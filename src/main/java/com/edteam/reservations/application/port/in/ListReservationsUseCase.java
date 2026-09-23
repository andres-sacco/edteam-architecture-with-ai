package com.edteam.reservations.application.port.in;

import com.edteam.reservations.application.query.ResultPage;
import com.edteam.reservations.domain.model.Reservation;

/** Listado paginado de reservas, acotado a lo que el solicitante puede ver. */
public interface ListReservationsUseCase {

    /**
     * @throws com.edteam.reservations.domain.access.ReservationAccessDeniedException
     *         si un titular pide explícitamente el listado de otro usuario
     */
    ResultPage<Reservation> list(ListReservationsQuery query);
}
