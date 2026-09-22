package com.edteam.reservations.domain.exception;

/** Los datos del usuario no son válidos. */
public class InvalidUserException extends DomainException {

    public InvalidUserException(String message) {
        super(message);
    }
}
