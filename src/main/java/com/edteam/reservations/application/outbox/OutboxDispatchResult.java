package com.edteam.reservations.application.outbox;

/**
 * Resultado de una corrida del despachador del outbox.
 *
 * @param dispatched mensajes enviados con éxito
 * @param failed     mensajes que fallaron y quedan para reintentar
 */
public record OutboxDispatchResult(int dispatched, int failed) {

    public static final OutboxDispatchResult EMPTY = new OutboxDispatchResult(0, 0);

    public int total() {
        return dispatched + failed;
    }
}
