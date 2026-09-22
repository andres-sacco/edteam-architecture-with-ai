package com.edteam.reservations.application.port.in;

import com.edteam.reservations.application.query.ReservationSearchCriteria;
import com.edteam.reservations.application.query.ResultPage;
import com.edteam.reservations.domain.model.Reservation;

/** Puerto de entrada: listar reservas con filtros y paginación. */
public interface ListReservationsUseCase {

    /**
     * Devuelve la página de reservas que cumple el criterio.
     *
     * <p>Una página vacía no es un error: significa que no hay reservas que
     * cumplan el filtro, o que se pidió una página más allá del final.
     */
    ResultPage<Reservation> list(ReservationSearchCriteria criteria);
}
