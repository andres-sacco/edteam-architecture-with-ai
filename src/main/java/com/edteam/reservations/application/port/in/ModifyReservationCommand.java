package com.edteam.reservations.application.port.in;

import com.edteam.reservations.domain.access.Actor;

import java.util.Objects;

/**
 * Pedido de reemplazo del itinerario de una reserva.
 *
 * @param reservationId   reserva a modificar
 * @param expectedVersion versión sobre la que trabajó el cliente ({@code If-Match})
 * @param newItinerary    itinerario que reemplaza al vigente
 * @param actor           quién pide la modificación
 */
public record ModifyReservationCommand(long reservationId,
                                       long expectedVersion,
                                       ItineraryData newItinerary,
                                       Actor actor) {

    public ModifyReservationCommand {
        Objects.requireNonNull(newItinerary, "newItinerary es obligatorio");
        Objects.requireNonNull(actor, "El solicitante es obligatorio");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion no puede ser negativa");
        }
    }
}
