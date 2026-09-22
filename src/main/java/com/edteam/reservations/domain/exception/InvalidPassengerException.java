package com.edteam.reservations.domain.exception;

/** Los datos del pasajero no son válidos. */
public class InvalidPassengerException extends DomainException {

    public InvalidPassengerException(String message) {
        super(message);
    }
}
