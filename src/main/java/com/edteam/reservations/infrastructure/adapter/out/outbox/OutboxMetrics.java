package com.edteam.reservations.infrastructure.adapter.out.outbox;

import com.edteam.reservations.infrastructure.logging.LogFields;
import com.edteam.reservations.infrastructure.logging.Throwables;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Gauges del estado del outbox y de la DLQ del consumidor.
 *
 * <p>Son gauges y no counters porque describen una <b>profundidad</b>: lo que
 * importa no es cuántos mensajes pasaron sino cuántos están esperando y desde
 * cuándo. Es la diferencia entre "se despacharon 4.000 mensajes" y "hay 4.000
 * esperando y el más viejo tiene 40 minutos".
 *
 * <p>Series publicadas:
 * <ul>
 *   <li>{@code reservations.outbox.pending} — cuántos esperan. Si crece
 *       sostenido, el destino no acepta o el relay no corre;</li>
 *   <li>{@code reservations.outbox.lag} (segundos) — {@code now - min(enqueued_at)}
 *       de los pendientes. <b>Es el número que importa:</b> cuánto tarda una
 *       notificación desde que el hecho ocurrió;</li>
 *   <li>{@code reservations.outbox.dead} — dead letter del productor.
 *       <b>Alerta con &gt; 0:</b> alguien tiene que mirarla y reencolar;</li>
 *   <li>{@code reservations.outbox.dispatched.retained} — despachados sin
 *       purgar, para ver que la purga corre;</li>
 *   <li>{@code reservations.messaging.dlq.depth} — dead letter del
 *       <b>consumidor</b>, en el broker. <b>Alerta con &gt; 0.</b></li>
 * </ul>
 *
 * <p>Las dos dead letters se miden por separado a propósito: una dice "no
 * pudimos publicar" y la otra "no pudieron procesar". Se resuelven en lugares
 * distintos y con gente distinta.
 *
 * <h2>Una sola consulta cada pocos segundos</h2>
 * Un gauge se evalúa en cada raspado del recolector, y son cinco series sobre
 * la misma foto. Se cachea el resultado por {@code reservations.outbox.metrics-cache}
 * para no convertir el monitoreo en carga sobre la base. La ventana es
 * configurable —y no una constante— porque los tests necesitan leer el valor
 * del instante y no el de hace cinco segundos.
 */
