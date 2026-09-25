package com.edteam.reservations.application.port.in;

import com.edteam.reservations.application.query.ReservationSearchCriteria;
import com.edteam.reservations.domain.access.Actor;
import java.util.Objects;

/**
 * Pedido de listado, con el criterio que armó el cliente y quién lo pide.
 *
 * <p>El criterio viene tal cual llegó: el caso de uso lo <em>reduce</em> al
 * alcance del actor antes de consultar. Se separan a propósito, para que el
 * criterio pedido y el criterio efectivo sean dos cosas distintas y se pueda
 * probar que la reducción ocurre.
 */
public record ListReservationsQuery(ReservationSearchCriteria criteria, Actor actor) {

    public ListReservationsQuery {
        Objects.requireNonNull(criteria, "El criterio de búsqueda es obligatorio");
        Objects.requireNonNull(actor, "El solicitante es obligatorio");
    }
}
