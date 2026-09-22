package com.edteam.reservations.domain.model;

/** Identificador del segmento ({@code BIGSERIAL} en el modelo de datos). */
public record SegmentId(long value) {

    public SegmentId {
        if (value <= 0) {
            throw new IllegalArgumentException("El id de segmento debe ser positivo");
        }
    }

    public static SegmentId of(long value) {
        return new SegmentId(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
