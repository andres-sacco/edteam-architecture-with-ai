package com.edteam.reservations.infrastructure.config;

import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.infrastructure.adapter.out.airport.CachingAirportCatalog;
import com.edteam.reservations.infrastructure.adapter.out.airport.StaticAirportCatalog;
import com.edteam.reservations.infrastructure.adapter.out.airport.catalog.CatalogAirportCatalog;
import com.edteam.reservations.infrastructure.adapter.out.airport.catalog.CityCatalogClient;
import com.edteam.reservations.infrastructure.adapter.out.airport.catalog.RestCityCatalogClient;
import com.edteam.reservations.infrastructure.adapter.out.airport.catalog.RetryingCityCatalogClient;
import com.edteam.reservations.infrastructure.adapter.out.messaging.DomainEventPayloadMapper;
import com.edteam.reservations.infrastructure.adapter.out.outbox.JdbcEventOutbox;
import com.edteam.reservations.infrastructure.adapter.out.outbox.MeteredEventOutbox;
import com.edteam.reservations.infrastructure.adapter.out.outbox.OutboxAdmin;
import com.edteam.reservations.infrastructure.cache.CacheStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/**
 * Cableado de los adaptadores de salida.
 *
 * <p>Los adaptadores que necesitan configuración se declaran acá en lugar de
 * anotarlos con {@code @Component}: así la composición queda visible en un solo
 * archivo. Es lo que permite, por ejemplo, envolver el maestro de aeropuertos
 * con un cache sin que ni el caso de uso ni el adaptador de origen se enteren.
 */
