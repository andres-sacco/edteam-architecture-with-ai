package com.edteam.reservations.application.port.out;

import com.edteam.reservations.application.outbox.OutboxMessage;
import com.edteam.reservations.domain.event.DomainEvent;

import java.util.Collection;
import java.util.List;

/**
 * Puerto de salida hacia el almacenamiento del outbox de eventos.
 *
 * <p>Separado de {@link NotificationPort} a propósito: uno guarda el evento
 * (rápido, local, transaccional) y el otro lo envía afuera (lento, remoto,
 * falible). Esa separación es la que desacopla las reservas del sistema de
 * notificaciones.
 */
public interface EventOutboxPort {

    /**
     * Encola los eventos para despacharlos más tarde.
     *
     * <p>Debe ejecutarse en la misma transacción que la escritura de la reserva:
     * si la reserva no se guarda, el evento tampoco.
     */
    void enqueue(Collection<DomainEvent> events);

    /**
     * Toma hasta {@code maxMessages} mensajes pendientes para despachar.
     *
     * <p>Con varias instancias de la aplicación corriendo en paralelo, la
     * implementación debe garantizar que un mismo mensaje no se entregue a dos
     * despachadores a la vez (por ejemplo, con {@code SELECT ... FOR UPDATE
     * SKIP LOCKED} en PostgreSQL).
     */
    List<OutboxMessage> pollPending(int maxMessages);

    /** Marca el mensaje como enviado con éxito. */
    void markDispatched(String messageId);

    /**
     * Registra un intento fallido. La implementación incrementa el contador de
     * intentos y, al superar el máximo configurado, deja el mensaje en
     * {@code FAILED} para que no se reintente indefinidamente.
     */
    void markFailed(String messageId, String error);
}
