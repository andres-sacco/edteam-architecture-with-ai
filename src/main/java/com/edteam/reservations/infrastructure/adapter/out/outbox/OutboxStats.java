package com.edteam.reservations.infrastructure.adapter.out.outbox;

import java.time.Duration;

/**
 * Foto del outbox para las métricas.
 *
 * <p>Las tres preguntas operativas en un solo viaje a la base: cuántos hay
 * esperando, hace cuánto está trabado el más viejo y cuántos quedaron muertos.
 * Se leen juntas porque se consultan juntas, y en una sola consulta para no
 * pagar tres por cada raspado del recolector de métricas.
 *
 * @param pending  mensajes pendientes o reclamados
 * @param dead     mensajes en la dead letter del productor. Alerta con &gt; 0
 * @param lag      {@code now - min(enqueued_at)} de los pendientes: cuánto
 *                 tarda una notificación desde que el hecho ocurrió. Es el
 *                 número que importa, mucho más que el conteo
 * @param dispatched despachados todavía no purgados
 */
public record OutboxStats(long pending, long dead, Duration lag, long dispatched) {

    public static final OutboxStats EMPTY = new OutboxStats(0, 0, Duration.ZERO, 0);
}
