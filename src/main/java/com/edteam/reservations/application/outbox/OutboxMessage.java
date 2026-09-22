package com.edteam.reservations.application.outbox;

import com.edteam.reservations.domain.event.DomainEvent;

import java.time.Instant;
import java.util.Objects;

/**
 * Evento pendiente de enviar al sistema de notificaciones.
 *
 * <p>Es el registro del patrón <em>transactional outbox</em>: el caso de uso
 * guarda la reserva y encola el evento en el mismo recurso transaccional, y un
 * proceso aparte lo despacha. Así no se pierde una notificación por un fallo
 * del sistema externo ni se notifica algo que después se rollbackeó.
 *
 * @param id       identificador del mensaje, generado por el almacenamiento
 * @param event    evento de dominio a notificar
 * @param attempts intentos de envío ya realizados
 * @param status   estado actual del mensaje
 */
public record OutboxMessage(String id, DomainEvent event, int attempts, OutboxStatus status, Instant enqueuedAt) {

    public OutboxMessage {
        Objects.requireNonNull(id, "id es obligatorio");
        Objects.requireNonNull(event, "event es obligatorio");
        Objects.requireNonNull(status, "status es obligatorio");
        Objects.requireNonNull(enqueuedAt, "enqueuedAt es obligatorio");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts no puede ser negativo");
        }
    }
}
