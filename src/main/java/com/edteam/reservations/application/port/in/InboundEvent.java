package com.edteam.reservations.application.port.in;

import java.time.Instant;
import java.util.Objects;

/**
 * Hecho recibido del transporte, ya traducido a tipos propios.
 *
 * <p>Es la frontera del adaptador de entrada: el consumidor parsea el envelope
 * —JSON, propiedades AMQP, headers— y entrega esto. Del otro lado no hay
 * ninguna clase del broker ni de Jackson, que es lo que permite testear el caso
 * de uso sin levantar un broker y cambiar de transporte sin tocarlo.
 *
 * <p>No es un {@code DomainEvent}: es lo que <em>llegó</em>, que puede estar
 * duplicado, desordenado, viejo o mal formado. Convertirlo en un evento de
 * dominio sería afirmar que es válido antes de haberlo verificado.
 *
 * @param messageId     clave de idempotencia; es lo que hace posible deduplicar
 * @param type          tipo del hecho
 * @param schemaVersion versión mayor del esquema del payload
 * @param source        quién lo emitió; distingue entornos y evita que un
 *                      mensaje de staging se procese como productivo
 * @param subject       id de la reserva: el ámbito del orden
 * @param sequence      orden monotónico dentro de la reserva
 * @param userId        destinatario, por id interno
 * @param occurredAt    cuándo ocurrió el hecho
 * @param correlationId traza del pedido que lo originó; puede ser nulo
 */
public record InboundEvent(
        String messageId,
        String type,
        int schemaVersion,
        String source,
        String subject,
        long sequence,
        String userId,
        Instant occurredAt,
        String correlationId) {

    public InboundEvent {
        Objects.requireNonNull(messageId, "messageId es obligatorio");
        Objects.requireNonNull(type, "type es obligatorio");
        Objects.requireNonNull(subject, "subject es obligatorio");
        Objects.requireNonNull(userId, "userId es obligatorio");
        Objects.requireNonNull(occurredAt, "occurredAt es obligatorio");
    }
}
