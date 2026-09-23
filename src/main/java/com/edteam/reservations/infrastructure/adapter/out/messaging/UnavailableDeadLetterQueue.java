package com.edteam.reservations.infrastructure.adapter.out.messaging;

import java.util.List;

/**
 * Dead letter del consumidor cuando no hay broker.
 *
 * <p>Devuelve {@code -1} y no {@code 0} a propósito: un cero diría «no hay
 * mensajes muertos», que es una afirmación que este objeto no puede hacer. Lo
 * que dice es «no sé», y esa diferencia importa en un tablero con una alerta
 * en {@code > 0}.
 */
public class UnavailableDeadLetterQueue implements DeadLetterQueue {

    @Override
    public long depth() {
        return -1L;
    }

    @Override
    public List<DeadLetter> peek(int limit) {
        return List.of();
    }

    @Override
    public int replay(int max) {
        throw new IllegalStateException(
                "La mensajería está apagada (reservations.messaging.enabled=false): no hay DLQ que reprocesar");
    }
}
