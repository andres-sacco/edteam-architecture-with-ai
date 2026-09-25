package com.edteam.reservations.application.port.in;

import com.edteam.reservations.domain.access.Actor;
import java.util.List;
import java.util.Objects;

/**
 * Pedido de alta de una reserva.
 *
 * <p>Ya no lleva {@code UserData}: el comprador es el {@code actor}, y el
 * actor sale del token. Que el cliente declarara en el cuerpo a nombre de
 * quién reservaba permitía dar de alta una reserva a nombre de cualquier
 * email y hacer que la notificación —desde nuestro canal, con nuestra
 * reputación— le llegara a la víctima.
 *
 * @param actor          comprador; también el destinatario de las notificaciones
 * @param idempotencyKey clave del intento, generada por el cliente
 * @param itinerary      itinerario a reservar
 * @param passengers     pasajeros; entre 1 y 9
 */
public record CreateReservationCommand(
        Actor actor, String idempotencyKey, ItineraryData itinerary, List<PassengerData> passengers) {

    public CreateReservationCommand {
        Objects.requireNonNull(actor, "El solicitante es obligatorio");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey es obligatorio");
        Objects.requireNonNull(itinerary, "itinerary es obligatorio");
        Objects.requireNonNull(passengers, "passengers es obligatorio");
        passengers = List.copyOf(passengers);
    }
}
