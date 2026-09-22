package com.edteam.reservations.domain.model;

/**
 * Identificador del usuario dueño de la reserva.
 *
 * <p>Es un {@code long} porque el modelo de datos usa {@code BIGSERIAL}. El
 * usuario es un agregado aparte: la reserva lo referencia por id y nunca lo
 * modifica.
 */
public record UserId(long value) {

    public UserId {
        if (value <= 0) {
            throw new IllegalArgumentException("El id de usuario debe ser positivo");
        }
    }

    public static UserId of(long value) {
        return new UserId(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
