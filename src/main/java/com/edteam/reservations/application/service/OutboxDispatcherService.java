package com.edteam.reservations.application.service;

import com.edteam.reservations.application.exception.EventPublishException;
import com.edteam.reservations.application.exception.EventPublisherUnavailableException;
import com.edteam.reservations.application.outbox.OutboxDispatchResult;
import com.edteam.reservations.application.outbox.OutboxFailure;
import com.edteam.reservations.application.outbox.OutboxMessage;
import com.edteam.reservations.application.port.in.DispatchPendingNotificationsUseCase;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.application.port.out.EventPublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Relay del outbox: toma los hechos pendientes y los publica.
 *
 * <p>Es la pieza que hace asincrónica la integración: corre fuera del hilo que
 * atiende al usuario, así que si el destino está lento o caído las reservas no
 * se ven afectadas — los mensajes se acumulan en el outbox y se reintentan.
 *
 * <p>Tres reglas, y las tres son correcciones de defectos concretos:
 *
 * <ol>
 *   <li><b>Un fallo aislado no corta el lote.</b> Cada mensaje se marca por
 *       separado; el resto se sigue intentando.</li>
 *   <li><b>Los fallos se clasifican.</b> {@link EventPublishException} es
 *       transitorio y se reintenta con backoff; cualquier otra excepción es
 *       permanente y va a la dead letter en el primer intento. Quien clasifica
 *       es el adaptador, que es el único que sabe si el error fue del broker o
 *       del payload; acá sólo se traduce el tipo de la excepción.</li>
 *   <li><b>Se bloquea por reserva, no por lote.</b> Si un mensaje de una
 *       reserva falla, los que le siguen <em>de esa misma reserva</em> no se
 *       publican en este lote y vuelven al estado pendiente sin gastar un
 *       intento. Sin esto, un alta que falla una vez y una cancelación que sale
 *       bien producen «se canceló tu reserva» antes de «registramos tu
 *       reserva». El orden global no se promete —el consumidor lo resuelve con
 *       {@code sequence}— pero el de una misma reserva sí se cuida acá, que es
 *       donde sale barato.</li>
 * </ol>
 *
 * <p>La entrega es <em>at-least-once</em>: el consumidor tiene que deduplicar
 * por el id del mensaje, y ahora recibe ese id porque lo que cruza el puerto es
 * el {@link OutboxMessage} completo y no sólo el evento.
 */
