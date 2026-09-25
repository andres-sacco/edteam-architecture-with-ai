package com.edteam.reservations.infrastructure.adapter.out.airport.catalog;

import com.edteam.reservations.application.exception.AirportCatalogIntegrationException;
import com.edteam.reservations.application.exception.AirportCatalogThrottledException;
import com.edteam.reservations.application.exception.AirportCatalogUnavailableException;
import com.edteam.reservations.infrastructure.adapter.out.airport.CityResolution;
import com.edteam.reservations.infrastructure.adapter.out.airport.CityResolver;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Puente entre la cadena de clientes HTTP y el resolutor por conjunto.
 *
 * <p>Hace dos cosas, y las dos son de borde:
 *
 * <ol>
 *   <li><strong>Traduce el rechazo de la librería.</strong> Es la última clase
 *       que ve {@code CallNotPermittedException} y {@code BulkheadFullException}:
 *       de acá para arriba sólo hay {@link CityResolution} y excepciones del
 *       vocabulario de la aplicación. Así la librería de resiliencia no llega
 *       ni al puerto ni al caso de uso, y el manejador de errores REST no
 *       tiene que aprender tipos nuevos.</li>
 *   <li><strong>Convierte un fallo en un resultado por ciudad</strong>, con su
 *       motivo. El motivo termina como etiqueta de la métrica de degradación:
 *       sin él, «se sirvió un dato viejo» no distingue entre el circuito
 *       abierto, los intentos agotados y el presupuesto vencido, que son tres
 *       problemas distintos con tres remedios distintos.</li>
 * </ol>
 *
 * <p>La excepción que <strong>no</strong> se convierte en un resultado es
 * {@link AirportCatalogIntegrationException}: una credencial vencida o un
 * cuerpo fuera de contrato es el caso que ningún mecanismo automático
 * resuelve. Taparlo con un dato viejo lo dejaría invisible durante las horas
 * enteras de la ventana de gracia, que es justamente el hallazgo que este
 * cambio corrige. Sube, se cuenta aparte y llega a una persona.
 */
public class CatalogCityResolver implements CityResolver {

    /** Errores del catálogo por clase. {@code kind=integration} es el que se alerta. */
    public static final String ERRORS = "reservations.catalog.errors";

    public static final String CIRCUIT_OPEN = "circuit_open";
    public static final String BULKHEAD_FULL = "bulkhead_full";
    public static final String THROTTLED = "throttled";
    public static final String RETRIES_EXHAUSTED = "retries_exhausted";

    /**
     * El fallo que necesita una persona: credencial vencida, permisos, cuerpo
     * fuera de contrato. Es la serie sobre la que el diseño justifica una
     * alerta que despierta a alguien, y por eso tiene su propia etiqueta.
     */
    public static final String INTEGRATION = "integration";

    private final CityCatalogClient client;
    private final MeterRegistry registry;

    public CatalogCityResolver(CityCatalogClient client, MeterRegistry registry) {
        this.client = Objects.requireNonNull(client, "El cliente del catálogo es obligatorio");
        this.registry = Objects.requireNonNull(registry, "El registro de métricas es obligatorio");
    }

    @Override
    public Map<String, CityResolution> resolve(Collection<String> codes) {
        Map<String, CityResolution> resolutions = new LinkedHashMap<>();
        for (String code : codes) {
            resolutions.put(code, resolveOne(code));
        }
        return resolutions;
    }

    private CityResolution resolveOne(String code) {
        try {
            return CityResolution.of(client.findByCode(code).isPresent());
        } catch (CallNotPermittedException e) {
            count(CIRCUIT_OPEN);
            return CityResolution.unavailable(CIRCUIT_OPEN);
        } catch (BulkheadFullException e) {
            count(BULKHEAD_FULL);
            return CityResolution.unavailable(BULKHEAD_FULL);
        } catch (AirportCatalogThrottledException e) {
            count(THROTTLED);
            return CityResolution.unavailable(THROTTLED);
        } catch (AirportCatalogUnavailableException e) {
            count(RETRIES_EXHAUSTED);
            return CityResolution.unavailable(RETRIES_EXHAUSTED);
        } catch (AirportCatalogIntegrationException e) {
            // Se cuenta y se relanza: no hay fallback para esto. Ver el javadoc.
            count(INTEGRATION);
            throw e;
        }
    }

    private void count(String kind) {
        Counter.builder(ERRORS)
                .tags(Tags.of("kind", kind))
                .description("Fallos del catálogo de ciudades, por clase")
                .register(registry)
                .increment();
    }
}
