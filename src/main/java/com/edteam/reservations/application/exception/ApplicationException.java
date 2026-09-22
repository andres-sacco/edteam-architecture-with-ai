package com.edteam.reservations.application.exception;

/**
 * Raíz de los errores de la capa de aplicación: casos que no son reglas del
 * agregado sino problemas de orquestación (la reserva no existe, un
 * aeropuerto no está en el maestro, otro proceso escribió primero).
 */
public abstract class ApplicationException extends RuntimeException {

    protected ApplicationException(String message) {
        super(message);
    }

    protected ApplicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
