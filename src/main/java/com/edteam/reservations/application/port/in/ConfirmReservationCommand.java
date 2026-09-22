package com.edteam.reservations.application.port.in;

/** Datos de entrada para confirmar una reserva pendiente. */
public record ConfirmReservationCommand(long reservationId, long expectedVersion) {

    public ConfirmReservationCommand {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion no puede ser negativa");
        }
    }
}
