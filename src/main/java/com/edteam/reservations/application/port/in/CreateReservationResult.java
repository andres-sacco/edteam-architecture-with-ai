package com.edteam.reservations.application.port.in;

import com.edteam.reservations.domain.model.Reservation;
import java.util.Objects;

/**
 * Resultado de crear una reserva: la reserva y si esta invocación fue la que
 * la creó.
 *
 * <p>El caso de uso es idempotente, así que devolver sólo la reserva deja
 * ambiguo lo que el adaptador de entrada necesita saber: con la misma clave de
 * idempotencia, la primera llamada da de alta la reserva y las siguientes
 * devuelven la misma. Quien traduce eso a HTTP tiene que distinguir un
 * {@code 201 Created} de un {@code 200 OK} de reintento, y el único que puede
 * responderlo sin volver a consultar es el propio caso de uso.
 *
 * @param reservation la reserva, siempre persistida y con id asignado
 * @param created     {@code true} si esta invocación la dio de alta;
 *                    {@code false} si ya existía para esa clave
 */
public record CreateReservationResult(Reservation reservation, boolean created) {

    public CreateReservationResult {
        Objects.requireNonNull(reservation, "La reserva es obligatoria");
    }

    /** Alta efectiva: la reserva no existía. */
    public static CreateReservationResult created(Reservation reservation) {
        return new CreateReservationResult(reservation, true);
    }

    /** Reintento: ya había una reserva para esa clave de idempotencia. */
    public static CreateReservationResult alreadyExisted(Reservation reservation) {
        return new CreateReservationResult(reservation, false);
    }
}
