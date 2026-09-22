package com.edteam.reservations.application.port.in;

import java.util.List;
import java.util.Objects;

/**
 * Datos de entrada para crear una reserva.
 *
 * <p>{@code idempotencyKey} lo genera el cliente y es lo que hace segura la
 * operación ante reintentos: dos pedidos con la misma clave producen una sola
 * reserva, incluso si llegan al mismo tiempo (el {@code UNIQUE} del modelo de
 * datos es el que cierra la carrera).
 *
 * <p>El usuario viaja con sus datos y no como id: el sistema no expone un alta
 * de usuarios, así que exigir un id obligaría a que la fila ya estuviera
 * cargada por fuera de la aplicación. Se lo identifica por email —su clave
 * natural— y se lo da de alta si es la primera vez que reserva.
 *
 * @param user            quien reserva, identificado por su email
 * @param idempotencyKey  UUID generado por el cliente
 * @param itinerary       itinerario a reservar, con sus tramos en orden
 * @param passengers      al menos un pasajero
 */
public record CreateReservationCommand(UserData user,
                                       String idempotencyKey,
                                       ItineraryData itinerary,
                                       List<PassengerData> passengers) {

    public CreateReservationCommand {
        Objects.requireNonNull(user, "user es obligatorio");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey es obligatorio");
        Objects.requireNonNull(itinerary, "itinerary es obligatorio");
        Objects.requireNonNull(passengers, "passengers es obligatorio");
        passengers = List.copyOf(passengers);
    }
}
