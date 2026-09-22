package com.edteam.reservations.domain.model;

/** Identificador del pasajero ({@code BIGSERIAL} en el modelo de datos). */
public record PassengerId(long value) {

    public PassengerId {
        if (value <= 0) {
            throw new IllegalArgumentException("El id de pasajero debe ser positivo");
        }
    }

    public static PassengerId of(long value) {
        return new PassengerId(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
