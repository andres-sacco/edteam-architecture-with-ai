package com.edteam.reservations.infrastructure.adapter.out.airport.catalog;

import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.domain.model.AirportCode;

import java.util.Objects;

/**
 * Implementa el puerto del maestro de aeropuertos contra la API de catálogo.
 *
 * <p>Es deliberadamente delgada: todo lo que tiene que ver con HTTP —estados,
 * cuerpos, fallos— vive en {@link RestCityCatalogClient}. Acá sólo se traduce
 * "hay ciudad" a "existe", que es el único vocabulario que conoce el caso de
 * uso.
 *
 * <p>Los fallos <strong>no</strong> se tragan: si el catálogo no contestó, la
 * excepción sube. Devolver {@code false} ante una caída del proveedor haría
 * que el sistema rechazara reservas con aeropuertos válidos, que es peor que
 * fallar de forma visible.
 *
 * <p>Va debajo de {@code CachingAirportCatalog}, no encima: la caché absorbe
 * la mayor parte del tráfico —el maestro casi no cambia— y este cliente sólo
 * ve las consultas que no pudo resolver. Como el decorador guarda en caché
 * únicamente los resultados y no las excepciones, una caída del catálogo no
 * envenena la caché.
 *
 * <p><strong>Son ciudades, no aeropuertos.</strong> Los códigos del itinerario
 * se validan contra {@code /city/{code}}: lo que se vende es un par de
 * ciudades. El campo del contrato todavía se llama {@code originAirportCode} y
 * el tipo del dominio {@code AirportCode}; renombrarlos es un cambio
 * incompatible de la API y queda como decisión aparte. Consecuencia práctica:
 * un código de aeropuerto (EZE, AEP) no está en el catálogo, así que el pedido
 * se rechaza con 400 nombrando ese código.
 */
public class CatalogAirportCatalog implements AirportCatalogPort {

    private final CityCatalogClient client;

    public CatalogAirportCatalog(CityCatalogClient client) {
        this.client = Objects.requireNonNull(client, "El cliente del catálogo es obligatorio");
    }

    @Override
    public boolean exists(AirportCode code) {
        if (code == null) {
            return false;
        }
        return client.findByCode(code.value()).isPresent();
    }
}
