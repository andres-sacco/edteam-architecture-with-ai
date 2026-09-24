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
import java.util.Map;
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

    /**
     * Vocabulario del log de esta clase. Literales y no una constante de
     * {@code infrastructure.logging}: la capa de aplicación declara QUÉ dato
     * acompaña al hecho y no importa nada del sistema de logs —ArchUnit lo
     * verifica—. Que estos nombres coincidan con los del resto lo sostiene el
     * test de esquema, no un import.
     */
    private static final String EVENT = "event";

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
            //
            // A DEBUG y no a INFO: el número ya viaja como campo 'deferred' en
            // la línea del tick, y en INFO eran dos líneas por vuelta para el
            // mismo dato —2.160 por hora durante una caída del broker—.
            eventOutbox.release(deferred);
            log.atDebug()
                    .addKeyValue(EVENT, "outbox.deferred")
                    .addKeyValue("deferred", deferred.size())
                    .addKeyValue("publisherDown", publisherDown)
                    .log("Mensajes liberados sin gastar intento");
        }

        log.atDebug()
                .addKeyValue(EVENT, "outbox.batch")
                .addKeyValue("probe", probe)
                .addKeyValue("dispatched", dispatched)
                .addKeyValue("failed", failed)
                .addKeyValue("deferred", deferred.size())
                .log("Lote del outbox despachado");
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
        //
        // Se GUARDA el anterior y se restituye al salir, en lugar de borrarlo.
        // Es el hallazgo 10 de la auditoría: con 'remove', después del primer
        // mensaje el id de la corrida del relay quedaba vacío y el resto de la
        // vuelta —incluida la línea INFO del tick— salía sin ninguno.
        Map<String, String> previous = MDC.getCopyOfContextMap();
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
            //
            // A DEBUG y no a INFO: durante una caída del broker esto es una
            // línea por mensaje del lote, cada cinco segundos. Que el destino
            // no está se sabe por la transición del circuito, que se loguea
            // una vez, y por el lag del outbox, que es lo que alerta.
            log.atDebug()
                    .addKeyValue(EVENT, "outbox.not_attempted")
                    .addKeyValue("eventType", message.type())
                    .addKeyValue("messageId", message.id())
                    .log("No se intentó publicar: el destino no está disponible");
            return Outcome.NOT_ATTEMPTED;
        } catch (EventPublishException e) {
            if (probe) {
                // Una sonda no es un intento de entrega: el mensaje vuelve
                // como estaba y el circuito ya se enteró del fallo.
                log.atDebug()
                        .addKeyValue(EVENT, "outbox.probe")
                        .addKeyValue("eventType", message.type())
                        .addKeyValue("messageId", message.id())
                        .addKeyValue("outcome", "failed")
                        .log("La sonda no prosperó: se libera sin gastar intento");
                return Outcome.NOT_ATTEMPTED;
            }
            log.atWarn()
                    .addKeyValue(EVENT, "outbox.publish_failed")
                    .addKeyValue("eventType", message.type())
                    .addKeyValue("messageId", message.id())
                    .addKeyValue("subject", message.subject())
                    .addKeyValue("attempt", message.attempts() + 1)
                    .addKeyValue("failure", "transient")
                    .addKeyValue("exception.class", e.getClass().getSimpleName())
                    .log("Fallo transitorio publicando el mensaje");
            eventOutbox.markFailed(message.id(), e.getMessage(), OutboxFailure.TRANSIENT);
            return Outcome.FAILED;
        } catch (RuntimeException e) {
            // Permanente: insistir no cambia el resultado. Va a la dead letter
            // ahora y no después de quemar cinco intentos del despachador.
            //
            // Se escribe la CLASE de la excepción y no su toString(): el
            // mensaje de una excepción de JPA o de PostgreSQL trae los valores
            // enlazados, que es el hallazgo 4 de la auditoría. La columna
            // last_error de la fila sí guarda el detalle: es nuestra base, no
            // el SaaS de logs.
            log.atError()
                    .addKeyValue(EVENT, "outbox.dead_lettered")
                    .addKeyValue("eventType", message.type())
                    .addKeyValue("messageId", message.id())
                    .addKeyValue("subject", message.subject())
                    .addKeyValue("failure", "permanent")
                    .addKeyValue("exception.class", e.getClass().getSimpleName())
                    .log("Fallo permanente publicando: va a la dead letter del productor sin reintentos");
            eventOutbox.markFailed(message.id(), e.toString(), OutboxFailure.PERMANENT);
            return Outcome.FAILED;
        } finally {
            if (correlated) {
                restoreMdc(previous);
            }
        }
    }

    /**
     * Devuelve el MDC al estado en que estaba antes de publicar este mensaje.
     *
     * <p>{@code setContextMap(null)} tira {@code IllegalArgumentException}, así
     * que el caso «no había nada» se escribe a mano.
     */
    private static void restoreMdc(Map<String, String> previous) {
        if (previous == null || previous.isEmpty()) {
            MDC.remove(MDC_CORRELATION_ID);
        } else {
            MDC.setContextMap(previous);
        }
    }
}
