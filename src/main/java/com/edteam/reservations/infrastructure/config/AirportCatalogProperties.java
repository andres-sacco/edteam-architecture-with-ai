package com.edteam.reservations.infrastructure.config;

import com.edteam.reservations.infrastructure.adapter.out.airport.CachingAirportCatalog;
import com.edteam.reservations.infrastructure.adapter.out.airport.catalog.RetryingCityCatalogClient;
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
 * <p>Los tres tiempos del cache son distintos a propósito, y el porqué de cada
 * uno está en {@link CachingAirportCatalog}: el negativo es más corto porque
 * servir un "no existe" viejo es rechazar una reserva válida, y la ventana de
 * gracia es larga porque su razón de ser es cubrir una caída del proveedor,
 * que puede durar bastante más que un TTL.
 *
 * <h2>Timeouts y reintentos</h2>
 * Los timeouts van acá y no en {@code spring.http.client} porque son de
 * <em>este</em> proveedor: el read timeout que tolera el catálogo no tiene por
 * qué ser el que tolere el próximo servicio que se integre, y un valor global
 * los ataría. Los reintentos siguen la misma lógica y sólo aplican a
 * {@code GET /city/{code}}, que es una lectura idempotente; el detalle de por
 * qué se puede reintentar esto y no una escritura está en
 * {@link RetryingCityCatalogClient}.
 *
 * @param cacheTtl         vigencia de un "existe"
 * @param negativeCacheTtl vigencia de un "no existe"; más corto a propósito
 * @param staleWhileError  cuánto se conserva una entrada vencida para poder
 *                         servirla si el catálogo no responde
 * @param connectTimeout   tope para establecer la conexión
 * @param readTimeout      tope para que el catálogo termine de responder
 * @param retry            política de reintentos ante fallos transitorios
 * @param baseUrl          raíz de la API de catálogo; vacío para usar el stub en memoria
 * @param apiKey           credencial que se manda en cada llamada; vacío para no enviar header
 * @param apiKeyHeader     header en el que viaja la credencial
 */
@ConfigurationProperties(prefix = "reservations.airport-catalog")
public record AirportCatalogProperties(Duration cacheTtl,
                                       Duration negativeCacheTtl,
                                       Duration staleWhileError,
                                       Duration connectTimeout,
                                       Duration readTimeout,
                                       RetryProperties retry,
                                       String baseUrl,
                                       String apiKey,
                                       String apiKeyHeader) {

    public AirportCatalogProperties {
        if (cacheTtl == null) {
            cacheTtl = Duration.ofMinutes(30);
        }
        if (negativeCacheTtl == null) {
            negativeCacheTtl = Duration.ofMinutes(5);
        }
        if (staleWhileError == null) {
            staleWhileError = Duration.ofHours(2);
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofMillis(500);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(2);
        }
        if (retry == null) {
            retry = new RetryProperties(null, null, null);
        }
        if (apiKeyHeader == null || apiKeyHeader.isBlank()) {
            apiKeyHeader = "X-API-Key";
        }
    }

    /**
     * Reintentos ante fallos transitorios del catálogo.
     *
     * @param maxAttempts    intentos totales, el primero incluido; 1 los apaga
     * @param initialBackoff espera después del primer fallo
     * @param maxBackoff     techo de la espera
     */
    public record RetryProperties(Integer maxAttempts, Duration initialBackoff, Duration maxBackoff) {

        public RetryProperties {
            if (maxAttempts == null || maxAttempts < 1) {
                maxAttempts = 3;
            }
            if (initialBackoff == null) {
                initialBackoff = Duration.ofMillis(100);
            }
            if (maxBackoff == null) {
                maxBackoff = Duration.ofMillis(500);
            }
        }
    }

    /** Política de vencimiento que espera el decorador con cache. */
    public CachingAirportCatalog.Ttl cacheTtlPolicy() {
        return new CachingAirportCatalog.Ttl(cacheTtl, negativeCacheTtl, staleWhileError);
    }

    /** Política de reintentos que espera el decorador que reintenta. */
    public RetryingCityCatalogClient.Retry retryPolicy() {
        return new RetryingCityCatalogClient.Retry(
                retry.maxAttempts(), retry.initialBackoff(), retry.maxBackoff());
    }

    /** {@code true} si hay un proveedor configurado al que llamar. */
    public boolean hasRemoteCatalog() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
