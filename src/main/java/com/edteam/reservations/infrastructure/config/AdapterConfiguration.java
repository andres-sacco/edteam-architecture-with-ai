package com.edteam.reservations.infrastructure.config;

import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.infrastructure.adapter.out.airport.BudgetedCityCatalogFanout;
import com.edteam.reservations.infrastructure.adapter.out.airport.CachingAirportCatalog;
import com.edteam.reservations.infrastructure.adapter.out.airport.CityResolver;
import com.edteam.reservations.infrastructure.adapter.out.airport.StaticAirportCatalog;
import com.edteam.reservations.infrastructure.adapter.out.airport.catalog.BulkheadCityCatalogClient;
import com.edteam.reservations.infrastructure.adapter.out.airport.catalog.CatalogCityResolver;
import com.edteam.reservations.infrastructure.adapter.out.airport.catalog.CircuitBreakingCityCatalogClient;
import com.edteam.reservations.infrastructure.adapter.out.airport.catalog.CityCatalogClient;
import com.edteam.reservations.infrastructure.adapter.out.airport.catalog.RestCityCatalogClient;
import com.edteam.reservations.infrastructure.adapter.out.airport.catalog.RetryingCityCatalogClient;
import com.edteam.reservations.infrastructure.adapter.out.messaging.DomainEventPayloadMapper;
import com.edteam.reservations.infrastructure.adapter.out.outbox.JdbcEventOutbox;
import com.edteam.reservations.infrastructure.adapter.out.outbox.MeteredEventOutbox;
import com.edteam.reservations.infrastructure.cache.CacheStore;
import com.edteam.reservations.infrastructure.logging.CorrelationIdPropagation;
import com.edteam.reservations.infrastructure.logging.LogFields;
import com.edteam.reservations.infrastructure.logging.LogSanitizer;
import com.edteam.reservations.infrastructure.resilience.Circuit;
import com.edteam.reservations.infrastructure.resilience.DegradationRecorder;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * Cableado de los adaptadores de salida.
 *
 * <p>Acá está escrito, y explícito, el <strong>orden de los decoradores</strong>
 * del catálogo. No es el orden en que Spring encuentra los beans ni el que
 * trae por defecto la librería: cada frontera está elegida y justificada en el
 * javadoc de la clase que la ocupa.
 */
