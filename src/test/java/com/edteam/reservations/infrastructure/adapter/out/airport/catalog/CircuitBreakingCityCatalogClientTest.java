package com.edteam.reservations.infrastructure.adapter.out.airport.catalog;

import com.edteam.reservations.application.exception.AirportCatalogIntegrationException;
import com.edteam.reservations.application.exception.AirportCatalogThrottledException;
import com.edteam.reservations.application.exception.AirportCatalogUnavailableException;
import com.edteam.reservations.infrastructure.config.CircuitBreakerProperties;
import com.edteam.reservations.infrastructure.resilience.Circuit;
import com.edteam.reservations.infrastructure.resilience.Failures;
import com.edteam.reservations.support.MutableClock;
import com.edteam.reservations.support.TestFixtures;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El circuito del catálogo: cuándo abre, cuándo no, y cómo vuelve solo.
 *
 * <p>Sin red y sin esperar tiempo real salvo donde el tiempo <em>es</em> lo
 * que se prueba —la recuperación automática—, y ahí con una espera de 150 ms
 * en lugar de los 5 s de producción.
 */
@DisplayName("Circuito del catálogo de ciudades")
class CircuitBreakingCityCatalogClientTest {

    private static final CatalogCity BUE = new CatalogCity("BUE", "Buenos Aires");

    private final AtomicInteger calls = new AtomicInteger();

