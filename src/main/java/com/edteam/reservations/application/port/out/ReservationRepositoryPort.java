package com.edteam.reservations.application.port.out;

import com.edteam.reservations.application.exception.ConcurrentUpdateException;
import com.edteam.reservations.application.exception.DuplicateReservationException;
import com.edteam.reservations.application.exception.UnknownUserException;
import com.edteam.reservations.application.query.ReservationSearchCriteria;
import com.edteam.reservations.application.query.ResultPage;
import com.edteam.reservations.domain.model.IdempotencyKey;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationId;
import com.edteam.reservations.domain.model.UserId;

import java.util.Optional;

/** Persistencia del agregado {@code Reservation}. */
public interface ReservationRepositoryPort {

    Optional<Reservation> findById(ReservationId reservationId);

    /**
     * Busca la reserva creada por <b>ese usuario</b> con esa clave de
     * idempotencia.
     *
     * <p>La clave sola no alcanza como criterio. Es un UUID que viaja en un
     * header, y un header queda en los logs de acceso de cualquier proxy del
     * camino, en las trazas de los SDK móviles y en el historial de las
     * herramientas de soporte. Con la búsqueda por clave a secas, cualquiera
     * que consiguiera una clave usada obtenía la reserva completa de su dueño
     * —con los documentos de los pasajeros— reenviándola en un alta. Alcanzando
     * la clave al usuario, una clave filtrada deja de servir desde otra
     * identidad: para el atacante es una clave nueva, y el alta que dispara es
     * la suya.
     *
     * @throws DuplicateReservationException nunca desde acá; ver {@link #save(Reservation)}
     */
    Optional<Reservation> findByIdempotencyKey(UserId owner, IdempotencyKey idempotencyKey);

    ResultPage<Reservation> search(ReservationSearchCriteria criteria);

    /**
     * @throws DuplicateReservationException si el par (usuario, clave de idempotencia) ya existe
     * @throws UnknownUserException          si el usuario de la reserva no está dado de alta
     * @throws ConcurrentUpdateException     si la versión almacenada cambió
     */
    Reservation save(Reservation reservation);
}
