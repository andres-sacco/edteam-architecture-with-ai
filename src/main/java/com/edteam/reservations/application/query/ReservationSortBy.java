package com.edteam.reservations.application.query;

/**
 * Campos por los que se puede ordenar un listado de reservas.
 *
 * <p>Es un enum cerrado y no un nombre de campo libre: si el criterio de orden
 * llegara como texto desde el adaptador, cualquier cambio de nombre en la
 * persistencia se convertiría en un cambio de contrato, y un valor arbitrario
 * podría terminar en una cláusula SQL.
 */
public enum ReservationSortBy {

    /** Fecha de alta de la reserva. */
    CREATED_AT,

    /** Salida del primer tramo del itinerario. */
    FIRST_DEPARTURE_AT
}