    @Test
    @DisplayName("abre al alcanzar el umbral, y NO antes")
    void opensAtTheThresholdAndNotBefore() {
        // Mínimo 5 llamadas, 50 % de fallo. Con cuatro fallos el circuito no
        // tiene datos suficientes para decidir: abrir ahí sería frenar el
        // tráfico por una ráfaga desafortunada de un solo pedido.
        Failing delegate = new Failing();
        Circuit circuit = circuit("umbral", 10, 5, 50, Duration.ofSeconds(30));
        CityCatalogClient client = new CircuitBreakingCityCatalogClient(delegate, circuit);

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> client.findByCode("BUE"))
                    .isInstanceOf(AirportCatalogUnavailableException.class);
        }
        assertThat(circuit.state())
                .as("con 4 de 5 llamadas mínimas el circuito todavía no puede decidir")
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(delegate.calls()).isEqualTo(4);

        assertThatThrownBy(() -> client.findByCode("BUE"))
                .isInstanceOf(AirportCatalogUnavailableException.class);

        assertThat(circuit.state())
                .as("la quinta llamada completa el mínimo con 100 % de fallo")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("abierto, ni una llamada llega al delegado: eso es lo que se está comprando")
    void doesNotTouchTheDelegateWhileOpen() {
        Failing delegate = new Failing();
        Circuit circuit = circuit("abierto", 10, 5, 50, Duration.ofSeconds(30));
        CityCatalogClient client = new CircuitBreakingCityCatalogClient(delegate, circuit);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> client.findByCode("BUE")).isInstanceOf(RuntimeException.class);
        }
        int afterOpening = delegate.calls();

        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> client.findByCode("BUE"))
                    .isInstanceOf(CallNotPermittedException.class);
        }

        assertThat(delegate.calls())
                .as("veinte pedidos más y el proveedor caído no recibió ninguno")
                .isEqualTo(afterOpening);
    }

    @Test
    @DisplayName("un 404 del catálogo NO abre el circuito")
    void aNotFoundNeverOpensTheCircuit() {
        // «Esa ciudad no existe» es una respuesta de negocio y el proveedor
        // está sano: el cliente HTTP la devuelve como Optional vacío, que para
        // el circuito es una llamada exitosa. Si contara, una ráfaga de
        // códigos mal tipeados frenaría el tráfico justo cuando todo funciona.
        Circuit circuit = circuit("404", 10, 5, 50, Duration.ofSeconds(30));
        CityCatalogClient client = new CircuitBreakingCityCatalogClient(
                code -> {
                    calls.incrementAndGet();
                    return Optional.empty();
                }, circuit);

        for (int i = 0; i < 30; i++) {
            assertThat(client.findByCode("ZZZ")).isEmpty();
        }

        assertThat(circuit.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(calls.get()).isEqualTo(30);
    }

    @Test
    @DisplayName("un fallo permanente tampoco lo abre: un 503 genérico escondería la credencial vencida")
    void aPermanentFailureNeverOpensTheCircuit() {
        Circuit circuit = circuit("permanente", 10, 5, 50, Duration.ofSeconds(30));
        CityCatalogClient client = new CircuitBreakingCityCatalogClient(
                code -> {
                    throw new AirportCatalogIntegrationException("401 del catálogo");
                }, circuit);

        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> client.findByCode("BUE"))
                    .isInstanceOf(AirportCatalogIntegrationException.class);
        }

        assertThat(circuit.state())
                .as("el fallo que necesita una persona tiene que seguir viéndose, no taparse con un 503")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("un 429 SÍ lo abre: el proveedor está pidiendo que paremos de verdad")
    void throttlingOpensTheCircuit() {
        Circuit circuit = circuit("429", 10, 5, 50, Duration.ofSeconds(30));
        CityCatalogClient client = new CircuitBreakingCityCatalogClient(
                code -> {
                    throw new AirportCatalogThrottledException("429");
                }, circuit);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> client.findByCode("BUE")).isInstanceOf(RuntimeException.class);
        }

        assertThat(circuit.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("vuelve a cerrarse solo cuando la dependencia se recupera, sin reinicio ni intervención")
    void closesByItselfWhenTheDependencyRecovers() {
        AtomicReference<Boolean> healthy = new AtomicReference<>(false);
        // 150 ms en lugar de los 5 s de producción, y transición automática
        // encendida: la recuperación no puede depender de que llegue tráfico,
        // porque un circuito que se abrió justo cuando el tráfico cayó se
        // quedaría abierto hasta el próximo pedido.
        Circuit circuit = circuit("recuperacion", 10, 5, 50, Duration.ofMillis(150));
        CityCatalogClient client = new CircuitBreakingCityCatalogClient(
                code -> {
                    if (!healthy.get()) {
                        throw new AirportCatalogUnavailableException("503");
                    }
                    return Optional.of(BUE);
                }, circuit);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> client.findByCode("BUE")).isInstanceOf(RuntimeException.class);
        }
        assertThat(circuit.state()).isEqualTo(CircuitBreaker.State.OPEN);

        Awaitility.await().atMost(Duration.ofSeconds(3))
                .until(() -> circuit.state() == CircuitBreaker.State.HALF_OPEN);

        healthy.set(true);
        // Dos llamadas de prueba: es el 'permitted-calls-in-half-open-state'
        // de este circuito de test.
        assertThat(client.findByCode("BUE")).contains(BUE);
        assertThat(client.findByCode("BUE")).contains(BUE);

        Awaitility.await().atMost(Duration.ofSeconds(3))
                .until(() -> circuit.state() == CircuitBreaker.State.CLOSED);
    }

    private static Circuit circuit(String name, int window, int minimum, int failureRate, Duration open) {
        CircuitBreakerProperties properties = new CircuitBreakerProperties(
                true, window, minimum, failureRate, Duration.ofSeconds(10), 100,
                open, 2, true, Duration.ofHours(1));
        return Circuit.of(name, properties, Failures::catalog,
                CircuitBreakerRegistry.ofDefaults(), MutableClock.at(TestFixtures.NOW));
    }

    private static final class Failing implements CityCatalogClient {

        private final AtomicInteger calls = new AtomicInteger();

        int calls() {
            return calls.get();
        }

        @Override
        public Optional<CatalogCity> findByCode(String code) {
            calls.incrementAndGet();
            throw new AirportCatalogUnavailableException("El catálogo respondió 503");
        }
    }
}
