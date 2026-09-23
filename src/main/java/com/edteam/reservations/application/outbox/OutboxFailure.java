package com.edteam.reservations.application.outbox;

/**
 * Naturaleza de un fallo de publicación: decide si el mensaje se reintenta o
 * se da por muerto.
 *
 * <p>La clasificación la hace el <em>adaptador</em>, no el despachador: el
 * único que sabe si el {@code 503} es del broker o si el payload no serializa
 * es quien habla con el broker. El despachador sólo traduce la excepción que
 * recibió a uno de estos dos valores.
 *
 * <p>Sin esta distinción los dos casos recorren el mismo camino y los dos
 * duelen: un mensaje venenoso quema todos los intentos por nada, y un broker
 * caído diez minutos manda a la dead letter mensajes cuyo fallo era 100 %
 * recuperable.
 */
public enum OutboxFailure {

    /** El destino puede volver: se reintenta con backoff exponencial y jitter. */
    TRANSIENT,

    /**
     * Insistir no va a cambiar el resultado (payload que no serializa, tipo
     * desconocido, mensaje no ruteable por falta de binding). Va a la dead
     * letter en el primer intento.
     */
    PERMANENT
}
