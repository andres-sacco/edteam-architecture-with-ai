package com.edteam.reservations.application.service;

import com.edteam.reservations.application.exception.EventPublishException;
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

        List<OutboxMessage> claimed = eventOutbox.pollPending(batchSize);
        if (claimed.isEmpty()) {
            return OutboxDispatchResult.EMPTY;
        }

        int dispatched = 0;
        int failed = 0;
        Set<String> blockedSubjects = new HashSet<>();
        List<String> deferred = new ArrayList<>();

        for (OutboxMessage message : claimed) {
            if (blockedSubjects.contains(message.subject())) {
                deferred.add(message.id());
                continue;
            }
            if (publish(message)) {
                dispatched++;
            } else {
                failed++;
                blockedSubjects.add(message.subject());
            }
        }

        if (!deferred.isEmpty()) {
            // Sin intento gastado: no fallaron, sólo esperan a que salga el
            // mensaje anterior de su reserva.
            eventOutbox.release(deferred);
            log.info("{} mensajes postergados para no adelantar el orden de su reserva", deferred.size());
        }

        log.debug("Despacho de outbox: {} publicados, {} fallidos, {} postergados",
                dispatched, failed, deferred.size());
        return new OutboxDispatchResult(dispatched, failed, deferred.size());
    }

    /** @return {@code true} si se publicó */
    private boolean publish(OutboxMessage message) {
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
            return true;
        } catch (EventPublishException e) {
            log.warn("Fallo transitorio publicando {} (mensaje {}, reserva {}, intento {}): {}",
                    message.type(), message.id(), message.subject(), message.attempts() + 1, e.getMessage());
            eventOutbox.markFailed(message.id(), e.getMessage(), OutboxFailure.TRANSIENT);
            return false;
        } catch (RuntimeException e) {
            // Permanente: insistir no cambia el resultado. Va a la dead letter
            // ahora y no después de quemar cinco intentos del despachador.
            log.error("Fallo permanente publicando {} (mensaje {}, reserva {}): {}. "
                            + "Va a la dead letter del productor sin reintentos.",
                    message.type(), message.id(), message.subject(), e.toString());
            eventOutbox.markFailed(message.id(), e.toString(), OutboxFailure.PERMANENT);
            return false;
        } finally {
            if (correlated) {
                MDC.remove(MDC_CORRELATION_ID);
            }
        }
    }
}
