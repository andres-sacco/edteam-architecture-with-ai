package com.edteam.reservations.infrastructure.adapter.out.airport.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.edteam.reservations.application.exception.AirportCatalogIntegrationException;
import com.edteam.reservations.application.exception.AirportCatalogThrottledException;
import com.edteam.reservations.application.exception.AirportCatalogUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("RetryingCityCatalogClient")
class RetryingCityCatalogClientTest {

    private static final RetryingCityCatalogClient.Retry RETRY =
            new RetryingCityCatalogClient.Retry(3, Duration.ofMillis(100), Duration.ofMillis(500));

    private static final CatalogCity BUENOS_AIRES = new CatalogCity("BUE", "Buenos Aires");

    @Mock
    private CityCatalogClient delegate;

    /** Registra las esperas en lugar de dormirlas: el test verifica la política, no el reloj. */
    private List<Duration> waits;

    private RetryingCityCatalogClient client;

    @BeforeEach
    void setUp() {
        waits = new ArrayList<>();
        client = new RetryingCityCatalogClient(delegate, RETRY, duration -> {
            waits.add(duration);
            return true;
        });
    }

    @Nested
    @DisplayName("Qué se reintenta")
    class WhatIsRetried {

        @Test
        @DisplayName("una respuesta exitosa no se reintenta")
        void doesNotRetryOnSuccess() {
            when(delegate.findByCode("BUE")).thenReturn(Optional.of(BUENOS_AIRES));

            assertThat(client.findByCode("BUE")).contains(BUENOS_AIRES);

            verify(delegate, times(1)).findByCode("BUE");
            assertThat(waits).isEmpty();
        }

        @Test
        @DisplayName("'la ciudad no existe' es una respuesta, no un fallo: tampoco se reintenta")
        void doesNotRetryAnEmptyResult() {
            when(delegate.findByCode("ZZZ")).thenReturn(Optional.empty());

            assertThat(client.findByCode("ZZZ")).isEmpty();

            verify(delegate, times(1)).findByCode("ZZZ");
        }

        @Test
        @DisplayName("un fallo transitorio se reintenta y se resuelve en el segundo intento")
        void retriesTransientFailures() {
            when(delegate.findByCode("BUE"))
                    .thenThrow(new AirportCatalogUnavailableException("El catálogo respondió 503"))
                    .thenReturn(Optional.of(BUENOS_AIRES));

            assertThat(client.findByCode("BUE")).contains(BUENOS_AIRES);

            verify(delegate, times(2)).findByCode("BUE");
            assertThat(waits).hasSize(1);
        }

        @Test
        @DisplayName("una integración rota NO se reintenta: repetir el mismo pedido da el mismo 4xx")
        void doesNotRetryIntegrationFailures() {
            when(delegate.findByCode("BUE"))
                    .thenThrow(new AirportCatalogIntegrationException("El catálogo rechazó la consulta con 401"));

            assertThatThrownBy(() -> client.findByCode("BUE")).isInstanceOf(AirportCatalogIntegrationException.class);

            // Insistir contra una credencial vencida sólo agrega latencia al
            // pedido del usuario y carga al proveedor.
            verify(delegate, times(1)).findByCode("BUE");
            assertThat(waits).isEmpty();
        }

        @Test
        @DisplayName("agotados los intentos, el fallo sube conservando la causa")
        void givesUpAfterTheLastAttempt() {
            AirportCatalogUnavailableException cause =
                    new AirportCatalogUnavailableException("El catálogo respondió 503");
            when(delegate.findByCode("BUE")).thenThrow(cause);

            assertThatThrownBy(() -> client.findByCode("BUE"))
                    .isInstanceOf(AirportCatalogUnavailableException.class)
                    .hasMessageContaining("después de 3 intentos")
                    .hasCause(cause);

            verify(delegate, times(3)).findByCode("BUE");
            // Se espera entre intentos, no después del último: insistir ya no
            // va a pasar, así que dormir sería tiempo regalado.
            assertThat(waits).hasSize(2);
        }

        @Test
        @DisplayName("con maxAttempts=1 no reintenta: es el comportamiento anterior, elegible por configuración")
        void canBeDisabled() {
            RetryingCityCatalogClient noRetries = new RetryingCityCatalogClient(
                    delegate, RetryingCityCatalogClient.Retry.disabled(), duration -> true);
            when(delegate.findByCode("BUE"))
                    .thenThrow(new AirportCatalogUnavailableException("El catálogo respondió 503"));

            assertThatThrownBy(() -> noRetries.findByCode("BUE"))
                    .isInstanceOf(AirportCatalogUnavailableException.class);

            verify(delegate, times(1)).findByCode("BUE");
        }

        @Test
        @DisplayName("si interrumpen el hilo, corta en lugar de seguir esperando")
        void stopsWhenInterrupted() {
            RetryingCityCatalogClient interrupted = new RetryingCityCatalogClient(delegate, RETRY, duration -> false);
            when(delegate.findByCode("BUE"))
                    .thenThrow(new AirportCatalogUnavailableException("El catálogo respondió 503"));

            assertThatThrownBy(() -> interrupted.findByCode("BUE"))
                    .isInstanceOf(AirportCatalogUnavailableException.class);

            verify(delegate, times(1)).findByCode("BUE");
        }
    }

