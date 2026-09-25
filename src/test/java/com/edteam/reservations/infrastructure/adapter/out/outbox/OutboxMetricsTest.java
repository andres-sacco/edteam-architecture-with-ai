package com.edteam.reservations.infrastructure.adapter.out.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/**
 * Un gauge no puede afirmar lo que no puede verificar.
 *
 * <h2>El hallazgo 15, que es el más peligroso de la lista</h2>
 * Los gauges del outbox devolvían {@code OutboxStats.EMPTY} —o sea ceros—
 * cuando la base no respondía. Las dos alertas que dependen de ellos
 * —{@code reservations_outbox_lag > 300} (P1) y
 * {@code increase(reservations_outbox_dead[30m]) > 0}— fallaban en silencio
 * <b>en el modo de falla que existen para atrapar</b>: con la base caída el lag
 * no sube, se congela o se va a cero, y un cero en un tablero con alerta en
 * {@code > 0} es una afirmación falsa que tranquiliza.
 *
 * <p>El repositorio ya conocía la regla y la aplicaba en el otro gauge:
 * {@code RabbitDeadLetterQueue.depth()} devuelve {@code -1} con el broker caído
 * y su comentario explica exactamente esto. Lo que faltaba era aplicarla acá.
 */
@DisplayName("Gauges del outbox")
class OutboxMetricsTest {

    private static final OutboxStats HEALTHY = new OutboxStats(12, 3, Duration.ofSeconds(42), 500);

    /**
     * El binder se conserva en un campo a propósito: Micrometer guarda una
     * referencia DÉBIL al objeto del gauge, y sin esto el recolector se lo
     * lleva en medio del test y las series devuelven NaN. En la aplicación es
     * un bean del contexto, así que el problema no existe.
     */
    private OutboxMetrics metrics;

    @Test
    @DisplayName("con la base sana publica los valores leídos")
    void publishesRealValuesWhenTheDatabaseAnswers() {
        MeterRegistry registry = new SimpleMeterRegistry();
        metrics = new OutboxMetrics(stubOutbox(new AtomicBoolean(true)), () -> 7L, Duration.ZERO);
        metrics.bindTo(registry);

        assertThat(gauge(registry, OutboxMetrics.PENDING)).isEqualTo(12.0);
        assertThat(gauge(registry, OutboxMetrics.DEAD)).isEqualTo(3.0);
        assertThat(gauge(registry, OutboxMetrics.LAG)).isEqualTo(42.0);
        assertThat(gauge(registry, OutboxMetrics.RETAINED)).isEqualTo(500.0);
        assertThat(gauge(registry, OutboxMetrics.DLQ_DEPTH)).isEqualTo(7.0);
    }

    @Test
    @DisplayName("con la base caída devuelve el centinela y NO cero")
    void returnsTheSentinelWhenTheDatabaseIsDown() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AtomicBoolean readable = new AtomicBoolean(true);
        metrics = new OutboxMetrics(stubOutbox(readable), () -> 7L, Duration.ZERO);
        metrics.bindTo(registry);

        // Primera lectura sana: el snapshot queda cacheado, que es justamente
        // el valor congelado que antes se seguía publicando como si fuera real.
        assertThat(gauge(registry, OutboxMetrics.LAG)).isEqualTo(42.0);

        readable.set(false);

        assertThat(gauge(registry, OutboxMetrics.LAG))
                .withFailMessage("Con la base caída el lag tiene que decir «no sé», no «cero»: "
                        + "un cero apaga la alerta 4 exactamente cuando hay que despertarla")
                .isEqualTo(-1.0);
        assertThat(gauge(registry, OutboxMetrics.PENDING)).isEqualTo(-1.0);
        assertThat(gauge(registry, OutboxMetrics.DEAD)).isEqualTo(-1.0);
        assertThat(gauge(registry, OutboxMetrics.RETAINED)).isEqualTo(-1.0);
    }

    @Test
    @DisplayName("y cuenta el fallo, porque una métrica ciega es un incidente en sí misma")
    void countsTheReadFailure() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AtomicBoolean readable = new AtomicBoolean(false);
        metrics = new OutboxMetrics(stubOutbox(readable), () -> 7L, Duration.ZERO);
        metrics.bindTo(registry);

        gauge(registry, OutboxMetrics.LAG);

        // Es la regla 9 de docker/prometheus/rules/reservations.yml: mientras
        // este contador crezca, las alertas 4 y 5 están ciegas y hay que
        // saberlo explícitamente, no deducirlo de un gauge que no se mueve.
        assertThat(registry.find(OutboxMetrics.READ_ERRORS).counter())
                .isNotNull()
                .satisfies(counter -> assertThat(counter.count()).isPositive());
    }

    @Test
    @DisplayName("se recupera sola cuando la base vuelve")
    void recoversWhenTheDatabaseComesBack() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AtomicBoolean readable = new AtomicBoolean(false);
        metrics = new OutboxMetrics(stubOutbox(readable), () -> 7L, Duration.ZERO);
        metrics.bindTo(registry);

        assertThat(gauge(registry, OutboxMetrics.LAG)).isEqualTo(-1.0);
        readable.set(true);
        assertThat(gauge(registry, OutboxMetrics.LAG)).isEqualTo(42.0);
    }

    private static double gauge(MeterRegistry registry, String name) {
        Gauge gauge = registry.find(name).gauge();
        assertThat(gauge).withFailMessage("No se publicó el gauge '%s'", name).isNotNull();
        return gauge.value();
    }

    /** Un outbox que responde o falla según el interruptor. */
    private static OutboxAdmin stubOutbox(AtomicBoolean readable) {
        return new OutboxAdmin() {
            @Override
            public OutboxStats stats() {
                if (!readable.get()) {
                    throw new QueryTimeoutException("la base no respondió");
                }
                return HEALTHY;
            }

            @Override
            public List<DeadOutboxMessage> deadLetter(int limit) {
                return List.of();
            }

            @Override
            public boolean replay(String messageId) {
                return false;
            }

            @Override
            public int replayAll() {
                return 0;
            }

            @Override
            public int purgeDispatchedBefore(Instant limit) {
                return 0;
            }
        };
    }
}
