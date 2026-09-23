package com.edteam.reservations.application.exception;

/**
 * El mensaje recibido no se puede procesar y reintentarlo no va a cambiarlo:
 * tipo desconocido, versión de esquema que este consumidor no entiende, campos
 * obligatorios ausentes, hecho demasiado viejo para notificar.
 *
 * <p>Es el par de {@link EventPublishException} del lado de entrada: lo que
 * llega como esta excepción va directo a la dead-letter queue sin gastar
 * reintentos, y cualquier otro fallo se trata como transitorio.
 */
public class UnprocessableEventException extends ApplicationException {

    public UnprocessableEventException(String message) {
        super(message);
    }

    public UnprocessableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
