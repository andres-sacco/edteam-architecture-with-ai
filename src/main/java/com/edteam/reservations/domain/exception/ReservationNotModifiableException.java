package com.edteam.reservations.domain.exception;

import com.edteam.reservations.domain.model.ReservationStatus;

/** La operación no es válida para el estado actual de la reserva. */
public class ReservationNotModifiableException extends DomainException {

    public ReservationNotModifiableException(String reservationReference, ReservationStatus status) {
        super("La operación no es válida para la reserva %s porque está en estado %s"
                .formatted(reservationReference, status));
    }
}
