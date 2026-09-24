package com.edteam.reservations.application.service;

import com.edteam.reservations.application.audit.AuditAction;
import com.edteam.reservations.application.audit.AuditEntry;
import com.edteam.reservations.application.exception.ConcurrentUpdateException;
import com.edteam.reservations.application.exception.ReservationNotFoundException;
import com.edteam.reservations.application.port.in.CancelReservationCommand;
import com.edteam.reservations.application.port.in.CancelReservationUseCase;
import com.edteam.reservations.application.port.out.AuditTrailPort;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.domain.access.ReservationAccessPolicy;
import com.edteam.reservations.domain.event.ReservationCancelled;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Cancelación de una reserva.
 *
 * <p>Era la operación más barata de abusar: {@code DELETE} sólo pedía un
 * {@code If-Match}, y el {@code ETag} se conseguía del {@code GET} anónimo.
 * Dos pedidos cancelaban la reserva de cualquiera, y con las aerolíneas
 * integradas esa cancelación es irreversible.
 */
@Service
public class CancelReservationService implements CancelReservationUseCase {

    private static final Logger log = LoggerFactory.getLogger(CancelReservationService.class);

    private final ReservationRepositoryPort reservationRepository;
    private final EventOutboxPort eventOutbox;
    private final AuditTrailPort auditTrail;
    private final Clock clock;

    public CancelReservationService(ReservationRepositoryPort reservationRepository,
                                    EventOutboxPort eventOutbox,
                                    AuditTrailPort auditTrail,
                                    Clock clock) {
        this.reservationRepository = Objects.requireNonNull(reservationRepository);
        this.eventOutbox = Objects.requireNonNull(eventOutbox);
        this.auditTrail = Objects.requireNonNull(auditTrail);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional(timeout = 2)
    public Reservation cancel(CancelReservationCommand command) {
        Objects.requireNonNull(command, "El comando es obligatorio");
        ReservationId reservationId = ReservationId.of(command.reservationId());
        Instant now = clock.instant();

        Reservation current = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        // Autorización antes que concurrencia: un 409 sobre una reserva ajena
        // ya confirmaría que existe y qué versión tiene.
        if (!ReservationAccessPolicy.canWrite(command.actor(), current)) {
            auditTrail.record(AuditEntry.denied(AuditAction.RESERVATION_ACCESS_DENIED,
                    command.actor().email(), reservationId.toString(), now));
            throw new ReservationNotFoundException(reservationId);
        }

        if (current.version() != command.expectedVersion()) {
            throw new ConcurrentUpdateException(reservationId, command.expectedVersion(), current.version());
        }

        Reservation cancelled = current.cancel(now);
        Reservation saved = reservationRepository.save(cancelled);

        eventOutbox.enqueue(List.of(ReservationCancelled.of(saved)));
        auditTrail.record(AuditEntry.allowed(AuditAction.RESERVATION_CANCELLED,
                command.actor().email(), saved.requireId().toString(), saved.version(), now));

        log.info("Reserva cancelada id={} version={}", saved.requireId(), saved.version());
        return saved;
    }
}
