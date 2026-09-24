package com.edteam.reservations.infrastructure.adapter.in.ops;

import com.edteam.reservations.application.outbox.OutboxDispatchResult;
import com.edteam.reservations.application.port.in.DispatchPendingNotificationsUseCase;
import com.edteam.reservations.infrastructure.adapter.out.outbox.OutboxAdmin;
import com.edteam.reservations.infrastructure.adapter.out.outbox.OutboxStats;
import com.edteam.reservations.infrastructure.logging.LogFields;
import com.edteam.reservations.infrastructure.security.OpsActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Endpoint {@code outbox} del puerto de gestión: la dead letter del productor
 * con su camino de vuelta.
 *
 * <p>Es la pieza que convierte {@code FAILED} de un estado terminal del que
 * nadie se entera en una dead letter con dueño: se ve qué se perdió, por qué, y
 * se reenvía después de arreglar la causa. Antes el único rastro era una línea
 * de log que desaparecía con el reinicio.
 *
 * <h2>Por qué acá y no en la API</h2>
 * Va en el puerto de gestión (9090), que no se publica hacia afuera. Es la
 * mitigación estructural que ya usa el resto del actuator: no hace falta
 * acertar con la autorización de un endpoint que reencola mensajes si el puerto
 * no es alcanzable desde la red.
 *
 * <h2>Reencolar no puede duplicar</h2>
 * Depende de que {@code pollPending} <em>reclame</em> el mensaje. Sin ese
 * reclamo, este endpoint sería el segundo llamador concurrente del relay y los
 * dos publicarían el mismo mensaje. Por eso el reclamo se implementó primero.
 *
 * <p>Y aun así la entrega sigue siendo at-least-once: si el mensaje ya se
 * había publicado y lo que falló fue el ack, reencolarlo lo publica de nuevo.
 * El consumidor deduplica por {@code messageId}, que no cambia con el replay.
 *
 * <p>El listado no devuelve el payload: lleva ruta y fecha de viaje atadas a un
 * usuario, y esto se mira desde una consola. Para el contenido hay que
 * consultar la tabla.
 */
@Endpoint(id = "outbox")
public class OutboxEndpoint {

    private static final Logger log = LoggerFactory.getLogger(OutboxEndpoint.class);

    private final OutboxAdmin outbox;
    private final DispatchPendingNotificationsUseCase dispatchNotifications;

    public OutboxEndpoint(OutboxAdmin outbox, DispatchPendingNotificationsUseCase dispatchNotifications) {
        this.outbox = Objects.requireNonNull(outbox);
        this.dispatchNotifications = Objects.requireNonNull(dispatchNotifications);
    }

    /** {@code GET /actuator/outbox} — estado y dead letter. */
    @ReadOperation
    public Map<String, Object> read() {
        OutboxStats stats = outbox.stats();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pending", stats.pending());
        body.put("lagSeconds", stats.lag().toSeconds());
        body.put("dead", stats.dead());
        body.put("dispatchedRetained", stats.dispatched());
        body.put("deadLetter", outbox.deadLetter(50));
        return body;
    }

    /** {@code GET /actuator/outbox/{id}} — un mensaje muerto puntual. */
    @ReadOperation
    public OutboxAdmin.DeadOutboxMessage readOne(@Selector String messageId) {
        return outbox.deadLetter(500).stream()
                .filter(message -> message.id().equals(messageId))
                .findFirst()
                .orElse(null);
    }

    /**
     * {@code POST /actuator/outbox} — reencola la dead letter.
     *
     * <p>Con {@code messageId} reencola uno; sin él, todos. Con
     * {@code dispatch=true} corre además un lote del relay en el acto, para no
     * tener que esperar el próximo tick y ver el resultado del arreglo de una.
     */
    @WriteOperation
    public Map<String, Object> replay(@Nullable String messageId,
                                     @Nullable Boolean dispatch,
                                     @Nullable Integer batchSize) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (messageId != null && !messageId.isBlank()) {
            body.put("replayed", outbox.replay(messageId) ? 1 : 0);
            body.put("messageId", messageId);
        } else {
            body.put("replayed", outbox.replayAll());
        }
        if (Boolean.TRUE.equals(dispatch)) {
            OutboxDispatchResult result = dispatchNotifications.dispatchPending(
                    batchSize == null || batchSize <= 0 ? 50 : batchSize);
            body.put("dispatched", result.dispatched());
            body.put("failed", result.failed());
            body.put("deferred", result.deferred());
        }
        return body;
    }

    /**
     * {@code DELETE /actuator/outbox} — purga manual de los despachados.
     *
     * <p>La purga normal la hace el scheduler; esto existe para drenar a mano
     * cuando la tabla creció por una purga que no corrió.
     */
    @DeleteOperation
    public Map<String, Object> purge(@Nullable Integer olderThanDays) {
        int days = olderThanDays == null || olderThanDays < 0 ? 7 : olderThanDays;
        int purged = outbox.purgeDispatchedBefore(
                java.time.Instant.now().minus(java.time.Duration.ofDays(days)));
        // Esta operación BORRA filas de producción y no dejaba ninguna huella
        // (hallazgo 25). Ahora deja la suya, con el actor y con el
        // correlationId que el filtro del contexto de gestión pone.
        log.atInfo()
                .addKeyValue(LogFields.EVENT, LogFields.OUTBOX_PURGED)
                .addKeyValue(LogFields.PURGED, purged)
                .addKeyValue("olderThanDays", days)
                .addKeyValue(LogFields.ACTOR, OpsActor.current())
                .log("Purga manual del outbox");
        return Map.of("purged", purged, "olderThanDays", days);
    }
}
