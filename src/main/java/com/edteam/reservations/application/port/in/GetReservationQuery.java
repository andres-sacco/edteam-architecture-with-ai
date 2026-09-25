package com.edteam.reservations.application.port.in;

import com.edteam.reservations.domain.access.Actor;
import com.edteam.reservations.domain.model.ReservationId;
import java.util.Objects;

/**
 * Pedido de lectura de una reserva por id.
 *
 * <p>La lectura también lleva solicitante, y es el caso que más importa: el
 * {@code GET} por id era la forma más barata de vaciar los datos de pasajeros
 * de todo el sistema recorriendo los ids.
 *
 * @param reservationId reserva a leer
 * @param actor         quién la pide
 */
public record GetReservationQuery(ReservationId reservationId, Actor actor) {

    public GetReservationQuery {
        Objects.requireNonNull(reservationId, "El id es obligatorio");
        Objects.requireNonNull(actor, "El solicitante es obligatorio");
    }
}
