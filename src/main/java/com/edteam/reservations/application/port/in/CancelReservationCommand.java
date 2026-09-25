package com.edteam.reservations.application.port.in;

import com.edteam.reservations.domain.access.Actor;
import java.util.Objects;

/**
 * Pedido de cancelación de una reserva.
 *
 * <p>El {@code actor} no es un dato de auditoría: es parte del comando porque
 * sin él el caso de uso no puede decidir si la cancelación corresponde. Es la
 * diferencia entre autorizar en el controller —donde protege sólo a ese
 * adaptador— y autorizar en el caso de uso, donde protege la operación
 * cualquiera sea la puerta por la que entre.
 *
 * @param reservationId   reserva a cancelar
 * @param expectedVersion versión sobre la que trabajó el cliente ({@code If-Match})
 * @param actor           quién pide la cancelación
 */
public record CancelReservationCommand(long reservationId, long expectedVersion, Actor actor) {

    public CancelReservationCommand {
        Objects.requireNonNull(actor, "El solicitante es obligatorio");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion no puede ser negativa");
        }
    }
}
