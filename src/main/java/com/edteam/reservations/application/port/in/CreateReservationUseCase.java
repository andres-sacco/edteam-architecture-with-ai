package com.edteam.reservations.application.port.in;

/** Puerto de entrada: crear una reserva. */
public interface CreateReservationUseCase {

    /**
     * Crea la reserva, o devuelve la que ya existe si la clave de idempotencia
     * fue usada antes. En ese caso no se vuelve a notificar.
     *
     * <p>El resultado indica cuál de los dos casos ocurrió: el adaptador de
     * entrada lo necesita para distinguir un alta de un reintento, y acá se
     * sabe sin volver a consultar.
     *
     * @throws com.edteam.reservations.application.exception.UnknownAirportException   si algún aeropuerto no existe
     * @throws com.edteam.reservations.application.exception.UnknownUserException      si el usuario no existe
     * @throws com.edteam.reservations.application.exception.DuplicateReservationException
     *         si otro pedido con la misma clave ganó la carrera; el adaptador debe reintentar una vez
     * @throws com.edteam.reservations.domain.exception.DomainException                si los datos violan una regla de negocio
     */
    CreateReservationResult create(CreateReservationCommand command);
}
