package com.edteam.reservations.application.service;

import com.edteam.reservations.application.exception.ConcurrentUpdateException;
import com.edteam.reservations.application.exception.ReservationNotFoundException;
import com.edteam.reservations.application.port.in.ConfirmReservationCommand;
import com.edteam.reservations.application.port.in.ConfirmReservationUseCase;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.domain.event.ReservationConfirmed;
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
 * Orquesta la confirmación de una reserva pendiente.
 *
 * <p>Existe porque el modelo de datos define tres estados: sin esta transición,
 * {@code CONFIRMADA} sería un valor inalcanzable. Es el punto donde más adelante
 * se enganchará el resultado del pago.
 */
@Service
public class ConfirmReservationService implements ConfirmReservationUseCase {

    private static final Logger log = LoggerFactory.getLogger(ConfirmReservationService.class);

    private final ReservationRepositoryPort reservationRepository;
    private final EventOutboxPort eventOutbox;
    private final Clock clock;

    public ConfirmReservationService(ReservationRepositoryPort reservationRepository,
                                     EventOutboxPort eventOutbox,
                                     Clock clock) {
        this.reservationRepository = Objects.requireNonNull(reservationRepository);
        this.eventOutbox = Objects.requireNonNull(eventOutbox);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public Reservation confirm(ConfirmReservationCommand command) {
        Objects.requireNonNull(command, "El comando es obligatorio");

        ReservationId reservationId = ReservationId.of(command.reservationId());
        Reservation current = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        if (current.version() != command.expectedVersion()) {
            throw new ConcurrentUpdateException(reservationId, command.expectedVersion(), current.version());
        }

        Reservation confirmed = current.confirm(clock.instant());
        Reservation saved = reservationRepository.save(confirmed);

        eventOutbox.enqueue(List.of(ReservationConfirmed.of(saved)));

        log.info("Reserva confirmada id={} version={}", saved.requireId(), saved.version());
        return saved;
    }
}
