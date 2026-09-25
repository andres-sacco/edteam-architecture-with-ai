package com.edteam.reservations.infrastructure.config;

import com.edteam.reservations.infrastructure.adapter.out.airport.CachingAirportCatalog;
import com.edteam.reservations.infrastructure.adapter.out.airport.catalog.RetryingCityCatalogClient;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Todo lo configurable del maestro de aeropuertos: plazos del cache, timeouts
 * del proveedor, reintentos, bulkhead, circuito y el presupuesto del
 * itinerario.
 *
 * <p>Cada valor por defecto está acá y su motivo en {@code application.yml}.
 * Ninguno es el default de la librería: el tráfico, el costo de la llamada y
 * el valor del fallback son distintos para cada dependencia, y eso es
 * justamente lo que fija los umbrales.
 */
@ConfigurationProperties(prefix = "reservations.airport-catalog")
public record AirportCatalogProperties(
        Duration cacheTtl,
        Duration negativeCacheTtl,
        Duration staleWhileError,
        Duration connectTimeout,
        Duration readTimeout,
        Duration itineraryBudget,
        RetryProperties retry,
        BulkheadProperties bulkhead,
        CircuitBreakerProperties circuitBreaker,
        String baseUrl,
        String apiKey,
        String apiKeyHeader) {

    /**
     * Umbrales por defecto del circuito del catálogo.
     *
     * <p>Ventana 50 / mínimo 20: un itinerario típico son cuatro ciudades y
     * uno complejo once, así que 20 llamadas son unos pocos itinerarios —
     * suficiente para que una ráfaga desafortunada de un solo pedido no abra
     * el circuito, y poco como para detectar una caída en segundos.
     *
     * <p>Abierto 5 s, que es el número que más se aparta del default de 60 s
     * de la librería: el fallback sirve datos que envejecen, así que cada
     * segundo abierto cuesta frescura, y probar es barato (4 llamadas de ≤ 1 s).
     */
    private static final CircuitBreakerProperties CIRCUIT_DEFAULTS = new CircuitBreakerProperties(
            true, 50, 20, 50, Duration.ofMillis(900), 60, Duration.ofSeconds(5), 4, true, Duration.ofMinutes(10));

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
            connectTimeout = Duration.ofMillis(300);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofMillis(700);
        }
        if (itineraryBudget == null || itineraryBudget.isNegative() || itineraryBudget.isZero()) {
            itineraryBudget = Duration.ofMillis(1600);
        }
        if (retry == null) {
            retry = new RetryProperties(null, null, null);
        }
        if (bulkhead == null) {
            bulkhead = new BulkheadProperties(null);
        }
        circuitBreaker = CircuitBreakerProperties.merge(circuitBreaker, CIRCUIT_DEFAULTS);
        if (apiKeyHeader == null || apiKeyHeader.isBlank()) {
            apiKeyHeader = "X-API-Key";
        }
    }

    /**
     * Reintentos de {@code GET /city/{code}}, la única llamada del sistema que
     * se reintenta y sólo porque es una lectura idempotente.
     */
    public record RetryProperties(Integer maxAttempts, Duration initialBackoff, Duration maxBackoff) {

        public RetryProperties {
            if (maxAttempts == null || maxAttempts < 1) {
                maxAttempts = 2;
            }
            if (initialBackoff == null) {
                initialBackoff = Duration.ofMillis(100);
            }
            if (maxBackoff == null) {
                maxBackoff = Duration.ofMillis(200);
            }
        }
    }

    /** Llamadas simultáneas contra el proveedor, con espera cero. */
    public record BulkheadProperties(Integer maxConcurrentCalls) {

        public BulkheadProperties {
            if (maxConcurrentCalls == null || maxConcurrentCalls < 1) {
                maxConcurrentCalls = 50;
            }
        }
    }

    public CachingAirportCatalog.Ttl cacheTtlPolicy() {
        return new CachingAirportCatalog.Ttl(cacheTtl, negativeCacheTtl, staleWhileError);
    }

    public RetryingCityCatalogClient.Retry retryPolicy() {
        return new RetryingCityCatalogClient.Retry(retry.maxAttempts(), retry.initialBackoff(), retry.maxBackoff());
    }

    /**
     * Techo de un intento contra el proveedor: la conexión más la lectura.
     *
     * <p>Es el número con el que el retry decide si arrancar otro intento
     * entra en lo que queda del presupuesto. Que se derive de los timeouts y
     * no sea una constante es lo que impide que los dos se desincronicen — el
     * javadoc anterior declaraba un peor caso que omitía el connect timeout y
     * quedaba un 17 % corto.
     */
    public Duration attemptCost() {
        return connectTimeout.plus(readTimeout);
    }

    /**
     * Peor caso de resolver <strong>una</strong> ciudad contra el origen, con
     * todos los intentos y todas las esperas en su techo. Derivado, no
     * escrito: un test lo compara con el presupuesto y falla el día que
     * alguien cambie un timeout sin rehacer la cuenta.
     */
    public Duration worstCasePerCity() {
        return attemptCost()
                .multipliedBy(retry.maxAttempts())
                .plus(retryPolicy().worstCaseBackoff());
    }

    /**
     * Peor caso de validar un itinerario completo. Es el presupuesto, no la
     * suma: con el fan-out en paralelo, N ciudades cuestan la más lenta, y el
     * corte duro lo pone el presupuesto — que siempre es menor o igual que el
     * peor caso de una ciudad sola.
     */
    public Duration worstCaseItinerary() {
        Duration perCity = worstCasePerCity();
        return itineraryBudget.compareTo(perCity) < 0 ? itineraryBudget : perCity;
    }

    public boolean hasRemoteCatalog() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    /**
     * La API key viaja en un header: sin TLS se lee en el camino. Se permite
     * {@code http://} sólo contra localhost, que es el contenedor de al lado
     * en la máquina de desarrollo.
     */
    public boolean usesSecureTransport() {
        return !hasRemoteCatalog()
                || baseUrl.startsWith("https://")
                || baseUrl.startsWith("http://localhost")
                || baseUrl.startsWith("http://127.0.0.1");
    }

    boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
