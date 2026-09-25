package com.edteam.reservations.infrastructure.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.edteam.reservations.application.exception.AirportCatalogUnavailableException;
import com.edteam.reservations.infrastructure.config.CircuitBreakerProperties;
import com.edteam.reservations.support.MutableClock;
import com.edteam.reservations.support.TestFixtures;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Las dos cosas que el envoltorio agrega sobre la librería.
 */
@DisplayName("Circuit")
class CircuitTest {

    @Test
    @DisplayName("hallazgo 18: la ventana caduca, así un incidente resuelto no decide el próximo")
    void theWindowExpires() {
        // El diseño elige COUNT_BASED con una razón correcta: con una ventana
        // temporal, a las 4 AM dos fallos serían el 100 % de la ventana. Pero
        // el efecto inverso no estaba cubierto: cincuenta llamadas pueden
        // abarcar varias horas de madrugada, así que fallos de un incidente YA
        // RESUELTO mantienen el circuito al borde de abrir. La mezcla correcta
        // es contar llamadas y además caducar la ventana.
        MutableClock clock = MutableClock.at(TestFixtures.NOW);
        Circuit circuit = circuit("caducidad", clock, Duration.ofMinutes(10));

        // Cuatro fallos: uno menos de los que hacen falta para abrir.
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> circuit.execute(CircuitTest::boom))
                    .isInstanceOf(AirportCatalogUnavailableException.class);
        }
        assertThat(circuit.breaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(4);

        // Pasan tres horas sin una sola llamada: lo que pasó esta madrugada ya
        // no dice nada sobre la salud del proveedor ahora.
        clock.advance(Duration.ofHours(3));

        assertThatThrownBy(() -> circuit.execute(CircuitTest::boom))
                .isInstanceOf(AirportCatalogUnavailableException.class);

        assertThat(circuit.state())
                .as("con la ventana viva, este quinto fallo habría abierto el circuito")
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuit.breaker().getMetrics().getNumberOfFailedCalls())
                .as("la ventana arranca de cero")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("la ventana NO caduca si el circuito está abierto: acortaría el tiempo de espera")
    void anOpenCircuitIsNeverReset() {
        MutableClock clock = MutableClock.at(TestFixtures.NOW);
        Circuit circuit = circuit("abierto-no-caduca", clock, Duration.ofMinutes(1));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> circuit.execute(CircuitTest::boom)).isInstanceOf(RuntimeException.class);
        }
        assertThat(circuit.state()).isEqualTo(CircuitBreaker.State.OPEN);

        clock.advance(Duration.ofHours(1));

        assertThat(circuit.state())
                .as("resetear acá cambiaría el tiempo abierto por una carrera con el reloj")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("apagado por configuración, deja pasar todo y no cuenta nada")
    void aDisabledCircuitLetsEverythingThrough() {
        // Aislar un problema en producción sacando un circuito de juego no
        // debería necesitar un redeploy ni un cableado distinto.
        Circuit circuit =
                Circuit.disabled("apagado", CircuitBreakerRegistry.ofDefaults(), MutableClock.at(TestFixtures.NOW));

        for (int i = 0; i < 50; i++) {
            assertThatThrownBy(() -> circuit.execute(CircuitTest::boom)).isInstanceOf(RuntimeException.class);
        }

        assertThat(circuit.isOpen()).isFalse();
    }

    private static Circuit circuit(String name, MutableClock clock, Duration windowMaxAge) {
        CircuitBreakerProperties properties = new CircuitBreakerProperties(
                true, 10, 5, 50, Duration.ofSeconds(10), 100, Duration.ofMinutes(5), 2, false, windowMaxAge);
        return Circuit.of(name, properties, Failures::catalog, CircuitBreakerRegistry.ofDefaults(), clock);
    }

    private static Object boom() {
        throw new AirportCatalogUnavailableException("503");
    }
}
