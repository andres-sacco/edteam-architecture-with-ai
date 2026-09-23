package com.edteam.reservations.application.notification;

import java.time.Instant;
import java.util.Objects;

/**
 * Notificación emitida a partir de un hecho recibido.
 *
 * <p>Deliberadamente pobre en datos. Lleva el {@code messageId} —para poder
 * atarla al mensaje que la produjo y para que un duplicado choque contra un
 * {@code UNIQUE} en lugar de convertirse en un segundo aviso—, la reserva, el
 * destinatario por id interno y el orden.
 *
 * <p>Lo que <b>no</b> lleva, y no es negociable: email, nombre, documento del
 * pasajero ni datos de pago. La ruta y la fecha de viaje viajan en el mensaje
 * porque sin eso no se puede redactar nada, pero no hace falta guardarlas en el
 * registro de entrega.
 *
 * @param messageId  clave de idempotencia del mensaje que la originó
 * @param type       tipo del hecho
 * @param subject    id de la reserva
 * @param userId     destinatario, por id interno
 * @param sequence   orden del hecho dentro de la reserva
 * @param occurredAt cuándo ocurrió el hecho (no cuándo se entregó)
 */
public record NotificationDelivery(String messageId,
                                   String type,
                                   String subject,
                                   String userId,
                                   long sequence,
                                   Instant occurredAt) {

    public NotificationDelivery {
        Objects.requireNonNull(messageId, "messageId es obligatorio");
        Objects.requireNonNull(type, "type es obligatorio");
        Objects.requireNonNull(subject, "subject es obligatorio");
        Objects.requireNonNull(userId, "userId es obligatorio");
        Objects.requireNonNull(occurredAt, "occurredAt es obligatorio");
    }
}
