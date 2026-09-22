package com.edteam.reservations.domain.model;

/** Identificador del itinerario ({@code BIGSERIAL} en el modelo de datos). */
public record ItineraryId(long value) {

    public ItineraryId {
        if (value <= 0) {
            throw new IllegalArgumentException("El id de itinerario debe ser positivo");
        }
    }

    public static ItineraryId of(long value) {
        return new ItineraryId(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
