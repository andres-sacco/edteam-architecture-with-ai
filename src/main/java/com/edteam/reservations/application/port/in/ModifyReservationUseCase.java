package com.edteam.reservations.application.port.in;

import com.edteam.reservations.domain.model.Reservation;

/** Puerto de entrada: cambiar el itinerario de una reserva. */
public interface ModifyReservationUseCase {

    /**
     * @throws com.edteam.reservations.application.exception.ReservationNotFoundException si no existe
     * @throws com.edteam.reservations.application.exception.ConcurrentUpdateException    si otro proceso la modificó
     * @throws com.edteam.reservations.application.exception.UnknownAirportException      si algún aeropuerto no existe
     */
    Reservation modify(ModifyReservationCommand command);
}
