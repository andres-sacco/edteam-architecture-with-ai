package com.edteam.reservations.infrastructure.adapter.in.rest;

import com.edteam.reservations.domain.model.ReservationId;
import com.edteam.reservations.infrastructure.cache.CacheKeys;
import com.edteam.reservations.infrastructure.cache.FailingCacheStore;
import com.edteam.reservations.infrastructure.cache.InMemoryCacheStore;
import com.edteam.reservations.support.MutableClock;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReservationVersionCache")
class ReservationVersionCacheTest {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final ReservationId ID = ReservationId.of(1042L);

    private MutableClock clock;
    private InMemoryCacheStore store;
    private ReservationVersionCache cache;

    @BeforeEach
    void setUp() {
        clock = MutableClock.at(TestFixtures.NOW);
        store = new InMemoryCacheStore(clock, 1_000);
        cache = new ReservationVersionCache(store, TTL);
    }

    @Test
    @DisplayName("devuelve la versión recordada")
    void remembersVersion() {
        cache.remember(ID, 7L);

        assertThat(cache.find(ID)).hasValue(7L);
    }

    @Test
    @DisplayName("una reserva que nunca se leyó es un miss")
    void missesUnknownReservation() {
        assertThat(cache.find(ID)).isEmpty();
    }

    @Test
    @DisplayName("cada reserva tiene su clave")
    void separatesReservations() {
        cache.remember(ID, 7L);
        cache.remember(ReservationId.of(9L), 2L);

        assertThat(cache.find(ID)).hasValue(7L);
        assertThat(cache.find(ReservationId.of(9L))).hasValue(2L);
    }

    @Test
    @DisplayName("forget borra la clave: es lo que hace toda escritura después del commit")
    void forgetRemovesTheKey() {
        cache.remember(ID, 7L);

        cache.forget(ID);

        assertThat(store.get(CacheKeys.RESERVATION_VERSION_PREFIX + "1042")).isEmpty();
        assertThat(cache.find(ID)).isEmpty();
    }

    @Test
    @DisplayName("el TTL es la red de seguridad si se pierde una invalidación")
    void expiresAfterTtl() {
        cache.remember(ID, 7L);

        clock.advance(TTL.minusSeconds(1));
        assertThat(cache.find(ID)).hasValue(7L);

        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.find(ID))
                .as("una invalidación perdida deja de hacer daño al vencer el TTL")
                .isEmpty();
    }

    @Test
    @DisplayName("lo guardado es un entero: no hay nada sensible que proteger")
    void storesOnlyTheVersion() {
        cache.remember(ID, 7L);

        assertThat(store.get(CacheKeys.RESERVATION_VERSION_PREFIX + "1042")).contains("7");
    }

    @Test
    @DisplayName("un valor ilegible es un miss")
    void treatsCorruptValueAsMiss() {
        store.put(CacheKeys.RESERVATION_VERSION_PREFIX + "1042", "siete", TTL);

        assertThat(cache.find(ID)).isEmpty();
    }

    @Test
    @DisplayName("con el cache caído todo es un miss y nadie se entera")
    void degradesWhenTheCacheIsDown() {
        ReservationVersionCache degraded = new ReservationVersionCache(new FailingCacheStore(), TTL);

        degraded.remember(ID, 7L);
        degraded.forget(ID);

        assertThat(degraded.find(ID)).isEmpty();
    }

    @Test
    @DisplayName("exige almacén y TTL positivo")
    void validatesConstructorArguments() {
        assertThatThrownBy(() -> new ReservationVersionCache(null, TTL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReservationVersionCache(store, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReservationVersionCache(store, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