@Service
public class OutboxDispatcherService implements DispatchPendingNotificationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcherService.class);

    /** Misma clave que usa el filtro HTTP: la traza no se corta en el relay. */
    private static final String MDC_CORRELATION_ID = "correlationId";

    private final EventOutboxPort eventOutbox;
    private final EventPublisherPort eventPublisher;

    public OutboxDispatcherService(EventOutboxPort eventOutbox, EventPublisherPort eventPublisher) {
        this.eventOutbox = Objects.requireNonNull(eventOutbox);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
    }

    @Override
    public OutboxDispatchResult dispatchPending(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("El tamaño del lote debe ser positivo");
        }
        return dispatch(eventOutbox.pollPending(batchSize), false);
    }

    /**
     * {@inheritDoc}
     *
     * <p>La diferencia con un despacho normal es una sola y es la que importa:
     * si la publicación falla, el mensaje se <strong>libera</strong> en lugar
     * de contarle el intento. Una sonda existe para averiguar si el destino
     * volvió, no para entregar; cobrarle el intento al mensaje que tuvo la
     * mala suerte de ser elegido lo acerca a la dead letter por un problema
     * que no es suyo. Con un backlog chico —cinco mensajes una madrugada— sin
     * esto cada uno agotaría su tope en menos de una hora y moriría igual, que
     * es exactamente lo que el circuito venía a evitar.
     */
    @Override
    public OutboxDispatchResult dispatchProbe() {
        return dispatch(eventOutbox.pollProbe(), true);
    }

    private OutboxDispatchResult dispatch(List<OutboxMessage> claimed, boolean probe) {
        if (claimed.isEmpty()) {
            return OutboxDispatchResult.EMPTY;
        }

        int dispatched = 0;
        int failed = 0;
        Set<String> blockedSubjects = new HashSet<>();
        List<String> deferred = new ArrayList<>();
        boolean publisherDown = false;

        for (OutboxMessage message : claimed) {
            if (publisherDown || blockedSubjects.contains(message.subject())) {
                deferred.add(message.id());
                continue;
            }
            Outcome outcome = publish(message, probe);
            switch (outcome) {
                case DISPATCHED -> dispatched++;
                case FAILED -> {
                    failed++;
                    blockedSubjects.add(message.subject());
                }
                case NOT_ATTEMPTED -> {
                    // El publicador avisó que el destino está caído: nunca
                    // llegó a intentarlo. Se corta el lote entero acá, porque
                    // los que faltan van a recibir la misma respuesta, y
                    // ninguno gasta un intento.
                    publisherDown = true;
                    deferred.add(message.id());
                }
            }
        }

        if (!deferred.isEmpty()) {
            // Sin intento gastado: no fallaron. O esperan a que salga el
            // mensaje anterior de su reserva, o ni se intentaron porque el
            // destino está caído, o eran una sonda que no prosperó.
            eventOutbox.release(deferred);
            log.info("{} mensajes liberados sin gastar intento{}", deferred.size(),
                    publisherDown ? " (el destino no está disponible)" : "");
        }

        log.debug("Despacho de outbox{}: {} publicados, {} fallidos, {} liberados",
                probe ? " (sonda)" : "", dispatched, failed, deferred.size());
        return new OutboxDispatchResult(dispatched, failed, deferred.size());
    }

    private enum Outcome {
        /** Publicado y confirmado por el destino. */
        DISPATCHED,
        /** Se intentó y falló: el intento se cuenta. */
        FAILED,
        /** No se intentó siquiera, o era una sonda. El intento NO se cuenta. */
        NOT_ATTEMPTED
    }

    private Outcome publish(OutboxMessage message, boolean probe) {
        // El correlation id del pedido que originó el hecho se restituye acá:
        // sin esto la traza se corta justo en el salto de lo sincrónico a lo
        // asincrónico, que es donde más cuesta reconstruirla a mano.
        boolean correlated = message.correlationId() != null;
        if (correlated) {
            MDC.put(MDC_CORRELATION_ID, message.correlationId());
        }
        try {
            eventPublisher.publish(message);
            eventOutbox.markDispatched(message.id());
            return Outcome.DISPATCHED;
        } catch (EventPublisherUnavailableException e) {
            // El publicador sabe que el destino está caído y ni lo intentó.
            // Es la diferencia entre «el mensaje llegará tarde» y «el mensaje
            // está un intento más cerca de la dead letter».
            log.info("No se intentó publicar {} (mensaje {}): {}", message.type(), message.id(), e.getMessage());
            return Outcome.NOT_ATTEMPTED;
        } catch (EventPublishException e) {
            if (probe) {
                // Una sonda no es un intento de entrega: el mensaje vuelve
                // como estaba y el circuito ya se enteró del fallo.
                log.info("La sonda sobre {} (mensaje {}) no prosperó; se libera sin gastar intento: {}",
                        message.type(), message.id(), e.getMessage());
                return Outcome.NOT_ATTEMPTED;
            }
            log.warn("Fallo transitorio publicando {} (mensaje {}, reserva {}, intento {}): {}",
                    message.type(), message.id(), message.subject(), message.attempts() + 1, e.getMessage());
            eventOutbox.markFailed(message.id(), e.getMessage(), OutboxFailure.TRANSIENT);
            return Outcome.FAILED;
        } catch (RuntimeException e) {
            // Permanente: insistir no cambia el resultado. Va a la dead letter
            // ahora y no después de quemar cinco intentos del despachador.
            log.error("Fallo permanente publicando {} (mensaje {}, reserva {}): {}. "
                            + "Va a la dead letter del productor sin reintentos.",
                    message.type(), message.id(), message.subject(), e.toString());
            eventOutbox.markFailed(message.id(), e.toString(), OutboxFailure.PERMANENT);
            return Outcome.FAILED;
        } finally {
            if (correlated) {
                MDC.remove(MDC_CORRELATION_ID);
            }
        }
    }
}