    @Nested
    @DisplayName("La espera entre intentos")
    class Backoff {

        @Test
        @DisplayName("crece: el segundo reintento espera más que el primero")
        void growsExponentially() {
            when(delegate.findByCode("BUE")).thenThrow(new AirportCatalogUnavailableException("503"));
            RetryingCityCatalogClient longRetries = new RetryingCityCatalogClient(
                    delegate,
                    new RetryingCityCatalogClient.Retry(4, Duration.ofMillis(100), Duration.ofSeconds(10)),
                    duration -> {
                        waits.add(duration);
                        return true;
                    });

            assertThatThrownBy(() -> longRetries.findByCode("BUE"))
                    .isInstanceOf(AirportCatalogUnavailableException.class);

            // Un reintento inmediato llega casi siempre mientras el proveedor
            // sigue caído: gasta un intento sin ganar nada.
            //
            // Se afirma sobre la banda de cada espera y no sobre el orden entre
            // ellas: con jitter, dos bandas contiguas se tocan en el extremo y
            // un test de "cada una mayor que la anterior" sería intermitente.
            assertThat(waits).hasSize(3);
            assertThat(waits.get(0)).isBetween(Duration.ofMillis(50), Duration.ofMillis(100));
            assertThat(waits.get(1)).isBetween(Duration.ofMillis(100), Duration.ofMillis(200));
            assertThat(waits.get(2)).isBetween(Duration.ofMillis(200), Duration.ofMillis(400));
        }

        @Test
        @DisplayName("no supera el techo configurado")
        void respectsTheCap() {
            RetryingCityCatalogClient.Retry capped =
                    new RetryingCityCatalogClient.Retry(10, Duration.ofMillis(100), Duration.ofMillis(500));

            for (int attempt = 1; attempt <= 10; attempt++) {
                assertThat(capped.backoffFor(attempt))
                        .as("espera del intento %d", attempt)
                        .isLessThanOrEqualTo(Duration.ofMillis(500));
            }
        }

        @Test
        @DisplayName("lleva jitter: dos esperas del mismo intento no son siempre iguales")
        void appliesJitter() {
            // Sin jitter, todas las instancias reintentan en el mismo instante
            // contra un proveedor que ya está devolviendo 429, y la
            // degradación se convierte en caída.
            RetryingCityCatalogClient.Retry retry =
                    new RetryingCityCatalogClient.Retry(5, Duration.ofSeconds(1), Duration.ofSeconds(10));

            List<Duration> sample = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                sample.add(retry.backoffFor(1));
            }

