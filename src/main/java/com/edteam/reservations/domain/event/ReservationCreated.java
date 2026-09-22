package com.edteam.reservations.domain.event;

import com.edteam.reservations.domain.model.ItinerarySummary;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationId;
import com.edteam.reservations.domain.model.UserId;

import java.time.Instant;
import java.util.Objects;

/** Se creó una reserva. */
public record ReservationCreated(ReservationId reservationId,
                                 UserId userId,
                                 ItinerarySummary itinerary,
                                 int passengerCount,
                                 Instant occurredAt) implements DomainEvent {

    public static final String TYPE = "reservation.created";

    public ReservationCreated {
        Objects.requireNonNull(reservationId, "reservationId es obligatorio");
        Objects.requireNonNull(userId, "userId es obligatorio");
        Objects.requireNonNull(itinerary, "itinerary es obligatorio");
        Objects.requireNonNull(occurredAt, "occurredAt es obligatorio");
        if (passengerCount < 1) {
            throw new IllegalArgumentException("passengerCount debe ser al menos 1");
        }
    }

    /**
     * Construye el evento desde la reserva recién persistida.
     *
     * @throws IllegalStateException si la reserva todavía no tiene id
     */
    public static ReservationCreated of(Reservation reservation) {
        return new ReservationCreated(
                reservation.requireId(),
                reservation.userId(),
                reservation.itinerary().summary(),
                reservation.passengers().size(),
                reservation.createdAt());
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
