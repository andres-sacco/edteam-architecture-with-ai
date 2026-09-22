package com.edteam.reservations.application.service;

import com.edteam.reservations.application.port.in.ListReservationsUseCase;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.application.query.ReservationSearchCriteria;
import com.edteam.reservations.application.query.ResultPage;
import com.edteam.reservations.domain.model.Reservation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Listado paginado de reservas.
 *
 * <p>Es un caso de uso de lectura y por eso es delgado: no hay regla de negocio
 * que aplicar sobre un listado. Existe igual, en lugar de dejar que el
 * controller consulte el repositorio, porque es el puerto de entrada lo que el
 * adaptador conoce; si mañana el listado tiene que filtrar por permisos del
 * usuario o resolver visibilidad de reservas de terceros, la regla entra acá y
 * no en el controller.
 *
 * <p>La transacción es de sólo lectura: le avisa al proveedor que no hace falta
 * <em>dirty checking</em> ni flush, y en PostgreSQL permite que la consulta
 * vaya a una réplica si mañana se agrega una.
 */
@Service
public class ListReservationsService implements ListReservationsUseCase {

    private final ReservationRepositoryPort reservationRepository;

    public ListReservationsService(ReservationRepositoryPort reservationRepository) {
        this.reservationRepository = Objects.requireNonNull(reservationRepository);
    }

    @Override
    @Transactional(readOnly = true)
    public ResultPage<Reservation> list(ReservationSearchCriteria criteria) {
        Objects.requireNonNull(criteria, "El criterio de búsqueda es obligatorio");
        return reservationRepository.search(criteria);
    }
}
