package com.edteam.reservations.application.port.in;

import com.edteam.reservations.domain.model.Reservation;

/** Puerto de entrada: crear una reserva. */
public interface CreateReservationUseCase {

    /**
     * Crea la reserva, o devuelve la que ya existe si la clave de idempotencia
     * fue usada antes. En ese caso no se vuelve a notificar.
     *
     * @throws com.edteam.reservations.application.exception.UnknownAirportException si algún aeropuerto no existe
     * @throws com.edteam.reservations.application.exception.UnknownUserException    si el usuario no existe
     * @throws com.edteam.reservations.domain.exception.DomainException              si los datos violan una regla de negocio
     */
    Reservation create(CreateReservationCommand command);
}