public class OutboxMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    public static final String PENDING = "reservations.outbox.pending";
    public static final String LAG = "reservations.outbox.lag";
    public static final String DEAD = "reservations.outbox.dead";
    public static final String RETAINED = "reservations.outbox.dispatched.retained";
    public static final String DLQ_DEPTH = "reservations.messaging.dlq.depth";

    /**
     * Lecturas del estado del outbox que fallaron.
     *
     * <p>Existe porque una métrica ciega es un incidente en sí misma: si este
     * contador crece, los cinco gauges de abajo están devolviendo el centinela
     * y las alertas 4 y 5 no pueden disparar. La octava regla de Prometheus
     * mira este contador, no los gauges.
     */
    public static final String READ_ERRORS = "reservations.outbox.metrics.errors";

    /**
     * Centinela de «no sé».
     *
     * <p>Es el hallazgo 15, y el repositorio ya conocía la regla: el
     * {@code depth()} de la DLQ devuelve {@code -1} con el broker caído y su
     * comentario explica por qué —«un cero sería una afirmación falsa, y en un
     * tablero con alerta en {@code > 0} una afirmación falsa tranquiliza»—.
     * Estos gauges devolvían {@code OutboxStats.EMPTY}, o sea ceros, con la
     * base caída: la alerta 4 ({@code lag > 300}, P1) y la 5
     * ({@code increase(dead) > 0}) fallaban en silencio <b>en el modo de falla
     * que existen para atrapar</b>.
     */
    private static final long UNKNOWN = -1L;

    private final OutboxAdmin outbox;
    private final Supplier<Long> dlqDepth;
    private final Duration cacheTtl;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    private final AtomicBoolean readable = new AtomicBoolean(true);
    private final AtomicReference<Counter> readErrors = new AtomicReference<>();

    public OutboxMetrics(OutboxAdmin outbox, Supplier<Long> dlqDepth, Duration cacheTtl) {
        this.outbox = Objects.requireNonNull(outbox);
        this.dlqDepth = Objects.requireNonNull(dlqDepth);
        this.cacheTtl = Objects.requireNonNull(cacheTtl);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        readErrors.set(Counter.builder(READ_ERRORS)
                .description("Lecturas del estado del outbox que fallaron: mientras crezca, "
                        + "los gauges del outbox devuelven el centinela y las alertas están ciegas")
                .register(registry));

        io.micrometer.core.instrument.Gauge.builder(PENDING, this, m -> m.honest(OutboxStats::pending))
                .description("Mensajes del outbox esperando publicación. -1 = no se pudo leer")
                .register(registry);
        io.micrometer.core.instrument.Gauge.builder(LAG, this,
                        m -> m.honest(stats -> stats.lag().toMillis() / 1000.0))
                .baseUnit("seconds")
                .description("Antigüedad del mensaje pendiente más viejo: el retraso real de la "
                        + "notificación. -1 = no se pudo leer")
                .register(registry);
        io.micrometer.core.instrument.Gauge.builder(DEAD, this, m -> m.honest(OutboxStats::dead))
                .description("Dead letter del PRODUCTOR: mensajes que no se pudieron publicar. "
                        + "Alerta con > 0. -1 = no se pudo leer")
                .register(registry);
        io.micrometer.core.instrument.Gauge.builder(RETAINED, this, m -> m.honest(OutboxStats::dispatched))
                .description("Mensajes ya publicados y todavía no purgados. -1 = no se pudo leer")
                .register(registry);
        io.micrometer.core.instrument.Gauge.builder(DLQ_DEPTH, this, m -> m.dlqDepth.get())
                .description("Dead letter del CONSUMIDOR, en el broker. Alerta con > 0. -1 = no se pudo leer")
                .register(registry);
    }

    /**
     * Un gauge que no afirma lo que no puede verificar.
     *
     * <p>Devuelve el valor leído, o {@code -1} si la última lectura falló. La
     * alternativa —el último valor conocido, o cero— es peor que no tener la
     * métrica: con la base caída el lag no sube, se congela, y la alerta que
     * existe para avisar que las notificaciones no salen se queda callada
     * exactamente cuando hay que despertarla.
     */
    private double honest(java.util.function.ToDoubleFunction<OutboxStats> field) {
        // El orden importa: PRIMERO se lee —que es lo que actualiza la marca de
        // «esta foto es verificada»— y recién después se decide si el valor se
        // puede afirmar. Al revés, la primera lectura fallida devolvería el
        // cero de OutboxStats.EMPTY, que es exactamente la afirmación falsa que
        // este método existe para no hacer.
        OutboxStats stats = stats();
        return readable.get() ? field.applyAsDouble(stats) : UNKNOWN;
    }

    private OutboxStats stats() {
        Snapshot current = snapshot.get();
        long now = System.nanoTime();
        if (current != null && now - current.takenAt() < cacheTtl.toNanos()) {
            return current.stats();
        }
        try {
            OutboxStats fresh = outbox.stats();
            snapshot.set(new Snapshot(fresh, now));
            readable.set(true);
            return fresh;
        } catch (DataAccessException e) {
            // Que el monitoreo no tire el raspado completo, y que tampoco
            // mienta: se marca la foto como no verificada —los gauges pasan a
            // -1— y se cuenta el fallo, que es lo que alerta la regla 8.
            readable.set(false);
            Counter counter = readErrors.get();
            if (counter != null) {
                counter.increment();
            }
            log.atWarn()
                    .addKeyValue(LogFields.EVENT, "outbox.metrics_unreadable")
                    .addKeyValue(LogFields.EXCEPTION_CLASS, Throwables.rootClassOf(e))
                    .addKeyValue(LogFields.REASON, Throwables.reasonOf(e))
                    .log("No se pudieron leer las métricas del outbox: los gauges pasan al centinela");
            return current != null ? current.stats() : OutboxStats.EMPTY;
        }
    }

    private record Snapshot(OutboxStats stats, long takenAt) {
    }
}
