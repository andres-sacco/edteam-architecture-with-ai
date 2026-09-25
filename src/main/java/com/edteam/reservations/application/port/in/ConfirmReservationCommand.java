package com.edteam.reservations.application.port.in;

import com.edteam.reservations.domain.access.Actor;
import java.util.Objects;

/**
 * Pedido de confirmación de una reserva.
 *
 * <p>El caso de uso no está expuesto por HTTP todavía (ver «Fuera de alcance»
 * en el README), pero lleva el solicitante igual: la autorización es del caso
 * de uso, así que tiene que estar resuelta antes de que exista el endpoint y
 * no como parte de publicarlo.
 *
 * @param reservationId   reserva a confirmar
 * @param expectedVersion versión sobre la que trabajó el cliente
 * @param actor           quién pide la confirmación
 */
public record ConfirmReservationCommand(long reservationId, long expectedVersion, Actor actor) {

    public ConfirmReservationCommand {
        Objects.requireNonNull(actor, "El solicitante es obligatorio");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion no puede ser negativa");
        }
    }
}
