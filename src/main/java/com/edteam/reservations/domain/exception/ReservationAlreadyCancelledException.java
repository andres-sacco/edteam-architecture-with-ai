package com.edteam.reservations.domain.exception;

/** Se intentó cancelar una reserva que ya estaba cancelada. */
public class ReservationAlreadyCancelledException extends DomainException {

    public ReservationAlreadyCancelledException(String reservationReference) {
        super("La reserva %s ya se encuentra cancelada".formatted(reservationReference));
    }
}
