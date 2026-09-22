package com.edteam.reservations.application.exception;

import com.edteam.reservations.domain.model.AirportCode;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Una o más ciudades del itinerario no existen en el catálogo.
 *
 * <p>Informa todos los códigos desconocidos juntos: si el cliente mandó mal el
 * origen y el destino, se entera de los dos en una sola respuesta en lugar de
 * corregir de a uno.
 *
 * <p>El nombre de la clase —y el código {@code UNKNOWN_AIRPORT} del contrato—
 * quedaron de cuando el maestro era de aeropuertos. Lo que se valida hoy son
 * ciudades; renombrarlos es un cambio incompatible de la API y se decide
 * aparte.
 */
public class UnknownAirportException extends ApplicationException {

    private final List<AirportCode> unknownCodes;

    public UnknownAirportException(List<AirportCode> unknownCodes) {
        super("Las siguientes ciudades no existen en el catálogo: %s".formatted(
                unknownCodes.stream().map(AirportCode::value).collect(Collectors.joining(", "))));
        this.unknownCodes = List.copyOf(unknownCodes);
    }

    public List<AirportCode> unknownCodes() {
        return unknownCodes;
    }
}
