package com.edteam.reservations.domain.exception;

/** El itinerario es inconsistente (mismo origen y destino, fechas invertidas, etc.). */
public class InvalidItineraryException extends DomainException {

    public InvalidItineraryException(String message) {
        super(message);
    }
}
