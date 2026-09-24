package com.edteam.reservations.infrastructure.resilience;

import com.edteam.reservations.infrastructure.logging.LogSanitizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

/**
 * El único camino por el que una respuesta degradada sale del sistema.
 *
 * <p>La restricción es «el fallback no miente en silencio»: toda respuesta que
 * no salió del camino feliz deja rastro en <strong>tres</strong> lugares —una
 * métrica con su motivo, un {@code WARN} y la marca que el borde convierte en
 * {@code X-Degraded}— y los tres se escriben acá, juntos, para que no pueda
 * agregarse un fallback nuevo y olvidarse uno.
 *
 * <p>El texto del motivo pasa por {@link LogSanitizer}: puede venir del cuerpo
 * de error de un tercero, y un salto de línea ahí adentro parte una entrada de
 * log en dos.
 */
public class DegradationRecorder {

    private static final Logger log = LoggerFactory.getLogger(DegradationRecorder.class);

    /** Respuestas servidas por un camino degradado, por dependencia y motivo. */
    public static final String SERVED = "reservations.degraded.responses";

    /** Edad del dato servido desde el <em>stale</em>. Dice cuánto se atrasó la verdad. */
    public static final String STALE_AGE = "reservations.degraded.stale.age";

    /** Casos en los que no hubo fallback posible y el pedido falló de frente. */
    public static final String EXHAUSTED = "reservations.degraded.exhausted";

    private final MeterRegistry registry;

    public DegradationRecorder(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "El registro de métricas es obligatorio");
    }

    /**
     * Una respuesta salió por el fallback.
     *
     * @param dependency la dependencia degradada, tal cual sale en {@code X-Degraded}
     * @param reason     por qué: {@code circuit_open}, {@code retries_exhausted},
     *                   {@code budget_exhausted}, {@code cache_down}…
     * @param detail     qué se sirvió, para el log
     * @param staleness  edad del dato servido, o {@code null} si no aplica
     */
    public void served(String dependency, String reason, String detail, Duration staleness) {
        Counter.builder(SERVED)
                .tags(Tags.of("dependency", dependency, "reason", reason))
                .description("Respuestas servidas por un camino degradado en lugar del origen")
                .register(registry)
                .increment();

        if (staleness != null) {
            Timer.builder(STALE_AGE)
                    .tags(Tags.of("dependency", dependency))
                    .description("Antigüedad del dato servido desde la ventana de gracia")
                    .register(registry)
                    .record(staleness);
        }

        Degradation.mark(dependency);

        log.warn("[degradado] {} respondió por fallback ({}): {}{}",
                dependency, reason, LogSanitizer.sanitize(detail, 256),
                staleness == null ? "" : ", dato de hace %d s".formatted(staleness.toSeconds()));
    }

    /**
     * No hubo fallback posible: el pedido va a fallar de forma explícita.
     *
     * <p>Se cuenta aparte a propósito. Mezclarlo con {@link #served} haría que
     * el panel mostrara «el fallback funcionó» en el caso en que justamente no
     * funcionó, que es el que hay que mirar.
     */
    public void exhausted(String dependency, String reason, String detail) {
        Counter.builder(EXHAUSTED)
                .tags(Tags.of("dependency", dependency, "reason", reason))
                .description("Pedidos que no tuvieron ningún fallback y fallaron de forma explícita")
                .register(registry)
                .increment();
        log.warn("[degradado] {} sin fallback posible ({}): {}",
                dependency, reason, LogSanitizer.sanitize(detail, 256));
    }
}
