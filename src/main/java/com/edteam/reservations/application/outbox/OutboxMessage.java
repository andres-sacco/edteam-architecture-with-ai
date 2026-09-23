package com.edteam.reservations.application.outbox;

import java.time.Instant;
import java.util.Objects;

/**
 * Hecho pendiente de publicar, tal como está guardado en el outbox.
 *
 * <p>Es el registro del patrón <em>transactional outbox</em>: el caso de uso
 * guarda la reserva y encola el evento en el mismo recurso transaccional, y un
 * proceso aparte lo publica. Así no se pierde una notificación por un fallo
 * del destino ni se notifica algo que después se rollbackeó.
 *
 * <h2>Por qué el payload viaja serializado y no como {@code DomainEvent}</h2>
 * El {@code data} del mensaje se serializa <b>al encolar</b>, dentro de la
 * transacción del caso de uso. Tres consecuencias, todas buscadas:
 * <ul>
 *   <li>lo que se publica es exactamente lo que pasó, aunque el código cambie
 *       entre el encolado y el despacho;</li>
 *   <li>el relay reenvía bytes y no necesita conocer los tipos de evento:
 *       agregar un quinto hecho no lo toca;</li>
 *   <li>no hay que deserializar una jerarquía sellada de vuelta desde la base.</li>
 * </ul>
 * Para la aplicación el {@code payload} es un JSON opaco. No arrastra ninguna
 * clase del broker ni de Jackson: es un {@code String}.
 *
 * @param id            clave de idempotencia del mensaje. Es la PK de la fila y
 *                      viaja en el envelope: es <b>estable entre reintentos</b>,
 *                      que es lo que permite al consumidor deduplicar
 * @param type          nombre estable del hecho; también la routing key
 * @param schemaVersion versión mayor del esquema del payload
 * @param subject       entidad a la que se refiere (id de la reserva). Es el
 *                      ámbito del orden
 * @param sequence      orden monotónico creciente, asignado por el almacenamiento
 * @param payload       el {@code data} del mensaje, ya serializado
 * @param correlationId traza del pedido que lo originó; puede ser nulo (el
 *                      evento pudo nacer de un job, no de un {@code POST})
 * @param occurredAt    cuándo ocurrió el hecho, no cuándo se envía
 * @param enqueuedAt    cuándo se encoló; con {@code now} da el lag del outbox
 * @param attempts      intentos de publicación ya realizados
 * @param status        estado actual
 */
public record OutboxMessage(String id,
                            String type,
                            int schemaVersion,
                            String subject,
                            long sequence,
                            String payload,
                            String correlationId,
                            Instant occurredAt,
                            Instant enqueuedAt,
                            int attempts,
                            OutboxStatus status) {

    public OutboxMessage {
        Objects.requireNonNull(id, "id es obligatorio");
        Objects.requireNonNull(type, "type es obligatorio");
        Objects.requireNonNull(subject, "subject es obligatorio");
        Objects.requireNonNull(payload, "payload es obligatorio");
        Objects.requireNonNull(occurredAt, "occurredAt es obligatorio");
        Objects.requireNonNull(enqueuedAt, "enqueuedAt es obligatorio");
        Objects.requireNonNull(status, "status es obligatorio");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion debe ser al menos 1");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts no puede ser negativo");
        }
    }
}