            // Sin jitter esto daría un único valor repetido 100 veces. No se
            // pide que las 100 sean distintas —el sorteo puede repetir— sino
            // que haya dispersión de verdad.
            assertThat(Set.copyOf(sample))
                    .as("valores distintos entre 100 sorteos")
                    .hasSizeGreaterThan(10);
            assertThat(sample)
                    .allSatisfy(wait -> assertThat(wait).isBetween(Duration.ofMillis(500), Duration.ofSeconds(1)));
        }
    }

    @Nested
    @DisplayName("El presupuesto del itinerario")
    class Budget {

        /** Lo que cuesta un intento completo: connect 300 ms + read 700 ms. */
        private static final Duration ATTEMPT_COST = Duration.ofSeconds(1);

        @Test
        @DisplayName("no arranca un reintento que no entra en lo que queda del presupuesto")
        void doesNotRetryWhenTheBudgetIsAlmostGone() {
            when(delegate.findByCode("BUE")).thenThrow(new AirportCatalogUnavailableException("503"));
            Clock clock = Clock.systemUTC();
            RetryingCityCatalogClient budgeted = new RetryingCityCatalogClient(
                    delegate,
                    RETRY,
                    duration -> {
                        waits.add(duration);
                        return true;
                    },
                    ATTEMPT_COST,
                    clock,
                    new SimpleMeterRegistry());

            // Quedan 200 ms y un intento completo cuesta 1 s: el reintento
            // llegaría tarde para este pedido y sólo le sacaría tiempo a las
            // ciudades que faltan.
            Instant deadline = clock.instant().plusMillis(200);
            assertThatThrownBy(() -> CatalogDeadline.within(deadline, () -> budgeted.findByCode("BUE")))
                    .isInstanceOf(AirportCatalogUnavailableException.class);

            verify(delegate, times(1)).findByCode("BUE");
            assertThat(waits).isEmpty();
        }

        @Test
        @DisplayName("con presupuesto de sobra reintenta normalmente")
        void retriesWhenTheBudgetAllowsIt() {
            when(delegate.findByCode("BUE"))
                    .thenThrow(new AirportCatalogUnavailableException("503"))
                    .thenReturn(Optional.of(BUENOS_AIRES));
            Clock clock = Clock.systemUTC();
            RetryingCityCatalogClient budgeted = new RetryingCityCatalogClient(
                    delegate,
                    RETRY,
                    duration -> {
                        waits.add(duration);
                        return true;
                    },
                    ATTEMPT_COST,
                    clock,
                    new SimpleMeterRegistry());

            Instant deadline = clock.instant().plusSeconds(30);
            assertThat(CatalogDeadline.within(deadline, () -> budgeted.findByCode("BUE")))
                    .contains(BUENOS_AIRES);

            verify(delegate, times(2)).findByCode("BUE");
            assertThat(waits).hasSize(1);
        }

        @Test
        @DisplayName("sin presupuesto instalado el corte no existe: el retry se comporta como siempre")
        void withoutABudgetNothingIsCut() {
            when(delegate.findByCode("BUE"))
                    .thenThrow(new AirportCatalogUnavailableException("503"))
                    .thenReturn(Optional.of(BUENOS_AIRES));
            RetryingCityCatalogClient budgeted = new RetryingCityCatalogClient(
                    delegate,
                    RETRY,
                    duration -> {
                        waits.add(duration);
                        return true;
                    },
                    ATTEMPT_COST,
                    Clock.systemUTC(),
                    new SimpleMeterRegistry());

            assertThat(budgeted.findByCode("BUE")).contains(BUENOS_AIRES);
            assertThat(waits).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Lo que cambió respecto de la política anterior")
    class PolicyChanges {

        @Test
        @DisplayName("un 429 NO se reintenta: insistir es desobedecer al proveedor que pidió aire")
        void doesNotRetryThrottling() {
            // Antes se reintentaba. El cambio es deliberado: el 429 sí cuenta
            // para el circuito —que es lo que de verdad frena el tráfico— pero
            // repetir el pedido empeora la saturación del proveedor justo
            // cuando está pidiendo aire.
            when(delegate.findByCode("BUE")).thenThrow(new AirportCatalogThrottledException("429 del catálogo"));

            assertThatThrownBy(() -> client.findByCode("BUE")).isInstanceOf(AirportCatalogThrottledException.class);

            verify(delegate, times(1)).findByCode("BUE");
            assertThat(waits).isEmpty();
        }

        @Test
        @DisplayName("publica la métrica de reintentos, que antes no existía")
        void publishesRetryMetrics() {
            // «Revisar la métrica de reintentos bajo carga con la dependencia
            // caída» era imposible: sólo había dos log.warn.
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            when(delegate.findByCode("BUE"))
                    .thenThrow(new AirportCatalogUnavailableException("503"))
                    .thenReturn(Optional.of(BUENOS_AIRES));
            RetryingCityCatalogClient metered = new RetryingCityCatalogClient(
                    delegate, RETRY, duration -> true, Duration.ZERO, Clock.systemUTC(), registry);

            metered.findByCode("BUE");

            assertThat(registry.find(RetryingCityCatalogClient.RETRIES)
                            .tag("result", "attempted")
                            .counter()
                            .count())
                    .isEqualTo(1);
            assertThat(registry.find(RetryingCityCatalogClient.RETRIES)
                            .tag("result", "recovered")
                            .counter()
                            .count())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("el peor caso que declara la política coincide con el que se calcula de sus números")
        void worstCaseBackoffIsDerivedAndNotWritten() {
            // Los números documentados se generan a partir de la configuración
            // en lugar de escribirse: el javadoc anterior declaraba 6,5 s
            // porque omitía el connect timeout, y el real era 7,8 s. Todo
            // cálculo derivado quedaba 17 % corto.
            RetryingCityCatalogClient.Retry twoAttempts =
                    new RetryingCityCatalogClient.Retry(2, Duration.ofMillis(100), Duration.ofMillis(200));

            assertThat(twoAttempts.worstCaseBackoff()).isEqualTo(Duration.ofMillis(100));

            RetryingCityCatalogClient.Retry threeAttempts =
                    new RetryingCityCatalogClient.Retry(3, Duration.ofMillis(100), Duration.ofMillis(200));
            assertThat(threeAttempts.worstCaseBackoff()).isEqualTo(Duration.ofMillis(300));
        }
    }

    @Test
    @DisplayName("la política valida sus parámetros")
    void validatesThePolicy() {
        assertThatThrownBy(() -> new RetryingCityCatalogClient.Retry(0, Duration.ofMillis(1), Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryingCityCatalogClient.Retry(3, Duration.ZERO, Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryingCityCatalogClient.Retry(3, Duration.ofMillis(500), Duration.ofMillis(100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no puede ser menor");
    }

    @Test
    @DisplayName("exige delegado, política y sleeper")
    void validatesConstructorArguments() {
        assertThatThrownBy(() -> new RetryingCityCatalogClient(null, RETRY, duration -> true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RetryingCityCatalogClient(delegate, null, duration -> true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RetryingCityCatalogClient(delegate, RETRY, null))
                .isInstanceOf(NullPointerException.class);
    }
}
