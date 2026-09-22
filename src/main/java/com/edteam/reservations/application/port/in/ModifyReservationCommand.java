package com.edteam.reservations.application.port.in;

import java.util.Objects;

/**
 * Datos de entrada para cambiar el itinerario de una reserva.
 *
 * <p>{@code expectedVersion} es la versión que el cliente leyó. Sin ese dato no
 * hay forma de detectar que otro usuario modificó la reserva en el medio
 * (lost update). En REST viaja como {@code If-Match}/ETag.
 */
public record ModifyReservationCommand(long reservationId, long expectedVersion, ItineraryData newItinerary) {

    public ModifyReservationCommand {
        Objects.requireNonNull(newItinerary, "newItinerary es obligatorio");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion no puede ser negativa");
        }
    }
}
