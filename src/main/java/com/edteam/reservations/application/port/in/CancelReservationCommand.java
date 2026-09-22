package com.edteam.reservations.application.port.in;

/**
 * Datos de entrada para cancelar una reserva.
 *
 * <p>La cancelación es lógica: la reserva pasa a {@code CANCELADA} y se
 * conserva por trazabilidad y por las reglas comerciales (penalidades,
 * reintegros). No se borra el registro.
 */
public record CancelReservationCommand(long reservationId, long expectedVersion) {

    public CancelReservationCommand {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion no puede ser negativa");
        }
    }
}
