package com.edteam.reservations.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Parámetros del maestro de aeropuertos.
 *
 * <p>La URL base es la que decide qué implementación se cablea: sin ella, el
 * maestro sigue siendo el stub en memoria; con ella, se usa el cliente REST
 * contra la API de catálogo. Así el entorno elige el origen sin recompilar y
 * los tests no dependen de que haya un proveedor arriba.
 *
 * @param cacheTtl     tiempo de vida de las entradas de cache
 * @param baseUrl      raíz de la API de catálogo; vacío para usar el stub en memoria
 * @param apiKey       credencial que se manda en cada llamada; vacío para no enviar header
 * @param apiKeyHeader header en el que viaja la credencial
 */
@ConfigurationProperties(prefix = "reservations.airport-catalog")
public record AirportCatalogProperties(Duration cacheTtl, String baseUrl, String apiKey, String apiKeyHeader) {

    public AirportCatalogProperties {
        if (cacheTtl == null) {
            cacheTtl = Duration.ofMinutes(30);
        }
        if (apiKeyHeader == null || apiKeyHeader.isBlank()) {
            apiKeyHeader = "X-API-Key";
        }
    }

    /** {@code true} si hay un proveedor configurado al que llamar. */
    public boolean hasRemoteCatalog() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
