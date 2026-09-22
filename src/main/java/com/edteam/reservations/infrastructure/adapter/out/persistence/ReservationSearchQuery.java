package com.edteam.reservations.infrastructure.adapter.out.persistence;

import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.application.query.ReservationSearchCriteria;

import java.util.List;

/**
 * Las dos consultas que resuelven el listado de reservas.
 *
 * <p>Es una interfaz interna del adaptador de persistencia, no un puerto: la
 * aplicación no la conoce y {@link ReservationRepositoryPort} no cambió de
 * firma. Existe sólo para poder decorar el conteo con un cache sin que
 * {@link ReservationPersistenceAdapter} se entere, igual que
 * {@code CachingAirportCatalog} decora al maestro de aeropuertos.
 *
 * @see CachingReservationSearchQuery
 */
public interface ReservationSearchQuery {

    /** Cantidad total de reservas que cumplen el filtro, ignorando la paginación. */
    long count(ReservationSearchCriteria criteria);

    /** Ids de la página pedida, ya en el orden del criterio. */
    List<Long> findPageOfIds(ReservationSearchCriteria criteria);
}
