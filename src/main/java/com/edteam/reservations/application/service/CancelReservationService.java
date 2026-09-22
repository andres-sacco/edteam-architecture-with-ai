package com.edteam.reservations.application.service;

import com.edteam.reservations.application.exception.ConcurrentUpdateException;
import com.edteam.reservations.application.exception.ReservationNotFoundException;
import com.edteam.reservations.application.port.in.CancelReservationCommand;
import com.edteam.reservations.application.port.in.CancelReservationUseCase;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.domain.event.ReservationCancelled;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Orquesta la cancelación de una reserva.
 *
 * <p>La baja es lógica: el agregado pasa a {@code CANCELLED}. No se borra el
 * registro porque hace falta para trazabilidad, reintegros y reportes.
 */
@Service
public class CancelReservationService implements CancelReservationUseCase {

    private static final Logger log = LoggerFactory.getLogger(CancelReservationService.class);

    private final ReservationRepositoryPort reservationRepository;
    private final EventOutboxPort eventOutbox;
    private final Clock clock;

    public CancelReservationService(ReservationRepositoryPort reservationRepository,
                                    EventOutboxPort eventOutbox,
                                    Clock clock) {
        this.reservationRepository = Objects.requireNonNull(reservationRepository);
        this.eventOutbox = Objects.requireNonNull(eventOutbox);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public Reservation cancel(CancelReservationCommand command) {
        Objects.requireNonNull(command, "El comando es obligatorio");

        ReservationId reservationId = ReservationId.of(command.reservationId());
        Reservation current = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        if (current.version() != command.expectedVersion()) {
            throw new ConcurrentUpdateException(reservationId, command.expectedVersion(), current.version());
        }

        Reservation cancelled = current.cancel(clock.instant());
        Reservation saved = reservationRepository.save(cancelled);

        eventOutbox.enqueue(List.of(ReservationCancelled.of(saved)));

        log.info("Reserva cancelada id={} version={}", saved.requireId(), saved.version());
        return saved;
    }
}
