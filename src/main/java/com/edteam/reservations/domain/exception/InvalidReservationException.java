package com.edteam.reservations.domain.exception;

/** La reserva no es válida (sin pasajeros, pasajeros repetidos, etc.). */
public class InvalidReservationException extends DomainException {

    public InvalidReservationException(String message) {
        super(message);
    }
}
