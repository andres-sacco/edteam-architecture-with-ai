package com.edteam.reservations.application.exception;

/**
 * Fallo <b>transitorio</b> publicando un evento: el destino puede volver, así
 * que el mensaje se reintenta con backoff.
 *
 * <p>El tipo de la excepción <em>es</em> la clasificación, y por eso importa:
 * el despachador reintenta lo que llega como {@code EventPublishException} y
 * manda a la dead letter, en el primer intento, cualquier otra cosa. Quien
 * decide es el adaptador, que es el único que sabe si el error fue del broker
 * o del payload.
 *
 * <p>Nunca llega al cliente de la API: la publicación ocurre fuera de la
 * operación de reserva.
 */
public class EventPublishException extends ApplicationException {

    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }

    public EventPublishException(String message) {
        super(message);
    }
}
