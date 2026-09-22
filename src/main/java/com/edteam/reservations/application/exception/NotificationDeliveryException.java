package com.edteam.reservations.application.exception;

/**
 * Falló el envío al sistema externo de notificaciones.
 *
 * <p>Nunca llega al cliente de la API: la notificación se despacha fuera de la
 * operación de reserva, y el error sólo hace que el mensaje del outbox se
 * reintente.
 */
public class NotificationDeliveryException extends ApplicationException {

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }

    public NotificationDeliveryException(String message) {
        super(message);
    }
}
