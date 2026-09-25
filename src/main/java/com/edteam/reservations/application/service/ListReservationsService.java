package com.edteam.reservations.application.service;

import com.edteam.reservations.application.port.in.ListReservationsQuery;
import com.edteam.reservations.application.port.in.ListReservationsUseCase;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.application.query.ReservationSearchCriteria;
import com.edteam.reservations.application.query.ResultPage;
import com.edteam.reservations.domain.access.ReservationAccessPolicy;
import com.edteam.reservations.domain.model.Email;
import com.edteam.reservations.domain.model.Reservation;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listado paginado de reservas.
 *
 * <p>El cambio de fondo está en una línea: el filtro por usuario ya no es lo
 * que mandó el cliente sino lo que la política le deja ver. Antes, un
 * {@code GET /v1/reservations} sin parámetros devolvía las reservas de todos
 * de a 100 por página, que es la base entera —con los documentos de los
 * pasajeros— en unos minutos de scraping.
 */
@Service
public class ListReservationsService implements ListReservationsUseCase {

    private final ReservationRepositoryPort reservationRepository;

    public ListReservationsService(ReservationRepositoryPort reservationRepository) {
        this.reservationRepository = Objects.requireNonNull(reservationRepository);
    }

    @Override
    @Transactional(readOnly = true, timeout = 2)
    public ResultPage<Reservation> list(ListReservationsQuery query) {
        Objects.requireNonNull(query, "El pedido es obligatorio");

        ReservationSearchCriteria requested = query.criteria();
        Optional<Email> owner = ReservationAccessPolicy.ownerFilterFor(query.actor(), requested.userEmail());

        return reservationRepository.search(requested.restrictedTo(owner));
    }
}
