package com.edteam.reservations.infrastructure.config;

import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.infrastructure.adapter.out.airport.CachingAirportCatalog;
import com.edteam.reservations.infrastructure.adapter.out.airport.StaticAirportCatalog;
import com.edteam.reservations.infrastructure.adapter.out.airport.catalog.CatalogAirportCatalog;
import com.edteam.reservations.infrastructure.adapter.out.airport.catalog.RestCityCatalogClient;
import com.edteam.reservations.infrastructure.adapter.out.outbox.InMemoryEventOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
     */
    @Bean
    public AirportCatalogPort airportCatalogPort(AirportCatalogProperties properties,
                                                 RestClient.Builder restClientBuilder,
                                                 Clock clock) {
        AirportCatalogPort origin;
        if (properties.hasRemoteCatalog()) {
            origin = new CatalogAirportCatalog(new RestCityCatalogClient(catalogRestClient(restClientBuilder, properties)));
            log.info("Maestro de aeropuertos: API de catálogo en {}", properties.baseUrl());
        } else {
            origin = StaticAirportCatalog.withDefaults();
            log.info("Maestro de aeropuertos: stub en memoria (no hay 'reservations.airport-catalog.base-url')");
        }
        return new CachingAirportCatalog(origin, properties.cacheTtl(), clock);
    }

    /**
     * Cliente HTTP del catálogo.
     *
     * <p>Se parte del {@code RestClient.Builder} de Spring Boot para heredar
     * los converters y la instrumentación (métricas, trazas) ya configurados;
     * acá sólo se agrega lo propio de este proveedor: la URL base y la
     * credencial.
     *
     * <p><strong>Sin timeouts ni reintentos</strong>, por la decisión tomada
     * para esta iteración. Si se revisa, el cambio es local a este método —un
     * {@code requestFactory} con timeouts— y no toca al cliente ni al puerto.
     * La credencial se manda como header por defecto: nunca en la query
     * string, donde quedaría escrita en logs de acceso y proxies.
     */
    private static RestClient catalogRestClient(RestClient.Builder builder, AirportCatalogProperties properties) {
        RestClient.Builder configured = builder.clone()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (properties.hasApiKey()) {
            configured = configured.defaultHeader(properties.apiKeyHeader(), properties.apiKey());
        }
        return configured.build();
    }

    /**
     * Se declara con el tipo concreto —y no con el puerto— para que los tests de
     * integración puedan inspeccionar el estado del outbox. Los consumidores
     * siguen dependiendo de {@link EventOutboxPort}.
     */
    @Bean
    public InMemoryEventOutbox eventOutboxPort(OutboxProperties properties, Clock clock) {
        return new InMemoryEventOutbox(clock, properties.maxAttempts());
    }
}
