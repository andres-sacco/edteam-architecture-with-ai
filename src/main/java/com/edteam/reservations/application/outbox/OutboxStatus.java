package com.edteam.reservations.application.outbox;

/** Estado de un mensaje del outbox. */
public enum OutboxStatus {

    /** Pendiente de envío (o a reintentar). */
    PENDING,

    /** Enviado con éxito al sistema de notificaciones. */
    DISPATCHED,

    /** Agotó los reintentos; requiere intervención (dead letter). */
    FAILED
}
