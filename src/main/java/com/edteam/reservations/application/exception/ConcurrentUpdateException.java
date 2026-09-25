package com.edteam.reservations.application.exception;

import com.edteam.reservations.domain.model.ReservationId;

/**
 * Otro proceso modificó la reserva entre la lectura y la escritura
 * (conflicto de optimistic locking).
 *
 * <p>Es el escenario esperable con muchos usuarios concurrentes operando sobre
 * la misma reserva: el cliente debe volver a leer y reintentar con la versión
 * actual. En REST se traduce a 409 Conflict.
 */
public class ConcurrentUpdateException extends ApplicationException {

    private final ReservationId reservationId;
    private final long expectedVersion;

    public ConcurrentUpdateException(ReservationId reservationId, long expectedVersion, long actualVersion) {
        super("La reserva %s fue modificada por otro proceso (versión esperada %d, actual %d)"
                .formatted(reservationId, expectedVersion, actualVersion));
        this.reservationId = reservationId;
        this.expectedVersion = expectedVersion;
    }

    public ConcurrentUpdateException(ReservationId reservationId, long expectedVersion, Throwable cause) {
        super(
                "La reserva %s fue modificada por otro proceso (versión esperada %d)"
                        .formatted(reservationId, expectedVersion),
                cause);
        this.reservationId = reservationId;
        this.expectedVersion = expectedVersion;
    }

    public ReservationId reservationId() {
        return reservationId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }
}
