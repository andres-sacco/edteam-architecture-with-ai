package com.edteam.reservations.domain.model;

/**
 * Identificador de la reserva.
 *
 * <p>Es un {@code long} porque el modelo de datos lo define como
 * {@code BIGSERIAL}: lo asigna la base de datos al insertar. Antes de ese
 * momento la reserva no tiene id, y eso se representa con un
 * {@code Optional.empty()} en {@link Reservation#id()} en lugar de con un cero
 * o un nulo escondido.
 *
 * <p>Para identificar la reserva desde el primer momento —y para que un
 * reintento del cliente no genere un duplicado— está
 * {@link IdempotencyKey}, que sí se genera en la aplicación.
 */
public record ReservationId(long value) {

    public ReservationId {
        if (value <= 0) {
            throw new IllegalArgumentException("El id de reserva debe ser positivo");
        }
    }

    public static ReservationId of(long value) {
        return new ReservationId(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
