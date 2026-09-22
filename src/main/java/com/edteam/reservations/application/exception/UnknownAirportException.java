package com.edteam.reservations.application.exception;

import com.edteam.reservations.domain.model.AirportCode;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Uno o más aeropuertos del itinerario no existen en el maestro.
 *
 * <p>Informa todos los códigos desconocidos juntos: si el cliente mandó mal el
 * origen y el destino, se entera de los dos en una sola respuesta en lugar de
 * corregir de a uno.
 */
public class UnknownAirportException extends ApplicationException {

    private final List<AirportCode> unknownCodes;

    public UnknownAirportException(List<AirportCode> unknownCodes) {
        super("Los siguientes aeropuertos no existen: %s".formatted(
                unknownCodes.stream().map(AirportCode::value).collect(Collectors.joining(", "))));
        this.unknownCodes = List.copyOf(unknownCodes);
    }

    public List<AirportCode> unknownCodes() {
        return unknownCodes;
    }
}
