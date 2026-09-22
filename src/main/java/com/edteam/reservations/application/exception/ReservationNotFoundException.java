package com.edteam.reservations.application.exception;

import com.edteam.reservations.domain.model.ReservationId;

/** No existe una reserva con el id solicitado. */
public class ReservationNotFoundException extends ApplicationException {

    private final ReservationId reservationId;

    public ReservationNotFoundException(ReservationId reservationId) {
        super("No existe la reserva %s".formatted(reservationId));
        this.reservationId = reservationId;
    }

    public ReservationId reservationId() {
        return reservationId;
    }
}
