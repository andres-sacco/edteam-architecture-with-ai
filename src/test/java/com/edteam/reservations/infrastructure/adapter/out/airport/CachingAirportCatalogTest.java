package com.edteam.reservations.infrastructure.adapter.out.airport;

import com.edteam.reservations.application.exception.AirportCatalogUnavailableException;
import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.domain.model.AirportCode;
import com.edteam.reservations.infrastructure.cache.CacheKeys;
import com.edteam.reservations.infrastructure.cache.CacheStore;
import com.edteam.reservations.infrastructure.cache.FailingCacheStore;
import com.edteam.reservations.infrastructure.cache.InMemoryCacheStore;
import com.edteam.reservations.support.MutableClock;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * El decorador ya no arma su propio mapa: recibe un {@link CacheStore}. Por eso
 * estos tests se escriben contra {@link InMemoryCacheStore} —el mismo almacén
 * que usa la aplicación cuando no hay Redis— en lugar de contra un campo
 * privado. Lo que se verifica no cambió: que el hit no vaya al origen, que el
 * TTL expire y que la invalidación borre.
 *
 * <p>Lo que sí es nuevo es el TTL diferenciado para los negativos y el
 * {@code stale-while-error}, que son las dos decisiones que justifican haber
 * tocado esta clase.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CachingAirportCatalog")
class CachingAirportCatalogTest {

    private static final Duration POSITIVE_TTL = Duration.ofMinutes(30);
    private static final Duration NEGATIVE_TTL = Duration.ofMinutes(5);
    private static final Duration STALE_WINDOW = Duration.ofHours(2);
    private static final CachingAirportCatalog.Ttl TTL =
            new CachingAirportCatalog.Ttl(POSITIVE_TTL, NEGATIVE_TTL, STALE_WINDOW);

    @Mock
    private AirportCatalogPort delegate;

    private MutableClock clock;
    private InMemoryCacheStore store;
    private CachingAirportCatalog catalog;

    @BeforeEach
    void setUp() {
        clock = MutableClock.at(TestFixtures.NOW);
        store = new InMemoryCacheStore(clock, 1_000);
        catalog = new CachingAirportCatalog(delegate, store, TTL, clock);
    }

    @Test
    @DisplayName("consulta el origen una sola vez para el mismo código")
    void cachesPositiveResult() {
        when(delegate.exists(TestFixtures.EZE)).thenReturn(true);

        assertThat(catalog.exists(TestFixtures.EZE)).isTrue();
        assertThat(catalog.exists(TestFixtures.EZE)).isTrue();
        assertThat(catalog.exists(TestFixtures.EZE)).isTrue();

        verify(delegate, times(1)).exists(TestFixtures.EZE);
    }

    @Test
    @DisplayName("también cachea las respuestas negativas")
    void cachesNegativeResult() {
        when(delegate.exists(TestFixtures.EZE)).thenReturn(false);

        assertThat(catalog.exists(TestFixtures.EZE)).isFalse();
        assertThat(catalog.exists(TestFixtures.EZE)).isFalse();

        verify(delegate, times(1)).exists(TestFixtures.EZE);
    }

    @Test
    @DisplayName("cachea cada código por separado")
    void cachesPerCode() {
        when(delegate.exists(TestFixtures.EZE)).thenReturn(true);
        when(delegate.exists(TestFixtures.SCL)).thenReturn(false);

        assertThat(catalog.exists(TestFixtures.EZE)).isTrue();
        assertThat(catalog.exists(TestFixtures.SCL)).isFalse();
        assertThat(catalog.exists(TestFixtures.EZE)).isTrue();

        verify(delegate, times(1)).exists(TestFixtures.EZE);
        verify(delegate, times(1)).exists(TestFixtures.SCL);
    }

    @Test
    @DisplayName("vuelve a consultar el origen cuando vence el TTL")
    void refreshesAfterTtl() {
        when(delegate.exists(TestFixtures.EZE)).thenReturn(true, false);

        assertThat(catalog.exists(TestFixtures.EZE)).isTrue();
        clock.advance(POSITIVE_TTL.minusSeconds(1));
        assertThat(catalog.exists(TestFixtures.EZE)).isTrue();
        verify(delegate, times(1)).exists(TestFixtures.EZE);

        clock.advance(Duration.ofSeconds(1));
        assertThat(catalog.exists(TestFixtures.EZE)).isFalse();
        verify(delegate, times(2)).exists(TestFixtures.EZE);
    }

    @Test
    @DisplayName("el negativo vence antes que el positivo: un alta nueva no queda rechazada media hora")
    void negativeResultsExpireSooner() {
        when(delegate.exists(TestFixtures.EZE)).thenReturn(false, true);

        assertThat(catalog.exists(TestFixtures.EZE)).isFalse();

        clock.advance(NEGATIVE_TTL.minusSeconds(1));
        assertThat(catalog.exists(TestFixtures.EZE)).isFalse();
        verify(delegate, times(1)).exists(TestFixtures.EZE);

        clock.advance(Duration.ofSeconds(1));
        assertThat(catalog.exists(TestFixtures.EZE)).isTrue();
        verify(delegate, times(2)).exists(TestFixtures.EZE);
    }

