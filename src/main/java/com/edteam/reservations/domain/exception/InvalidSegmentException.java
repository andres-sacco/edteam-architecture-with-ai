package com.edteam.reservations.domain.exception;

/** El segmento no es válido (origen igual al destino, aerolínea ausente, etc.). */
public class InvalidSegmentException extends DomainException {

    public InvalidSegmentException(String message) {
        super(message);
    }
}
