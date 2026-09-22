package com.edteam.reservations.infrastructure.adapter.out.airport;

import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.domain.model.AirportCode;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("CachingAirportCatalog")
class CachingAirportCatalogTest {

    private static final Duration TTL = Duration.ofMinutes(30);

    @Mock
    private AirportCatalogPort delegate;

    private MutableClock clock;
    private CachingAirportCatalog catalog;

    @BeforeEach
    void setUp() {
        clock = MutableClock.at(TestFixtures.NOW);
        catalog = new CachingAirportCatalog(delegate, TTL, clock);
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
        clock.advance(TTL.minusSeconds(1));
        assertThat(catalog.exists(TestFixtures.EZE)).isTrue();
        verify(delegate, times(1)).exists(TestFixtures.EZE);

        clock.advance(Duration.ofSeconds(1));
        assertThat(catalog.exists(TestFixtures.EZE)).isFalse();
        verify(delegate, times(2)).exists(TestFixtures.EZE);
    }

    @Test
    @DisplayName("invalidateAll fuerza una nueva consulta al origen")
    void invalidateAllClearsCache() {
        when(delegate.exists(TestFixtures.EZE)).thenReturn(true);

        assertThat(catalog.exists(TestFixtures.EZE)).isTrue();
        catalog.invalidateAll();
        assertThat(catalog.exists(TestFixtures.EZE)).isTrue();

        verify(delegate, times(2)).exists(TestFixtures.EZE);
    }

    @Test
    @DisplayName("tolera un código nulo sin ir al origen")
    void handlesNullCode() {
        assertThat(catalog.exists(null)).isFalse();

        verifyNoInteractions(delegate);
    }

    @Test
    @DisplayName("exige delegado, TTL positivo y clock")
    void validatesConstructorArguments() {
        assertThatThrownBy(() -> new CachingAirportCatalog(null, TTL, clock))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CachingAirportCatalog(delegate, null, clock))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CachingAirportCatalog(delegate, TTL, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CachingAirportCatalog(delegate, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CachingAirportCatalog(delegate, Duration.ofMinutes(-1), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("el decorador no cambia el resultado del origen")
    void delegatesResult() {
        when(delegate.exists(AirportCode.of("ZZZ"))).thenReturn(false);

        assertThat(catalog.exists(AirportCode.of("ZZZ"))).isEqualTo(delegate.exists(AirportCode.of("ZZZ")));
    }
}
