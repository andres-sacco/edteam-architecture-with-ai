package com.edteam.reservations.domain.exception;

/** El código de aeropuerto no respeta el formato IATA (3 letras). */
public class InvalidAirportCodeException extends DomainException {

    public InvalidAirportCodeException(String message) {
        super(message);
    }
}
