package com.edteam.reservations.infrastructure.adapter.out.airport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.edteam.reservations.application.exception.AirportCatalogIntegrationException;
import com.edteam.reservations.application.exception.AirportCatalogUnavailableException;
import com.edteam.reservations.domain.model.AirportCode;
import com.edteam.reservations.infrastructure.cache.CacheKeys;
import com.edteam.reservations.infrastructure.cache.FailingCacheStore;
import com.edteam.reservations.infrastructure.cache.InMemoryCacheStore;
import com.edteam.reservations.infrastructure.resilience.Degradation;
import com.edteam.reservations.infrastructure.resilience.DegradationRecorder;
import com.edteam.reservations.support.MutableClock;
import com.edteam.reservations.support.TestFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * El decorador de cache y el <em>stale-while-error</em>, reescrito sobre la
 * firma en bloque del puerto.
 *
 * <p>Los tests anteriores verificaban lo mismo de a un código por vez, que era
 * la única forma posible con {@code boolean exists(AirportCode)}. Se
 * conservaron todos los comportamientos que verificaban y se agregaron los
 * tres que la auditoría reclamaba y antes no se podían escribir: que un fallo
 * permanente no se tape, que un negativo vencido no se sirva, y que ninguna
 * respuesta degradada salga sin dejar rastro.
 */
@DisplayName("CachingAirportCatalog")
class CachingAirportCatalogTest {

    private static final Duration POSITIVE_TTL = Duration.ofMinutes(30);
    private static final Duration NEGATIVE_TTL = Duration.ofMinutes(5);
    private static final Duration STALE_WINDOW = Duration.ofHours(2);
    private static final CachingAirportCatalog.Ttl TTL =
            new CachingAirportCatalog.Ttl(POSITIVE_TTL, NEGATIVE_TTL, STALE_WINDOW);

    private MutableClock clock;
    private InMemoryCacheStore store;
    private RecordingResolver delegate;
    private MeterRegistry registry;
    private CachingAirportCatalog catalog;

    @BeforeEach
    void setUp() {
        clock = MutableClock.at(TestFixtures.NOW);
        store = new InMemoryCacheStore(clock, 1_000);
        delegate = new RecordingResolver();
        registry = new SimpleMeterRegistry();
        catalog = new CachingAirportCatalog(delegate, store, TTL, clock, new DegradationRecorder(registry), () -> true);
        Degradation.clear();
    }

    @AfterEach
    void clearDegradation() {
        Degradation.clear();
    }

    // =================================================================
    // Cache
    // =================================================================

    @Nested
    @DisplayName("Cache")
    class Caching {

        @Test
        @DisplayName("consulta el origen una sola vez para el mismo código")
        void cachesPositiveResult() {
            delegate.answer(TestFixtures.EZE, CityResolution.present());

            assertThat(unknown(TestFixtures.EZE)).isEmpty();
            assertThat(unknown(TestFixtures.EZE)).isEmpty();
            assertThat(unknown(TestFixtures.EZE)).isEmpty();

            assertThat(delegate.callsFor("EZE")).isEqualTo(1);
        }

        @Test
        @DisplayName("también cachea las respuestas negativas")
        void cachesNegativeResult() {
            delegate.answer(TestFixtures.EZE, CityResolution.absent());

            assertThat(unknown(TestFixtures.EZE)).containsExactly(TestFixtures.EZE);
            assertThat(unknown(TestFixtures.EZE)).containsExactly(TestFixtures.EZE);

            assertThat(delegate.callsFor("EZE")).isEqualTo(1);
        }

