package com.edteam.reservations.infrastructure.adapter.out.messaging;

import com.edteam.reservations.application.outbox.OutboxMessage;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.util.Objects;

/**
 * El envelope del mensaje: el contrato serializado.
 *
 * <p>Vive en infraestructura porque es una forma de transporte, no un concepto
 * de negocio. Los eventos de dominio no cambian de forma según por dónde
 * salen: el {@code messageId}, el {@code sequence} y la versión de esquema se
 * arman acá a partir de la fila del outbox.
 *
 * <p>El cuerpo es el contrato; las propiedades AMQP son un <b>espejo</b> de los
 * mismos valores, para que las herramientas del broker y el deduplicador del
 * consumidor no tengan que parsear el cuerpo. Es lo que permite que mudarse a
 * otro broker no cambie una línea del contrato.
 *
 * @param messageId     <b>clave de idempotencia.</b> Es la PK de la fila del
 *                      outbox y es estable entre reintentos: republicar el
 *                      mismo mensaje no cambia el id. Sin esto el consumidor no
 *                      puede deduplicar nada, por bien escrito que esté
 * @param type          qué pasó. Es también la routing key
 * @param version       versión mayor del esquema del {@code data}
 * @param source        quién lo emitió. Distingue entornos y evita que un
 *                      mensaje de staging se procese como productivo
 * @param subject       entidad a la que se refiere. Es el ámbito del orden
 * @param sequence      <b>orden.</b> Monotónico creciente y salido de la base,
 *                      no de un reloj de pared que puede ir para atrás entre
 *                      instancias
 * @param occurredAt    cuándo pasó el hecho. <b>No</b> cuándo se envió
 * @param publishedAt   cuándo salió. La diferencia con {@code occurredAt} es el
 *                      lag del outbox
 * @param correlationId une el evento con los logs del pedido que lo originó
 * @param data          el payload, tal como se serializó al encolar
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "messageId",
    "type",
    "version",
    "source",
    "subject",
    "sequence",
    "occurredAt",
    "publishedAt",
    "correlationId",
    "data"
})
public record EventEnvelope(
        String messageId,
        String type,
        int version,
        String source,
        String subject,
        long sequence,
        Instant occurredAt,
        Instant publishedAt,
        String correlationId,
        com.fasterxml.jackson.databind.JsonNode data) {

    public EventEnvelope {
        Objects.requireNonNull(messageId, "messageId es obligatorio");
        Objects.requireNonNull(type, "type es obligatorio");
        Objects.requireNonNull(subject, "subject es obligatorio");
        Objects.requireNonNull(occurredAt, "occurredAt es obligatorio");
        Objects.requireNonNull(publishedAt, "publishedAt es obligatorio");
        Objects.requireNonNull(data, "data es obligatorio");
    }

    /**
     * Arma el envelope desde la fila del outbox.
     *
     * @param publishedAt reloj del relay, no del hecho
     */
    public static EventEnvelope from(
            OutboxMessage message, String source, Instant publishedAt, com.fasterxml.jackson.databind.JsonNode data) {
        return new EventEnvelope(
                message.id(),
                message.type(),
                message.schemaVersion(),
                source,
                message.subject(),
                message.sequence(),
                message.occurredAt(),
                publishedAt,
                message.correlationId(),
                data);
    }
}
