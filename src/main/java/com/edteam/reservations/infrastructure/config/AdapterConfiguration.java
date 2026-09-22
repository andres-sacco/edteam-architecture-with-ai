package com.edteam.reservations.infrastructure.config;

import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.infrastructure.adapter.out.airport.CachingAirportCatalog;
import com.edteam.reservations.infrastructure.adapter.out.airport.StaticAirportCatalog;
import com.edteam.reservations.infrastructure.adapter.out.outbox.InMemoryEventOutbox;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    /**
     * Reloj inyectable. Los casos de uso no llaman a {@code Instant.now()}: lo
     * piden a este bean, y así en los tests se puede fijar el tiempo y verificar
     * reglas como "no se puede reservar un vuelo que ya partió".
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public AirportCatalogPort airportCatalogPort(AirportCatalogProperties properties, Clock clock) {
        return new CachingAirportCatalog(StaticAirportCatalog.withDefaults(), properties.cacheTtl(), clock);
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
