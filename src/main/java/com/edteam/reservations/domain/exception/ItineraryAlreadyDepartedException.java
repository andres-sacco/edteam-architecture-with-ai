package com.edteam.reservations.domain.exception;

/**
 * La operación no es válida porque el itinerario ya arrancó.
 *
 * <p>Se toma como referencia la salida del primer segmento: una vez que el
 * pasajero voló el primer tramo, la reserva no se crea, ni se modifica, ni se
 * cancela por este canal.
 */
public class ItineraryAlreadyDepartedException extends DomainException {

    public ItineraryAlreadyDepartedException(String message) {
        super(message);
    }
}
