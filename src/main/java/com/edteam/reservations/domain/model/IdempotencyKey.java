package com.edteam.reservations.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Clave que identifica un intento de reserva, generada por el cliente.
 *
 * <p>Es la pieza que evita reservas duplicadas cuando el cliente reintenta: un
 * doble click, un timeout de red o un retry automático mandan la misma clave, y
 * el sistema devuelve la reserva que ya existe en lugar de crear otra. El
 * modelo de datos la declara {@code UNIQUE}, así que la garantía no depende de
 * la lógica de la aplicación: incluso con dos pedidos simultáneos, la base
 * rechaza el segundo {@code INSERT}.
 *
 * <p>También es la identidad estable de la reserva antes de que la base asigne
 * el {@link ReservationId}.
 */
public record IdempotencyKey(UUID value) {

    public IdempotencyKey {
        Objects.requireNonNull(value, "La clave de idempotencia es obligatoria");
    }

    public static IdempotencyKey of(UUID value) {
        return new IdempotencyKey(value);
    }

    public static IdempotencyKey of(String value) {
        Objects.requireNonNull(value, "La clave de idempotencia es obligatoria");
        try {
            return new IdempotencyKey(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("La clave de idempotencia '%s' no es un UUID válido".formatted(value));
        }
    }

    public static IdempotencyKey newKey() {
        return new IdempotencyKey(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
