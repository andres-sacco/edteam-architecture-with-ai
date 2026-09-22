package com.edteam.reservations.application.port.in;

import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationId;

/** Puerto de entrada: consultar una reserva. */
public interface GetReservationUseCase {

    /**
     * @throws com.edteam.reservations.application.exception.ReservationNotFoundException si no existe
     */
    Reservation getById(ReservationId reservationId);
}
