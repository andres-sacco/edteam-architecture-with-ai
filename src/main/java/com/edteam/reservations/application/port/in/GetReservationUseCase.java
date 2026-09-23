package com.edteam.reservations.application.port.in;

import com.edteam.reservations.domain.model.Reservation;

/** Lectura de una reserva por su identificador. */
public interface GetReservationUseCase {

    /**
     * Devuelve la reserva si el solicitante puede verla.
     *
     * @throws com.edteam.reservations.application.exception.ReservationNotFoundException
     *         si no existe <b>o</b> si no es del solicitante. Es el mismo error a
     *         propósito: distinguirlos convertiría el par de códigos en un
     *         censo de las reservas del sistema.
     */
    Reservation get(GetReservationQuery query);
}
