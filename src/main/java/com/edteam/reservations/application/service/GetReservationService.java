package com.edteam.reservations.application.service;

import com.edteam.reservations.application.exception.ReservationNotFoundException;
import com.edteam.reservations.application.port.in.GetReservationUseCase;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Consulta de una reserva por id. */
@Service
public class GetReservationService implements GetReservationUseCase {

    private final ReservationRepositoryPort reservationRepository;

    public GetReservationService(ReservationRepositoryPort reservationRepository) {
        this.reservationRepository = Objects.requireNonNull(reservationRepository);
    }

    @Override
    @Transactional(readOnly = true)
    public Reservation getById(ReservationId reservationId) {
        Objects.requireNonNull(reservationId, "El id es obligatorio");
        return reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
    }
}
