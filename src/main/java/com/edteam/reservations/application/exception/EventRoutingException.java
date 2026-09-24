package com.edteam.reservations.application.exception;

/**
 * El broker aceptó el mensaje y lo devolvió: no hay ninguna cola atada a esa
 * routing key.
 *
 * <p>Se separa de {@link EventPublishException} porque son dos fallas
 * distintas que el puerto antes no dejaba distinguir. Un {@code nack} o un
 * confirm que no llega son el broker teniendo un problema: transitorios, del
 * lado de él, y cuentan para el circuito. Un mensaje devuelto es un binding
 * que falta: un error de topología <em>nuestro</em>, permanente hasta que
 * alguien lo arregle, y que no puede abrir el circuito —frenaría la entrega de
 * todos los demás eventos, que estaban saliendo bien—.
 *
 * <p>Sigue siendo reintentable por el outbox: el binding puede aparecer. Lo
 * que no hace es contar como salud del broker.
 */
public class EventRoutingException extends EventPublishException {

    public EventRoutingException(String message) {
        super(message);
    }

    public EventRoutingException(String message, Throwable cause) {
        super(message, cause);
    }
}
