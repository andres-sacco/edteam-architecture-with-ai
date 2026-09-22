package com.edteam.reservations.application.port.out;

import com.edteam.reservations.application.exception.NotificationDeliveryException;
import com.edteam.reservations.domain.event.DomainEvent;

/**
 * Puerto de salida hacia el sistema externo de notificaciones.
 *
 * <p>Ningún caso de uso llama a este puerto de forma directa: lo invoca el
 * despachador del outbox, por fuera de la operación del usuario. De esa manera
 * una caída del sistema de notificaciones no afecta la disponibilidad de las
 * reservas.
 */
public interface NotificationPort {

    /**
     * Envía la notificación correspondiente al evento.
     *
     * <p>La implementación debe ser tolerante a reintentos: el mismo evento
     * puede llegar más de una vez (entrega <em>at-least-once</em>), por lo que
     * conviene propagar el id del evento como clave de idempotencia.
     *
     * @throws NotificationDeliveryException si el envío falla y hay que reintentar
     */
    void notify(DomainEvent event);
}
