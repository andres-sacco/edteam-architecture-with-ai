package com.edteam.reservations.application.outbox;

/**
 * Resultado de una corrida del despachador del outbox.
 *
 * @param dispatched mensajes publicados con éxito
 * @param failed     mensajes que fallaron; quedan para reintentar o en la dead letter
 * @param deferred   mensajes que no se intentaron porque un mensaje anterior de
 *                   la <b>misma reserva</b> falló en este lote. No son un fallo:
 *                   se devuelven al estado pendiente sin gastar un intento, para
 *                   que un «se canceló tu reserva» no salga antes que su alta
 */
public record OutboxDispatchResult(int dispatched, int failed, int deferred) {

    public static final OutboxDispatchResult EMPTY = new OutboxDispatchResult(0, 0, 0);

    public int total() {
        return dispatched + failed + deferred;
    }
}
