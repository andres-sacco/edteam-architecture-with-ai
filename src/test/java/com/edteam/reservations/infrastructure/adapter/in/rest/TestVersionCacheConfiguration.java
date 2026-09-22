package com.edteam.reservations.infrastructure.adapter.in.rest;

import com.edteam.reservations.infrastructure.cache.InMemoryCacheStore;
import com.edteam.reservations.support.MutableClock;
import com.edteam.reservations.support.TestFixtures;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Cache de versiones real para el slice web.
 *
 * <p>Va real y no mockeado porque lo que hay que verificar es el
 * comportamiento de la petición condicional de punta a punta —que el hit no
 * llegue al caso de uso, y que una escritura no deje servir un {@code 304} con
 * la versión vieja—, y eso con un mock se estaría afirmando de mentira.
 */
@TestConfiguration
public class TestVersionCacheConfiguration {

    @Bean
    ReservationVersionCache reservationVersionCache() {
        return new ReservationVersionCache(
                new InMemoryCacheStore(MutableClock.at(TestFixtures.NOW), 100), Duration.ofSeconds(60));
    }
}
