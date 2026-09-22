package com.edteam.reservations.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Parámetros del maestro de aeropuertos.
 *
 * @param cacheTtl tiempo de vida de las entradas de cache
 */
@ConfigurationProperties(prefix = "reservations.airport-catalog")
public record AirportCatalogProperties(Duration cacheTtl) {

    public AirportCatalogProperties {
        if (cacheTtl == null) {
            cacheTtl = Duration.ofMinutes(30);
        }
    }
}
