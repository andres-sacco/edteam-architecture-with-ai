package com.edteam.reservations.domain.event;

import com.edteam.reservations.domain.model.Itinerary;
import com.edteam.reservations.domain.model.ItinerarySummary;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationId;
import com.edteam.reservations.domain.model.UserId;

import java.time.Instant;
import java.util.Objects;

/**
 * Se cambió el itinerario de una reserva.
 *
 * <p>Lleva el itinerario anterior además del nuevo para que la notificación
 * pueda mostrar el cambio ("tu vuelo pasó del 12 al 14") en lugar de sólo el
 * estado final.
 */
public record ReservationModified(ReservationId reservationId,
                                  UserId userId,
                                  ItinerarySummary previousItinerary,
                                  ItinerarySummary itinerary,
                                  Instant occurredAt) implements DomainEvent {

    public static final String TYPE = "reservation.modified";

    public ReservationModified {
        Objects.requireNonNull(reservationId, "reservationId es obligatorio");
        Objects.requireNonNull(userId, "userId es obligatorio");
        Objects.requireNonNull(previousItinerary, "previousItinerary es obligatorio");
        Objects.requireNonNull(itinerary, "itinerary es obligatorio");
        Objects.requireNonNull(occurredAt, "occurredAt es obligatorio");
    }

    public static ReservationModified of(Reservation reservation, Itinerary previousItinerary) {
        Objects.requireNonNull(previousItinerary, "El itinerario anterior es obligatorio");
        return new ReservationModified(
                reservation.requireId(),
                reservation.userId(),
                previousItinerary.summary(),
                reservation.itinerary().summary(),
                reservation.updatedAt());
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
