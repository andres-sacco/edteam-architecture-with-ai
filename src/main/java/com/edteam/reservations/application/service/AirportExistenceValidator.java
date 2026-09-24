package com.edteam.reservations.application.service;

import com.edteam.reservations.application.exception.UnknownAirportException;
import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.domain.model.AirportCode;
import com.edteam.reservations.domain.model.Itinerary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Verifica que todos los aeropuertos del itinerario existan en el maestro.
 *
 * <p>Vive en la aplicación y no en el dominio porque necesita el maestro, que
 * es una dependencia externa: el dominio valida la <em>forma</em> de un código
 * ({@link AirportCode}), la aplicación valida su <em>existencia</em>.
 *
 * <p>Delega el conjunto completo en una sola llamada al puerto. Antes recorría
 * los aeropuertos uno por uno, y ese bucle era la razón estructural por la que
 * un itinerario de once ciudades contra un catálogo degradado costaba la suma
 * de once peores casos. Cómo se resuelve el conjunto —en paralelo, con cache,
 * con circuito, con presupuesto— es problema del adaptador; acá no se sabe ni
 * hace falta saberlo.
 */
@Component
public class AirportExistenceValidator {

    private final AirportCatalogPort airportCatalog;

    public AirportExistenceValidator(AirportCatalogPort airportCatalog) {
        this.airportCatalog = Objects.requireNonNull(airportCatalog);
    }

    /**
     * @throws UnknownAirportException si alguno de los códigos no existe
     * @throws com.edteam.reservations.application.exception.AirportCatalogUnavailableException
     *         si no se pudo averiguar
     */
    public void validate(Itinerary itinerary) {
        Objects.requireNonNull(itinerary, "El itinerario es obligatorio");

        Set<AirportCode> airports = itinerary.airports();
        Set<AirportCode> unknown = airportCatalog.unknown(airports);
        if (unknown == null || unknown.isEmpty()) {
            return;
        }
        // Se reordena según el itinerario para que el mensaje de error nombre
        // las ciudades en el orden en que el usuario las escribió.
        List<AirportCode> ordered = airports.stream().filter(unknown::contains).toList();
        throw new UnknownAirportException(ordered.isEmpty() ? List.copyOf(unknown) : ordered);
    }
}
