package com.edteam.reservations.application.service;

import com.edteam.reservations.application.exception.UnknownAirportException;
import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.domain.model.AirportCode;
import com.edteam.reservations.domain.model.Itinerary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Valida contra el maestro que los aeropuertos del itinerario existan.
 *
 * <p>La regla se comparte entre la creación y la modificación, así que se
 * extrae en un colaborador propio en lugar de duplicarla en cada caso de uso.
 *
 * <p>Con itinerarios de varios tramos hay que validar todos los aeropuertos que
 * toca el viaje, no sólo el origen y el destino finales: una escala en un
 * aeropuerto inexistente también invalida la reserva.
 */
@Component
public class AirportExistenceValidator {

    private final AirportCatalogPort airportCatalog;

    public AirportExistenceValidator(AirportCatalogPort airportCatalog) {
        this.airportCatalog = Objects.requireNonNull(airportCatalog);
    }

    /**
     * @throws UnknownAirportException con todos los códigos desconocidos encontrados,
     *                                 para que el cliente los corrija de una sola vez
     */
    public void validate(Itinerary itinerary) {
        Objects.requireNonNull(itinerary, "El itinerario es obligatorio");

        List<AirportCode> unknown = new ArrayList<>();
        for (AirportCode airport : itinerary.airports()) {
            if (!airportCatalog.exists(airport)) {
                unknown.add(airport);
            }
        }
        if (!unknown.isEmpty()) {
            throw new UnknownAirportException(unknown);
        }
    }
}
