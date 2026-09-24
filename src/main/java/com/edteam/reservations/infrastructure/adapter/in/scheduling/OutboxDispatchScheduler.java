package com.edteam.reservations.infrastructure.adapter.in.scheduling;

import com.edteam.reservations.application.outbox.OutboxDispatchResult;
import com.edteam.reservations.application.port.in.DispatchPendingNotificationsUseCase;
import com.edteam.reservations.infrastructure.config.OutboxProperties;
import com.edteam.reservations.infrastructure.resilience.Circuit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dispara el relay del outbox cada pocos segundos.
 *
 * <p>Consulta el estado del circuito del broker <strong>antes</strong> de
 * trabajar, y eso no es redundante con el decorador que ya lo consulta por
 * mensaje. Sin este gate, el circuito ahorraría los segundos de
 * connect + confirm por mensaje, pero el relay igual reclamaría el lote
 * entero de la base para descartarlo: una transacción, cincuenta filas
 * actualizadas a {@code IN_FLIGHT} y otras cincuenta liberadas, cada cinco
 * segundos, durante toda la caída.
 *
 * <table>
 *   <caption>Qué hace el tick según el estado del circuito</caption>
 *   <tr><th>Estado</th><th>Tick</th></tr>
 *   <tr><td>{@code CLOSED}</td><td>Lote normal</td></tr>
 *   <tr><td>{@code OPEN}</td><td>No se dispara: ni consulta la base ni toca el broker</td></tr>
 *   <tr><td>{@code HALF_OPEN}</td><td>Una sonda: un mensaje al azar, sin gastarle el intento</td></tr>
 * </table>
 *
 * <p>Sin circuito configurado —o con el circuito apagado— el gate no existe y
 * el comportamiento es el de siempre.
 */
@Component
@ConditionalOnProperty(name = "reservations.outbox.dispatch-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatchScheduler.class);

    /** Ticks que no se dispararon porque el circuito del broker estaba abierto. */
    public static final String SKIPPED = "reservations.outbox.dispatch.skipped";

    /** Sondas disparadas en semiabierto. */
    public static final String PROBES = "reservations.outbox.dispatch.probes";

    private final DispatchPendingNotificationsUseCase dispatchNotifications;
    private final OutboxProperties properties;
    private final Circuit brokerCircuit;
    private final Counter skipped;
    private final Counter probes;

    public OutboxDispatchScheduler(DispatchPendingNotificationsUseCase dispatchNotifications,
                                   OutboxProperties properties,
                                   Circuit brokerCircuit,
                                   MeterRegistry registry) {
        this.dispatchNotifications = Objects.requireNonNull(dispatchNotifications);
        this.properties = Objects.requireNonNull(properties);
        this.brokerCircuit = brokerCircuit;
        Objects.requireNonNull(registry, "El registro de métricas es obligatorio");
        this.skipped = Counter.builder(SKIPPED)
                .description("Ticks del relay salteados porque el circuito del broker estaba abierto")
                .register(registry);
        this.probes = Counter.builder(PROBES)
                .description("Sondas del relay contra un broker que se cree recuperado")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${reservations.outbox.dispatch-interval:5s}",
            initialDelayString = "${reservations.outbox.dispatch-interval:5s}")
    public void dispatch() {
        try {
            jitter();
            OutboxDispatchResult result = tick();
            if (result.total() > 0) {
                log.info("Outbox despachado: {} publicados, {} fallidos, {} liberados",
                        result.dispatched(), result.failed(), result.deferred());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // Si se propaga, Spring cancela las siguientes ejecuciones de la
            // tarea: el relay quedaría muerto hasta el próximo reinicio.
            log.error("Error inesperado despachando el outbox", e);
        }
    }

    private OutboxDispatchResult tick() {
        if (brokerCircuit == null) {
            return dispatchNotifications.dispatchPending(properties.batchSize());
        }
        return switch (brokerCircuit.state()) {
            case OPEN, FORCED_OPEN -> {
                skipped.increment();
                // En DEBUG y no en WARN: la transición ya se logueó una vez
                // cuando el circuito abrió, y un WARN por tick durante una
                // caída de una hora son 720 líneas que no dicen nada nuevo.
                log.debug("Circuito del broker abierto: se saltea el tick del relay");
                yield OutboxDispatchResult.EMPTY;
            }
            case HALF_OPEN -> {
                probes.increment();
                yield dispatchNotifications.dispatchProbe();
            }
            default -> dispatchNotifications.dispatchPending(properties.batchSize());
        };
    }

    /**
     * Desfasa el arranque del tick entre instancias: sin esto, N relays que
     * arrancaron juntos consultan la base en el mismo instante cada cinco
     * segundos y compiten por las mismas filas.
     */
    private void jitter() throws InterruptedException {
        long spread = properties.dispatchInterval().toMillis() / 5;
        if (spread > 1) {
            Thread.sleep(ThreadLocalRandom.current().nextLong(spread));
        }
    }
}
