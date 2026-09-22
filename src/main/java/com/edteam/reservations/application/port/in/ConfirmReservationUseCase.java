package com.edteam.reservations.application.port.in;

import com.edteam.reservations.domain.model.Reservation;

/** Puerto de entrada: confirmar una reserva pendiente. */
public interface ConfirmReservationUseCase {

    /**
     * @throws com.edteam.reservations.application.exception.ReservationNotFoundException  si no existe
     * @throws com.edteam.reservations.application.exception.ConcurrentUpdateException     si otro proceso la modificó
     * @throws com.edteam.reservations.domain.exception.ReservationNotModifiableException  si no está pendiente
     */
    Reservation confirm(ConfirmReservationCommand command);
}
