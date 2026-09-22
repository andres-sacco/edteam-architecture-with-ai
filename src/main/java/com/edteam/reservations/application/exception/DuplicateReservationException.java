package com.edteam.reservations.application.exception;

import com.edteam.reservations.domain.model.IdempotencyKey;

/**
 * Ya existe una reserva con esa clave de idempotencia.
 *
 * <p>La lanza el adaptador de persistencia cuando la base rechaza el
 * {@code INSERT} por el {@code UNIQUE} sobre {@code idempotency_key}. Pasa
 * cuando dos pedidos con la misma clave llegan a la vez: el que pierde la
 * carrera recibe esta excepción, y el caso de uso responde con la reserva que
 * ganó. Por eso no suele llegar al cliente.
 */
public class DuplicateReservationException extends ApplicationException {

    private final IdempotencyKey idempotencyKey;

    public DuplicateReservationException(IdempotencyKey idempotencyKey, Throwable cause) {
        super("Ya existe una reserva con la clave de idempotencia %s".formatted(idempotencyKey), cause);
        this.idempotencyKey = idempotencyKey;
    }

    public IdempotencyKey idempotencyKey() {
        return idempotencyKey;
    }
}
