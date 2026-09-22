package com.edteam.reservations.domain.model;

/**
 * Estados de una reserva, los mismos que el {@code CHECK} del modelo de datos.
 *
 * <p>La correspondencia con los valores de la columna {@code reserva.estado}
 * (PENDIENTE / CONFIRMADA / CANCELADA) la resuelve el adaptador de persistencia:
 * el dominio no se ata a los literales de la base.
 */
public enum ReservationStatus {

    /** Reserva creada, todavía sin confirmar. Es el estado inicial. */
    PENDING,

    /** Reserva confirmada y vigente. */
    CONFIRMED,

    /** Reserva cancelada; se conserva el registro por trazabilidad. */
    CANCELLED;

    /** {@code true} si la reserva sigue viva y admite operaciones. */
    public boolean isActive() {
        return this != CANCELLED;
    }
}