    @Test
    @DisplayName("si el catálogo se cae, sirve el último valor conocido aunque esté vencido")
    void servesStaleValueWhenOriginFails() {
        when(delegate.exists(TestFixtures.EZE))
                .thenReturn(true)
                .thenThrow(new AirportCatalogUnavailableException("El catálogo respondió 503"));

        assertThat(catalog.exists(TestFixtures.EZE)).isTrue();

        clock.advance(POSITIVE_TTL.plusMinutes(1));
        assertThat(catalog.exists(TestFixtures.EZE))
                .as("valor vencido servido porque el origen no respondió")
                .isTrue();
        verify(delegate, times(2)).exists(TestFixtures.EZE);
    }

    @Test
    @DisplayName("pasada la ventana de gracia ya no hay nada viejo que servir, y el fallo sube")
    void stopsServingStaleAfterTheGraceWindow() {
        when(delegate.exists(TestFixtures.EZE))
                .thenReturn(true)
                .thenThrow(new AirportCatalogUnavailableException("El catálogo respondió 503"));

        assertThat(catalog.exists(TestFixtures.EZE)).isTrue();
        clock.advance(POSITIVE_TTL.plus(STALE_WINDOW));

        assertThatThrownBy(() -> catalog.exists(TestFixtures.EZE))
                .isInstanceOf(AirportCatalogUnavailableException.class);
    }

    @Test
    @DisplayName("sin nada guardado, el fallo del catálogo sube: devolver false rechazaría reservas válidas")
    void propagatesFailureOnColdCache() {
        when(delegate.exists(TestFixtures.EZE))
                .thenThrow(new AirportCatalogUnavailableException("El catálogo respondió 503"));

        assertThatThrownBy(() -> catalog.exists(TestFixtures.EZE))
                .isInstanceOf(AirportCatalogUnavailableException.class);
    }

    @Test
    @DisplayName("no guarda las excepciones: una caída del catálogo no envenena el cache")
    void doesNotCacheFailures() {
        when(delegate.exists(TestFixtures.EZE))
                .thenThrow(new AirportCatalogUnavailableException("El catálogo respondió 503"))
                .thenReturn(true);

        assertThatThrownBy(() -> catalog.exists(TestFixtures.EZE))
                .isInstanceOf(AirportCatalogUnavailableException.class);

        assertThat(catalog.exists(TestFixtures.EZE)).isTrue();
    }

    @Test
    @DisplayName("invalidate borra la clave y fuerza una nueva consulta")
    void invalidateClearsTheKey() {
        when(delegate.exists(TestFixtures.EZE)).thenReturn(true);

        assertThat(catalog.exists(TestFixtures.EZE)).isTrue();
        catalog.invalidate(TestFixtures.EZE);

        assertThat(store.get(CacheKeys.CITY_PREFIX + TestFixtures.EZE.value())).isEmpty();
        assertThat(catalog.exists(TestFixtures.EZE)).isTrue();
        verify(delegate, times(2)).exists(TestFixtures.EZE);
    }

    @Test
    @DisplayName("con el cache caído sigue respondiendo, yendo al origen cada vez")
    void degradesToOriginWhenTheCacheIsDown() {
        FailingCacheStore broken = new FailingCacheStore();
        CachingAirportCatalog degraded = new CachingAirportCatalog(delegate, broken, TTL, clock);
        when(delegate.exists(TestFixtures.EZE)).thenReturn(true);

        assertThat(degraded.exists(TestFixtures.EZE)).isTrue();
        assertThat(degraded.exists(TestFixtures.EZE)).isTrue();

        verify(delegate, times(2)).exists(TestFixtures.EZE);
        assertThat(broken.gets()).isEqualTo(2);
    }

    @Test
    @DisplayName("en el cache sólo hay un booleano y un instante: nada sensible")
    void storesNothingSensitive() {
        when(delegate.exists(TestFixtures.EZE)).thenReturn(true);

        catalog.exists(TestFixtures.EZE);

        assertThat(store.get(CacheKeys.CITY_PREFIX + "EZE"))
                .hasValueSatisfying(value -> assertThat(value).matches("(true|false)@\\d+"));
    }

    @Test
    @DisplayName("tolera un código nulo sin ir al origen")
    void handlesNullCode() {
        assertThat(catalog.exists(null)).isFalse();

        verifyNoInteractions(delegate);
    }

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

    @Test
    @DisplayName("el decorador no cambia el resultado del origen")
    void delegatesResult() {
        when(delegate.exists(AirportCode.of("ZZZ"))).thenReturn(false);

        assertThat(catalog.exists(AirportCode.of("ZZZ"))).isEqualTo(delegate.exists(AirportCode.of("ZZZ")));
    }
}