        @Test
        @DisplayName("resuelve el itinerario entero con una sola bajada al origen")
        void resolvesTheWholeItineraryInOneGo() {
            delegate.answer(TestFixtures.EZE, CityResolution.present());
            delegate.answer(TestFixtures.SCL, CityResolution.present());
            delegate.answer(TestFixtures.MAD, CityResolution.absent());

            assertThat(catalog.unknown(List.of(TestFixtures.EZE, TestFixtures.SCL, TestFixtures.MAD)))
                    .containsExactly(TestFixtures.MAD);
            assertThat(delegate.invocations())
                    .as("una sola bajada: es lo que habilita el paralelismo y el presupuesto")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("sólo baja al origen por lo que no está fresco en cache")
        void onlyResolvesWhatIsMissing() {
            delegate.answer(TestFixtures.EZE, CityResolution.present());
            unknown(TestFixtures.EZE);

            delegate.answer(TestFixtures.SCL, CityResolution.present());
            catalog.unknown(List.of(TestFixtures.EZE, TestFixtures.SCL));

            assertThat(delegate.callsFor("EZE")).isEqualTo(1);
            assertThat(delegate.callsFor("SCL")).isEqualTo(1);
        }

        @Test
        @DisplayName("vuelve a consultar el origen cuando vence el TTL")
        void refreshesAfterTtl() {
            delegate.answer(TestFixtures.EZE, CityResolution.present());
            assertThat(unknown(TestFixtures.EZE)).isEmpty();

            clock.advance(POSITIVE_TTL.minusSeconds(1));
            assertThat(unknown(TestFixtures.EZE)).isEmpty();
            assertThat(delegate.callsFor("EZE")).isEqualTo(1);

            clock.advance(Duration.ofSeconds(1));
            delegate.answer(TestFixtures.EZE, CityResolution.absent());
            assertThat(unknown(TestFixtures.EZE)).containsExactly(TestFixtures.EZE);
            assertThat(delegate.callsFor("EZE")).isEqualTo(2);
        }

        @Test
        @DisplayName("el negativo vence antes que el positivo: un alta nueva no queda rechazada media hora")
        void negativeResultsExpireSooner() {
            delegate.answer(TestFixtures.EZE, CityResolution.absent());
            assertThat(unknown(TestFixtures.EZE)).containsExactly(TestFixtures.EZE);

            clock.advance(NEGATIVE_TTL.minusSeconds(1));
            assertThat(unknown(TestFixtures.EZE)).containsExactly(TestFixtures.EZE);
            assertThat(delegate.callsFor("EZE")).isEqualTo(1);

            clock.advance(Duration.ofSeconds(1));
            delegate.answer(TestFixtures.EZE, CityResolution.present());
            assertThat(unknown(TestFixtures.EZE)).isEmpty();
            assertThat(delegate.callsFor("EZE")).isEqualTo(2);
        }

        @Test
        @DisplayName("en el cache sólo hay un booleano y un instante: nada sensible")
        void storesNothingSensitive() {
            delegate.answer(TestFixtures.EZE, CityResolution.present());
            unknown(TestFixtures.EZE);

            assertThat(store.get(CacheKeys.CITY_PREFIX + "EZE"))
                    .hasValueSatisfying(value -> assertThat(value).matches("(true|false)@\\d+"));
        }

        @Test
        @DisplayName("con el cache caído sigue respondiendo, yendo al origen cada vez")
        void degradesToOriginWhenTheCacheIsDown() {
            FailingCacheStore broken = new FailingCacheStore();
            CachingAirportCatalog degraded = new CachingAirportCatalog(delegate, broken, TTL, clock);
            delegate.answer(TestFixtures.EZE, CityResolution.present());

            assertThat(degraded.unknown(List.of(TestFixtures.EZE))).isEmpty();
            assertThat(degraded.unknown(List.of(TestFixtures.EZE))).isEmpty();

            assertThat(delegate.callsFor("EZE")).isEqualTo(2);
        }

        @Test
        @DisplayName("invalidate borra la clave y fuerza una nueva consulta")
        void invalidateClearsTheKey() {
            delegate.answer(TestFixtures.EZE, CityResolution.present());
            unknown(TestFixtures.EZE);

            catalog.invalidate(TestFixtures.EZE);

            assertThat(store.get(CacheKeys.CITY_PREFIX + "EZE")).isEmpty();
            unknown(TestFixtures.EZE);
            assertThat(delegate.callsFor("EZE")).isEqualTo(2);
        }

        @Test
        @DisplayName("una lista vacía no toca ni el cache ni el origen")
        void handlesEmptyInput() {
            assertThat(catalog.unknown(List.of())).isEmpty();
            assertThat(delegate.invocations()).isZero();
        }
    }

    // =================================================================
    // Fallback
    // =================================================================

    @Nested
    @DisplayName("Fallback")
    class Fallback {

