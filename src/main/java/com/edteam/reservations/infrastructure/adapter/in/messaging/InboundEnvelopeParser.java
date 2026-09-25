package com.edteam.reservations.infrastructure.adapter.in.messaging;

import com.edteam.reservations.application.exception.UnprocessableEventException;
import com.edteam.reservations.application.port.in.InboundEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import org.springframework.amqp.core.Message;

/**
 * Mensaje AMQP → {@link InboundEvent}.
 *
 * <p>Toda la deserialización vive acá, en el borde, y del otro lado del
 * {@code parse} no hay ninguna clase de Jackson ni del broker. Es lo que
 * permite testear la lógica del consumo sin levantar un broker.
 *
 * <h2>Lector tolerante</h2>
 * Ignora los campos que no conoce y no falla por un opcional ausente. Es la
 * regla que hace que agregar un campo al envelope o al {@code data} sea un
 * cambio compatible: un consumidor estricto convierte cada agregado del
 * productor en una caída coordinada.
 *
 * <p>Lo que <b>sí</b> es obligatorio son los cinco campos sin los que no se
 * puede hacer nada: {@code messageId} (no se puede deduplicar), {@code type}
 * (no se sabe qué pasó), {@code subject} (no se sabe de qué reserva),
 * {@code data.userId} (no se sabe a quién notificar) y {@code occurredAt} (no
 * se puede decidir si el hecho todavía vale la pena). Su ausencia es un fallo
 * permanente, no algo que reintentar.
 *
 * <p>El cuerpo es el contrato; las propiedades AMQP son un espejo. Se lee el
 * cuerpo y se cae a las propiedades sólo para lo que puede faltar, que es
 * exactamente el orden de precedencia que fija el contrato.
 */
public class InboundEnvelopeParser {

    private final ObjectMapper objectMapper;

    public InboundEnvelopeParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public InboundEvent parse(Message message) {
        Objects.requireNonNull(message, "El mensaje es obligatorio");

        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message.getBody());
        } catch (Exception e) {
            // Un cuerpo que no es JSON no se arregla insistiendo.
            throw new UnprocessableEventException("El cuerpo del mensaje no es JSON válido", e);
        }
        if (!envelope.isObject()) {
            throw new UnprocessableEventException("El cuerpo del mensaje no es un objeto JSON");
        }

        JsonNode data = envelope.path("data");
        String messageId =
                required(envelope, "messageId", message.getMessageProperties().getMessageId());
        String type = required(envelope, "type", message.getMessageProperties().getType());
        String subject = required(envelope, "subject", null);
        String userId = text(data, "userId");
        if (userId == null) {
            throw new UnprocessableEventException(
                    "El mensaje %s no trae 'data.userId': no hay a quién notificar".formatted(messageId));
        }

        return new InboundEvent(
                messageId,
                type,
                envelope.path("version").asInt(1),
                envelope.path("source").asText(""),
                subject,
                envelope.path("sequence").asLong(-1L),
                userId,
                instant(envelope, messageId),
                // Opcional: el hecho pudo nacer de un job y no de un pedido HTTP.
                text(envelope, "correlationId"));
    }

    private static Instant instant(JsonNode envelope, String messageId) {
        String value = text(envelope, "occurredAt");
        if (value == null) {
            throw new UnprocessableEventException("El mensaje %s no trae 'occurredAt'".formatted(messageId));
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new UnprocessableEventException(
                    "El 'occurredAt' del mensaje %s no es RFC 3339: '%s'".formatted(messageId, value), e);
        }
    }

    private static String required(JsonNode envelope, String field, String fallback) {
        String value = text(envelope, field);
        if (value != null) {
            return value;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        throw new UnprocessableEventException("El mensaje no trae '%s'".formatted(field));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }
}
