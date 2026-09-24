package com.edteam.reservations.application.exception;

/**
 * El publicador no intentó publicar: sabemos que el destino está caído.
 *
 * <p>Es la traducción, en el borde del adaptador, del rechazo de un circuito
 * abierto. La distinción importa y es todo el valor del circuito sobre el
 * outbox: un {@link EventPublishException} común es un intento que falló y
 * <strong>gasta uno de los intentos del mensaje</strong>; esto es un intento
 * que nunca ocurrió, así que el relay libera el mensaje sin tocar su contador
 * y lo deja exactamente donde estaba.
 *
 * <p>Sin esta distinción, una caída larga del broker consume el presupuesto de
 * reintentos de mensajes perfectamente recuperables y los manda a la dead
 * letter, que es lo que separa «la notificación llegó tarde» de «la
 * notificación hay que reenviarla a mano».
 */
public class EventPublisherUnavailableException extends EventPublishException {

    public EventPublisherUnavailableException(String message) {
        super(message);
    }

    public EventPublisherUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