        @Test
        @DisplayName("hallazgo 1: con el origen marcado como caído no se baja por la cadena")
        void doesNotCallTheOriginWhenItIsKnownToBeDown() {
            delegate.answer(TestFixtures.EZE, CityResolution.present());
            unknown(TestFixtures.EZE);
            clock.advance(POSITIVE_TTL.plusMinutes(1));

            CachingAirportCatalog withOpenCircuit = new CachingAirportCatalog(
                    delegate, store, TTL, clock, new DegradationRecorder(registry), () -> false);

            int before = delegate.invocations();
            assertThat(withOpenCircuit.unknown(List.of(TestFixtures.EZE)))
                    .as("se sirve el último valor conocido")
                    .isEmpty();

            assertThat(delegate.invocations())
                    .as("el fallback dejó de ser el camino lento: ni una llamada al origen")
                    .isEqualTo(before);
            assertThat(degradedCount("circuit_open")).isEqualTo(1);
        }

        @Test
        @DisplayName("si el catálogo se cae, sirve el último valor conocido aunque esté vencido")
        void servesStaleValueWhenOriginFails() {
            delegate.answer(TestFixtures.EZE, CityResolution.present());
            assertThat(unknown(TestFixtures.EZE)).isEmpty();

            clock.advance(POSITIVE_TTL.plusMinutes(1));
            delegate.answer(TestFixtures.EZE, CityResolution.unavailable("retries_exhausted"));

            assertThat(unknown(TestFixtures.EZE))
                    .as("valor vencido servido porque el origen no respondió")
                    .isEmpty();
        }

        @Test
        @DisplayName("pasada la ventana de gracia ya no hay nada viejo que servir, y el fallo sube")
        void stopsServingStaleAfterTheGraceWindow() {
            delegate.answer(TestFixtures.EZE, CityResolution.present());
            unknown(TestFixtures.EZE);

            clock.advance(POSITIVE_TTL.plus(STALE_WINDOW));
            delegate.answer(TestFixtures.EZE, CityResolution.unavailable("retries_exhausted"));

            assertThatThrownBy(() -> unknown(TestFixtures.EZE)).isInstanceOf(AirportCatalogUnavailableException.class);
        }

        @Test
        @DisplayName("sin nada guardado, el fallo del catálogo sube: devolver false rechazaría reservas válidas")
        void propagatesFailureOnColdCache() {
            delegate.answer(TestFixtures.EZE, CityResolution.unavailable("retries_exhausted"));

            assertThatThrownBy(() -> unknown(TestFixtures.EZE))
                    .isInstanceOf(AirportCatalogUnavailableException.class)
                    .hasMessageContaining("EZE");

            assertThat(registry.find(DegradationRecorder.EXHAUSTED).counter()).isNotNull();
            assertThat(registry.find(DegradationRecorder.EXHAUSTED).counter().count())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("no guarda las excepciones: una caída del catálogo no envenena el cache")
        void doesNotCacheFailures() {
            delegate.answer(TestFixtures.EZE, CityResolution.unavailable("retries_exhausted"));
            assertThatThrownBy(() -> unknown(TestFixtures.EZE)).isInstanceOf(AirportCatalogUnavailableException.class);

            delegate.answer(TestFixtures.EZE, CityResolution.present());
            assertThat(unknown(TestFixtures.EZE)).isEmpty();
        }

        @Test
        @DisplayName("hallazgo 5: un negativo vencido NO se sirve; sale 503 y no un 400 que miente")
        void neverServesAStaleNegative() {
            delegate.answer(TestFixtures.EZE, CityResolution.absent());
            assertThat(unknown(TestFixtures.EZE)).containsExactly(TestFixtures.EZE);

            // Pasa el TTL del negativo pero seguimos MUY dentro de la ventana
            // de gracia: con la política anterior, acá se servía el "no existe"
            // viejo y el usuario recibía un 400 UNKNOWN_AIRPORT sobre un
            // aeropuerto que sí existe, que no es reintentable.
            clock.advance(NEGATIVE_TTL.plusMinutes(10));
            delegate.answer(TestFixtures.EZE, CityResolution.unavailable("retries_exhausted"));

            assertThatThrownBy(() -> unknown(TestFixtures.EZE))
                    .as("503 reintentable y honesto, no un 400 sobre un itinerario correcto")
                    .isInstanceOf(AirportCatalogUnavailableException.class);
        }

        @Test
        @DisplayName("hallazgo 4: un fallo permanente NO se tapa con el valor viejo")
        void doesNotHideAPermanentFailure() {
            delegate.answer(TestFixtures.EZE, CityResolution.present());
            unknown(TestFixtures.EZE);
            clock.advance(POSITIVE_TTL.plusMinutes(1));

            delegate.failWith(new AirportCatalogIntegrationException("El catálogo rechazó la consulta con 401"));

            assertThatThrownBy(() -> unknown(TestFixtures.EZE))
                    .as("una credencial vencida tiene que verse hoy, no dentro de dos horas y media")
                    .isInstanceOf(AirportCatalogIntegrationException.class);
        }

        @Test
        @DisplayName("hallazgo 13: toda respuesta degradada deja métrica, edad del dato y marca para el header")
        void everyDegradedResponseLeavesATrace() {
            delegate.answer(TestFixtures.EZE, CityResolution.present());
            unknown(TestFixtures.EZE);

            clock.advance(POSITIVE_TTL.plusMinutes(30));
            delegate.answer(TestFixtures.EZE, CityResolution.unavailable("budget_exhausted"));

            assertThat(unknown(TestFixtures.EZE)).isEmpty();

            assertThat(degradedCount("budget_exhausted"))
                    .as("la métrica lleva el motivo, no sólo la cuenta")
                    .isEqualTo(1);
            assertThat(registry.find(DegradationRecorder.STALE_AGE)
                            .timer()
                            .totalTime(java.util.concurrent.TimeUnit.MINUTES))
                    .as("y la edad del dato servido")
                    .isEqualTo(30.0);
            assertThat(Degradation.sources())
                    .as("y la marca que el filtro del borde convierte en X-Degraded")
                    .containsExactly(CachingAirportCatalog.DEPENDENCY);
        }
    }

