package com.edteam.reservations.application.port.in;

import com.edteam.reservations.domain.model.Reservation;

/** Puerto de entrada: cancelar una reserva. */
public interface CancelReservationUseCase {

    /**
     * @throws com.edteam.reservations.application.exception.ReservationNotFoundException    si no existe
     * @throws com.edteam.reservations.application.exception.ConcurrentUpdateException       si otro proceso la modificó
     * @throws com.edteam.reservations.domain.exception.ReservationAlreadyCancelledException si ya estaba cancelada
     */
    Reservation cancel(CancelReservationCommand command);
}
