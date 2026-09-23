package com.edteam.reservations.application.service;

import com.edteam.reservations.application.audit.AuditAction;
import com.edteam.reservations.application.audit.AuditEntry;
import com.edteam.reservations.application.exception.ConcurrentUpdateException;
import com.edteam.reservations.application.exception.ReservationNotFoundException;
import com.edteam.reservations.application.port.in.ConfirmReservationCommand;
import com.edteam.reservations.application.port.in.ConfirmReservationUseCase;
import com.edteam.reservations.application.port.out.AuditTrailPort;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.domain.access.ReservationAccessPolicy;
import com.edteam.reservations.domain.event.ReservationConfirmed;
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

/** Confirmación de una reserva pendiente. */
@Service
public class ConfirmReservationService implements ConfirmReservationUseCase {

    private static final Logger log = LoggerFactory.getLogger(ConfirmReservationService.class);

    private final ReservationRepositoryPort reservationRepository;
    private final EventOutboxPort eventOutbox;
    private final AuditTrailPort auditTrail;
    private final Clock clock;

    public ConfirmReservationService(ReservationRepositoryPort reservationRepository,
                                     EventOutboxPort eventOutbox,
                                     AuditTrailPort auditTrail,
                                     Clock clock) {
        this.reservationRepository = Objects.requireNonNull(reservationRepository);
        this.eventOutbox = Objects.requireNonNull(eventOutbox);
        this.auditTrail = Objects.requireNonNull(auditTrail);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public Reservation confirm(ConfirmReservationCommand command) {
        Objects.requireNonNull(command, "El comando es obligatorio");
        ReservationId reservationId = ReservationId.of(command.reservationId());
        Instant now = clock.instant();

        Reservation current = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        if (!ReservationAccessPolicy.canWrite(command.actor(), current)) {
            auditTrail.record(AuditEntry.denied(AuditAction.RESERVATION_ACCESS_DENIED,
                    command.actor().email(), reservationId.toString(), now));
            throw new ReservationNotFoundException(reservationId);
        }

        if (current.version() != command.expectedVersion()) {
            throw new ConcurrentUpdateException(reservationId, command.expectedVersion(), current.version());
        }

        Reservation confirmed = current.confirm(now);
        Reservation saved = reservationRepository.save(confirmed);

        eventOutbox.enqueue(List.of(ReservationConfirmed.of(saved)));
        auditTrail.record(AuditEntry.allowed(AuditAction.RESERVATION_CONFIRMED,
                command.actor().email(), saved.requireId().toString(), saved.version(), now));

        log.info("Reserva confirmada id={} version={}", saved.requireId(), saved.version());
        return saved;
    }
}