@Configuration
public class AdapterConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AdapterConfiguration.class);

    /**
     * Reloj inyectable. Los casos de uso no llaman a {@code Instant.now()}: lo
     * piden a este bean, y así en los tests se puede fijar el tiempo y verificar
     * reglas como "no se puede reservar un vuelo que ya partió".
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Maestro de aeropuertos: origen + cache, en ese orden.
     *
     * <p>El origen depende de la configuración. Con {@code base-url} definida
     * se llama a la API de catálogo; sin ella queda el stub en memoria, que es
     * lo que permite levantar la aplicación y correr los tests sin depender de
     * un proveedor externo.
     *
     * <p>La decisión de envolver con cache no cambia según el origen: es
     * todavía más necesaria con el cliente REST, porque es la que hace que la
     * mayoría de las reservas no toquen la red.
     *
     * <p>El almacén del cache llega inyectado y ya no lo arma el decorador:
     * eso es lo que permite que la misma composición corra con Redis o con el
     * fallback en memoria según la configuración. Es exactamente el reemplazo
     * por un cache distribuido que este decorador estaba pensado para
     * permitir, y no toca ni a {@code CatalogAirportCatalog} ni al caso de uso.
     */
    @Bean
    public AirportCatalogPort airportCatalogPort(AirportCatalogProperties properties,
                                                 CacheStore cityCatalogCacheStore,
                                                 RestClient.Builder restClientBuilder,
                                                 Clock clock) {
        AirportCatalogPort origin;
        if (properties.hasRemoteCatalog()) {
            requireSecureTransport(properties);
            origin = new CatalogAirportCatalog(catalogClient(restClientBuilder, properties));
            log.info("Maestro de aeropuertos: API de catálogo en {} (connect {} ms, read {} ms, {} intentos)",
                    properties.baseUrl(),
                    properties.connectTimeout().toMillis(),
                    properties.readTimeout().toMillis(),
                    properties.retry().maxAttempts());
        } else {
            origin = StaticAirportCatalog.withDefaults();
            log.info("Maestro de aeropuertos: stub en memoria (no hay 'reservations.airport-catalog.base-url')");
        }
        return new CachingAirportCatalog(origin, cityCatalogCacheStore, properties.cacheTtlPolicy(), clock);
    }

    /**
     * Cliente del catálogo: transporte, traducción HTTP y reintentos.
     *
     * <p>El orden de las capas es el que importa. De adentro hacia afuera:
     * {@code RestCityCatalogClient} clasifica la respuesta —404 es "no
     * existe", 5xx y 429 son transitorios, el resto es integración rota— y
     * {@link RetryingCityCatalogClient} se apoya en esa clasificación para
     * reintentar sólo lo que tiene sentido reintentar. Más arriba,
     * {@code CachingAirportCatalog} hace que la mayoría de las consultas ni
     * lleguen hasta acá.
     */
    /**
     * Corta el arranque si la integración saliente no va cifrada.
     *
     * <p>Es una verificación en el arranque y no una advertencia a propósito:
     * una advertencia en el log de una aplicación que igual levantó es una
     * advertencia que nadie lee. Acá el despliegue falla, que es lo único que
     * garantiza que la API key no salga en claro por la red.
     */
    private static void requireSecureTransport(AirportCatalogProperties properties) {
        if (!properties.usesSecureTransport()) {
            throw new IllegalStateException(
                    ("El catálogo de ciudades está configurado en '%s': la API key viaja en un "
                            + "header y sin TLS se lee en el camino. Usar https:// (o vaciar "
                            + "'base-url' para volver al stub en memoria).").formatted(properties.baseUrl()));
        }
    }

    private static CityCatalogClient catalogClient(RestClient.Builder builder, AirportCatalogProperties properties) {
        return new RetryingCityCatalogClient(
                new RestCityCatalogClient(catalogRestClient(builder, properties)),
                properties.retryPolicy());
    }

    /**
     * Transporte HTTP del catálogo.
     *
     * <p>Se parte del {@code RestClient.Builder} de Spring Boot para heredar
     * los converters y la instrumentación (métricas, trazas) ya configurados;
     * acá sólo se agrega lo propio de este proveedor: la URL base, la
     * credencial y los timeouts.
     *
     * <p><strong>Los timeouts no son opcionales.</strong> Sin read timeout,
     * una llamada contra un proveedor que acepta la conexión y no contesta
     * queda colgada hasta que corte el sistema operativo, y el pedido de
     * reserva que la disparó queda colgado con ella; con
     * {@code maximum-pool-size: 20}, unas pocas de esas agotan el pool y la
     * degradación del catálogo se transforma en una caída nuestra. El caché
     * baja la cantidad de llamadas expuestas, pero no acota el daño de la que
     * sí sale: eso sólo lo hace el timeout.
     *
     * <p>Se separan connect y read a propósito: establecer la conexión es
     * rápido o no va a pasar (500 ms alcanzan de sobra), mientras que
     * responder una consulta puede legítimamente tardar más (2 s). Un valor
     * único obligaría a elegir el más permisivo para los dos.
     *
     * <p>La credencial se manda como header por defecto: nunca en la query
     * string, donde quedaría escrita en logs de acceso y proxies.
     */
    private static RestClient catalogRestClient(RestClient.Builder builder, AirportCatalogProperties properties) {
        ClientHttpRequestFactorySettings timeouts = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());

        RestClient.Builder configured = builder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(timeouts))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (properties.hasApiKey()) {
            configured = configured.defaultHeader(properties.apiKeyHeader(), properties.apiKey());
        }
        return configured.build();
    }

    /**
     * Outbox durable sobre PostgreSQL.
     *
     * <p>Se declara con el tipo concreto porque cumple dos papeles: es el
     * {@link EventOutboxPort} que usan los casos de uso y el
     * {@link OutboxAdmin} que usa el endpoint de gestión. Son dos vistas de la
     * misma tabla y no tiene sentido duplicar el adaptador para separarlas.
     */
    @Bean
    public JdbcEventOutbox jdbcEventOutbox(JdbcTemplate jdbcTemplate,
                                           DomainEventPayloadMapper payloadMapper,
                                           OutboxProperties properties,
                                           Clock clock) {
        return new JdbcEventOutbox(jdbcTemplate, payloadMapper, properties, clock);
    }

    /**
     * El puerto que ven los casos de uso: el outbox instrumentado.
     *
     * <p>{@code @Primary} porque el bean de arriba también satisface el puerto
     * —es el delegado— y sin esto la inyección quedaría ambigua. Es el mismo
     * patrón de decorador con el que el cache se instrumenta y el maestro de
     * aeropuertos se envuelve: la composición queda visible en el cableado y
     * cada pieza se testea sin la otra.
     */
    @Bean
    @Primary
    public EventOutboxPort eventOutboxPort(JdbcEventOutbox outbox, MeterRegistry registry) {
        return new MeteredEventOutbox(outbox, registry);
    }
}
