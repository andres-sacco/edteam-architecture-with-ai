package com.edteam.reservations.domain.event;

import com.edteam.reservations.domain.model.ItinerarySummary;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationId;
import com.edteam.reservations.domain.model.UserId;
import java.time.Instant;
import java.util.Objects;

/** Se confirmó una reserva que estaba pendiente. */
public record ReservationConfirmed(
        ReservationId reservationId, UserId userId, ItinerarySummary itinerary, Instant occurredAt)
        implements DomainEvent {

    public static final String TYPE = "reservation.confirmed";

    public ReservationConfirmed {
        Objects.requireNonNull(reservationId, "reservationId es obligatorio");
        Objects.requireNonNull(userId, "userId es obligatorio");
        Objects.requireNonNull(itinerary, "itinerary es obligatorio");
        Objects.requireNonNull(occurredAt, "occurredAt es obligatorio");
    }

    public static ReservationConfirmed of(Reservation reservation) {
        return new ReservationConfirmed(
                reservation.requireId(),
                reservation.userId(),
                reservation.itinerary().summary(),
                reservation.updatedAt());
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
