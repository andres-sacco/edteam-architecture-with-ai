package com.edteam.reservations.application.port.out;

import com.edteam.reservations.application.exception.ConcurrentUpdateException;
import com.edteam.reservations.application.exception.DuplicateReservationException;
import com.edteam.reservations.application.exception.UnknownUserException;
import com.edteam.reservations.application.query.ReservationSearchCriteria;
import com.edteam.reservations.application.query.ResultPage;
import com.edteam.reservations.domain.model.IdempotencyKey;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationId;

import java.util.Optional;

/**
 * Puerto de salida hacia el almacenamiento de reservas.
 *
 * <p>La interfaz vive en la capa de aplicación (la define quien la necesita) y
 * habla sólo en términos del dominio: no expone entidades JPA, {@code Page},
 * {@code Specification} ni nada del proveedor. Eso es lo que permite cambiar la
 * implementación sin tocar los casos de uso.
 */
public interface ReservationRepositoryPort {

    /** Trae la reserva con su itinerario y sus pasajeros ya resueltos. */
    Optional<Reservation> findById(ReservationId reservationId);

    /**
     * Busca por la clave de idempotencia del cliente.
     *
     * <p>Es la consulta que permite responder un reintento con la reserva ya
     * creada en lugar de generar un duplicado.
     */
    Optional<Reservation> findByIdempotencyKey(IdempotencyKey idempotencyKey);

    /**
     * Devuelve la página de reservas que cumple el criterio, con el itinerario
     * y los pasajeros ya resueltos.
     *
     * <p>El criterio y la página son tipos de la aplicación a propósito: los
     * equivalentes del proveedor ({@code Pageable}, {@code Page},
     * {@code Specification}) no cruzan el puerto. Traducirlos es parte del
     * trabajo del adaptador, igual que traducir las excepciones.
     *
     * <p>Pedir una página más allá del final devuelve una página vacía con el
     * total real, no un error.
     */
    ResultPage<Reservation> search(ReservationSearchCriteria criteria);

    /**
     * Persiste la reserva.
     *
     * <p>Contrato que debe cumplir toda implementación:
     * <ul>
     *   <li>Si la reserva no tiene id, la inserta: resuelve el itinerario y los
     *       pasajeros (reutilizando los que ya existan según su clave natural) y
     *       devuelve la reserva con el id, la versión y los ids asignados.</li>
     *   <li>Si tiene id, la actualiza validando que la versión almacenada
     *       coincida con {@link Reservation#version()}, e incrementándola.</li>
     *   <li>Si las versiones no coinciden, lanza {@link ConcurrentUpdateException}
     *       sin escribir nada.</li>
     *   <li>Si ya existe una reserva con la misma clave de idempotencia, lanza
     *       {@link DuplicateReservationException}.</li>
     *   <li>Si el usuario referenciado no existe, lanza {@link UnknownUserException}.</li>
     * </ul>
     *
     * @return la reserva tal como quedó almacenada
     */
    Reservation save(Reservation reservation);
}