@Configuration
public class AdapterConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AdapterConfiguration.class);

    /**
     * El reloj del sistema. Un bean y no {@code Instant.now()} disperso: es lo
     * que hace testeable todo lo que depende del tiempo, que en resiliencia es
     * casi todo.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    // -----------------------------------------------------------------
    // Maestro de aeropuertos
    // -----------------------------------------------------------------

    /**
     * La cadena completa, de afuera hacia adentro:
     *
     * <pre>
     * AirportExistenceValidator          (aplicación: sólo conoce el puerto)
     * └── CachingAirportCatalog          hit → 0 llamadas; stale-while-error; marca la degradación
     *     └── BudgetedCityCatalogFanout  presupuesto del itinerario + fan-out en hilos virtuales
     *         └── CatalogCityResolver    traduce el rechazo de la librería a un resultado por ciudad
     *             └── CircuitBreaking…   circuito
     *                 └── Bulkhead…      ≤ N llamadas en vuelo, sin cola de espera
     *                     └── Retrying…  2 intentos, consciente del presupuesto
     *                         └── Rest…  connect + read; clasifica la respuesta
     * </pre>
     *
     * <p>Las tres fronteras que importan y por qué están donde están:
     *
     * <ul>
     *   <li><strong>El cache va afuera de todo.</strong> Un hit no consume una
     *       llamada del circuito ni un permiso del bulkhead porque no toca la
     *       red. Con el circuito por encima, un circuito abierto dejaría sin
     *       servir datos guardados y frescos. Y el <em>stale-while-error</em>
     *       necesita estar por encima del circuito para poder reaccionar a su
     *       rechazo.</li>
     *   <li><strong>El circuito va por fuera del retry</strong>, para que la
     *       unidad que cuenta sea «resolver una ciudad» y para que un circuito
     *       abierto cortocircuite el bucle de reintentos en lugar de hacerlo
     *       girar sobre su propio rechazo.</li>
     *   <li><strong>El bulkhead va entre los dos</strong>, para que un rechazo
     *       por saturación propia no se reintente.</li>
     * </ul>
     *
     * <p>Sin {@code base-url} la cadena es sólo el cache sobre el stub en
     * memoria: no hay red que proteger, y montar circuitos sobre un
     * {@code Set} sería ceremonia.
     */
    @Bean
    public AirportCatalogPort airportCatalogPort(
            AirportCatalogProperties properties,
            @Qualifier("cityCatalogCacheStore") CacheStore cityCatalogCacheStore,
            RestClient.Builder restClientBuilder,
            Circuit catalogCircuit,
            Bulkhead catalogBulkhead,
            DegradationRecorder degradation,
            MeterRegistry registry,
            Clock clock) {
        if (!properties.hasRemoteCatalog()) {
            log.info("Maestro de aeropuertos: stub en memoria (no hay 'reservations.airport-catalog.base-url')");
            return new CachingAirportCatalog(
                    StaticAirportCatalog.withDefaults(),
                    cityCatalogCacheStore,
                    properties.cacheTtlPolicy(),
                    clock,
                    degradation,
                    () -> true);
        }

        requireSecureTransport(properties);

        CityCatalogClient http = new RestCityCatalogClient(catalogRestClient(restClientBuilder, properties), registry);
        CityCatalogClient retrying = new RetryingCityCatalogClient(
                http,
                properties.retryPolicy(),
                RetryingCityCatalogClient.Sleeper.real(),
                properties.attemptCost(),
                clock,
                registry);
        CityCatalogClient bulkheaded = new BulkheadCityCatalogClient(retrying, catalogBulkhead);
        CityCatalogClient guarded = new CircuitBreakingCityCatalogClient(bulkheaded, catalogCircuit);

        CityResolver resolver = new CatalogCityResolver(guarded, registry);
        CityResolver fanout = new BudgetedCityCatalogFanout(resolver, properties.itineraryBudget(), clock, registry);

        // La base-url va SANEADA y acotada: es configuración de despliegue y
        // puede traer credenciales en el userinfo (`https://user:clave@host`),
        // que en un log es una credencial replicada a un sistema indexado.
        log.atInfo()
                .addKeyValue(LogFields.EVENT, LogFields.STARTUP_WIRING)
                .addKeyValue("component", "airport-catalog")
                .addKeyValue("catalog.baseUrl", safeBaseUrl(properties.baseUrl()))
                .addKeyValue(
                        "catalog.connectTimeoutMs", properties.connectTimeout().toMillis())
                .addKeyValue("catalog.readTimeoutMs", properties.readTimeout().toMillis())
                .addKeyValue("catalog.maxAttempts", properties.retry().maxAttempts())
                .addKeyValue(
                        "catalog.itineraryBudgetMs",
                        properties.itineraryBudget().toMillis())
                .addKeyValue(
                        "catalog.worstCasePerCityMs",
                        properties.worstCasePerCity().toMillis())
                .log("Maestro de aeropuertos cableado contra la API de catálogo");

        // La sonda del origen: con el circuito abierto, el cache ni baja por
        // la cadena. Es la memoria de «el origen está caído» que el fallback
        // no tenía, y la que evita que cada ciudad vuelva a pagar el viaje.
        return new CachingAirportCatalog(
                fanout,
                cityCatalogCacheStore,
                properties.cacheTtlPolicy(),
                clock,
                degradation,
                () -> !catalogCircuit.isOpen());
    }

    private static void requireSecureTransport(AirportCatalogProperties properties) {
        if (!properties.usesSecureTransport()) {
            throw new IllegalStateException(("El catálogo de ciudades está configurado en '%s': la API key viaja en un "
                            + "header y sin TLS se lee en el camino. Usar https:// (o vaciar "
                            + "'base-url' para volver al stub en memoria).")
                    .formatted(properties.baseUrl()));
        }
    }

    /**
     * El {@code RestClient} del catálogo, con los timeouts de <em>este</em>
     * proveedor. Sale del {@code Builder} autoconfigurado, así que hereda los
     * interceptores de observabilidad y publica {@code http.client.requests}.
     */
    private static RestClient catalogRestClient(RestClient.Builder builder, AirportCatalogProperties properties) {
        ClientHttpRequestFactorySettings timeouts = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());

        RestClient.Builder configured = builder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(timeouts))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                // El correlation id sale del proceso. Era el hallazgo 14: la
                // traza se cortaba en el borde porque ningún interceptor
                // inyectaba el header. El `traceparent` de W3C lo pone solo la
                // instrumentación de Micrometer sobre este mismo builder, que
                // es la razón por la que el cliente se arma desde el Builder
                // autoconfigurado y no desde cero.
                //
                // Hoy el api-catalog es un contenedor de terceros que ignora
                // los dos headers, así que el costo es futuro y no actual. El
                // día que el catálogo sea nuestro, la traza se continúa sola
                // sin tocar una línea de acá.
                .requestInterceptor(CorrelationIdPropagation.interceptor());
        if (properties.hasApiKey()) {
            configured = configured.defaultHeader(properties.apiKeyHeader(), properties.apiKey());
        }
        return configured.build();
    }

    /**
     * La URL sin el {@code userinfo}, para poder escribirla en un log.
     *
     * <p>{@code https://usuario:clave@catalogo/} es una URL perfectamente
     * válida y una credencial perfectamente filtrada. Si no parsea, se dice
     * que no parsea en lugar de escribirla igual.
     */
    private static String safeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "<stub en memoria>";
        }
        try {
            java.net.URI uri = java.net.URI.create(baseUrl);
            String rendered = uri.getUserInfo() == null ? baseUrl : baseUrl.replace(uri.getUserInfo() + "@", "***@");
            return LogSanitizer.sanitize(rendered, 200);
        } catch (IllegalArgumentException e) {
            return "<url inválida>";
        }
    }

    // -----------------------------------------------------------------
    // Outbox
    // -----------------------------------------------------------------

    /**
     * El relay durable.
     *
     * <p>El lease del reclamo se deriva del peor caso de un tick
     * —{@code batch-size × confirm-timeout}— en lugar de ser una constante
     * suelta: con lotes de 50 y confirms de 5 s, un tick contra un broker que
     * acepta y no confirma dura más de cuatro minutos, y un lease de dos
     * dejaba que otra instancia re-reclamara mensajes todavía en vuelo.
     */
    @Bean
    public JdbcEventOutbox jdbcEventOutbox(
            JdbcTemplate jdbcTemplate,
            DomainEventPayloadMapper payloadMapper,
            OutboxProperties properties,
            MessagingProperties messaging,
            Clock clock) {
        Duration worstCaseTick =
                messaging.confirmTimeout().multipliedBy(properties.batchSize()).plusSeconds(30);
        OutboxProperties effective = properties.withClaimLeaseAtLeast(worstCaseTick);
        if (!effective.claimLease().equals(properties.claimLease())) {
            log.info(
                    "Lease del reclamo del outbox elevado de {} s a {} s: es el peor caso de un tick "
                            + "({} mensajes × {} s de confirm)",
                    properties.claimLease().toSeconds(),
                    effective.claimLease().toSeconds(),
                    properties.batchSize(),
                    messaging.confirmTimeout().toSeconds());
        }
        if (properties.worstCaseRetryWindow().compareTo(properties.retryCeiling()) < 0) {
            log.warn(
                    "Los dos cortes del outbox dicen cosas distintas: {} intentos cubren {} min de reloj, "
                            + "menos que el techo de {} min. El techo no va a actuar nunca.",
                    properties.maxAttempts(),
                    properties.worstCaseRetryWindow().toMinutes(),
                    properties.retryCeiling().toMinutes());
        }
        return new JdbcEventOutbox(jdbcTemplate, payloadMapper, effective, clock);
    }

    @Bean
    @Primary
    public EventOutboxPort eventOutboxPort(JdbcEventOutbox outbox, MeterRegistry registry) {
        return new MeteredEventOutbox(outbox, registry);
    }
}
