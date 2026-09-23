package com.edteam.reservations.infrastructure.adapter.out.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.time.Duration;
import java.util.Objects;
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

    private final OutboxAdmin outbox;
    private final Supplier<Long> dlqDepth;
    private final Duration cacheTtl;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public OutboxMetrics(OutboxAdmin outbox, Supplier<Long> dlqDepth, Duration cacheTtl) {
        this.outbox = Objects.requireNonNull(outbox);
        this.dlqDepth = Objects.requireNonNull(dlqDepth);
        this.cacheTtl = Objects.requireNonNull(cacheTtl);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        io.micrometer.core.instrument.Gauge.builder(PENDING, this, m -> m.stats().pending())
                .description("Mensajes del outbox esperando publicación")
                .register(registry);
        io.micrometer.core.instrument.Gauge.builder(LAG, this, m -> m.stats().lag().toMillis() / 1000.0)
                .baseUnit("seconds")
                .description("Antigüedad del mensaje pendiente más viejo: el retraso real de la notificación")
                .register(registry);
        io.micrometer.core.instrument.Gauge.builder(DEAD, this, m -> m.stats().dead())
                .description("Dead letter del PRODUCTOR: mensajes que no se pudieron publicar. Alerta con > 0")
                .register(registry);
        io.micrometer.core.instrument.Gauge.builder(RETAINED, this, m -> m.stats().dispatched())
                .description("Mensajes ya publicados y todavía no purgados")
                .register(registry);
        io.micrometer.core.instrument.Gauge.builder(DLQ_DEPTH, this, m -> m.dlqDepth.get())
                .description("Dead letter del CONSUMIDOR, en el broker. Alerta con > 0")
                .register(registry);
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
            return fresh;
        } catch (DataAccessException e) {
            // Que el monitoreo no tire el raspado completo. Se devuelve lo
            // último conocido —o ceros— y se avisa: una métrica que falla no
            // puede ser un incidente peor que el que está midiendo.
            log.warn("No se pudieron leer las métricas del outbox: {}", e.getMessage());
            return current != null ? current.stats() : OutboxStats.EMPTY;
        }
    }

    private record Snapshot(OutboxStats stats, long takenAt) {
    }
}