    // =================================================================
    // Construcción
    // =================================================================

    @Test
    @DisplayName("exige delegado, almacén, TTL positivo y clock")
    void validatesConstructorArguments() {
        assertThatThrownBy(() -> new CachingAirportCatalog(null, store, TTL, clock))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CachingAirportCatalog(delegate, null, TTL, clock))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CachingAirportCatalog(delegate, store, null, clock))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CachingAirportCatalog(delegate, store, TTL, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new CachingAirportCatalog.Ttl(Duration.ZERO, NEGATIVE_TTL, STALE_WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CachingAirportCatalog.Ttl(POSITIVE_TTL, Duration.ofMinutes(-1), STALE_WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CachingAirportCatalog.Ttl(POSITIVE_TTL, NEGATIVE_TTL, Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =================================================================
    // Apoyo
    // =================================================================

    private Set<AirportCode> unknown(AirportCode code) {
        return catalog.unknown(List.of(code));
    }

    private double degradedCount(String reason) {
        var counter = registry.find(DegradationRecorder.SERVED)
                .tags(Tags.of("dependency", CachingAirportCatalog.DEPENDENCY, "reason", reason))
                .counter();
        return counter == null ? 0 : counter.count();
    }

    /**
     * Resolutor de mentira que cuenta por código. Reemplaza al mock de
     * Mockito: con la firma en bloque, lo que hay que verificar es qué códigos
     * bajaron al origen y cuántas veces, y llevar la cuenta a mano es más
     * legible que un {@code ArgumentCaptor} por aserción.
     */
    private static final class RecordingResolver implements CityResolver {

        private final Map<String, CityResolution> answers = new LinkedHashMap<>();
        private final Map<String, AtomicInteger> calls = new LinkedHashMap<>();
        private final List<String> seen = new ArrayList<>();
        private RuntimeException failure;
        private int invocations;

        void answer(AirportCode code, CityResolution resolution) {
            answers.put(code.value(), resolution);
        }

        void failWith(RuntimeException error) {
            this.failure = error;
        }

        int callsFor(String code) {
            AtomicInteger counter = calls.get(code);
            return counter == null ? 0 : counter.get();
        }

        int invocations() {
            return invocations;
        }

        @Override
        public Map<String, CityResolution> resolve(Collection<String> codes) {
            invocations++;
            if (failure != null) {
                throw failure;
            }
            Map<String, CityResolution> resolutions = new LinkedHashMap<>();
            for (String code : codes) {
                seen.add(code);
                calls.computeIfAbsent(code, ignored -> new AtomicInteger()).incrementAndGet();
                resolutions.put(code, answers.getOrDefault(code, CityResolution.absent()));
            }
            return resolutions;
        }
    }
}
