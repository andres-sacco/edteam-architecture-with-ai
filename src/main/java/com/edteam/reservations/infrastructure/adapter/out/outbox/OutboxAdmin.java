package com.edteam.reservations.infrastructure.adapter.out.outbox;

import java.time.Instant;
import java.util.List;

/**
 * Operaciones de gestión sobre el outbox: mirar la dead letter y devolverle
 * mensajes a la cola.
 *
 * <p>Va aparte de {@code EventOutboxPort} porque no es un puerto de la
 * aplicación: ningún caso de uso reencola mensajes a mano. Es la herramienta
 * del operador, la consume el endpoint del puerto de gestión y por eso vive en
 * infraestructura, del mismo lado que quien la usa.
 *
 * <p>Sin esto, {@code FAILED} es un estado terminal del que nadie se entera:
 * la notificación no sólo se perdió, no hay forma de saber que se perdió ni de
 * reenviarla después de arreglar la causa.
 */
public interface OutboxAdmin {

    /** Métricas del outbox en una sola consulta. */
    OutboxStats stats();

    /**
     * Mensajes en la dead letter, del más reciente al más viejo.
     *
     * <p>No devuelve el payload: lleva ruta y fecha de viaje atadas a un
     * usuario, y el listado de la dead letter se mira desde una consola. Para
     * ver el contenido hay que consultar la tabla, que es una decisión
     * deliberada y no un olvido.
     */
    List<DeadOutboxMessage> deadLetter(int limit);

    /**
     * Devuelve el mensaje a {@code PENDING} con los intentos en cero y
     * elegible de inmediato.
     *
     * @return {@code true} si había un mensaje muerto con ese id
     */
    boolean replay(String messageId);

    /** Reencola toda la dead letter. @return cuántos mensajes volvieron */
    int replayAll();

    /** Borra los despachados anteriores al límite. @return filas borradas */
    int purgeDispatchedBefore(Instant limit);

    /**
     * Mensaje muerto, en la forma en que lo muestra el endpoint de gestión.
     *
     * @param id         clave de idempotencia; es lo que se pasa al replay
     * @param type       tipo del hecho
     * @param subject    reserva a la que se refiere
     * @param attempts   intentos consumidos
     * @param enqueuedAt cuándo se encoló
     * @param failedAt   cuándo se dio por muerto
     * @param lastError  último error, recortado
     */
    record DeadOutboxMessage(String id,
                             String type,
                             String subject,
                             long sequence,
                             int attempts,
                             Instant enqueuedAt,
                             Instant failedAt,
                             String lastError) {
    }
}
