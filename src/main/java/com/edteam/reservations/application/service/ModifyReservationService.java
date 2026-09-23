package com.edteam.reservations.application.service;

import com.edteam.reservations.application.audit.AuditAction;
import com.edteam.reservations.application.audit.AuditEntry;
import com.edteam.reservations.application.exception.ConcurrentUpdateException;
import com.edteam.reservations.application.exception.ReservationNotFoundException;
import com.edteam.reservations.application.port.in.ModifyReservationCommand;
import com.edteam.reservations.application.port.in.ModifyReservationUseCase;
import com.edteam.reservations.application.port.out.AuditTrailPort;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.domain.access.ReservationAccessPolicy;
import com.edteam.reservations.domain.event.ReservationModified;
import com.edteam.reservations.domain.model.Itinerary;
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

/** Reemplazo del itinerario de una reserva vigente. */
@Service
public class ModifyReservationService implements ModifyReservationUseCase {

    private static final Logger log = LoggerFactory.getLogger(ModifyReservationService.class);

    private final ReservationRepositoryPort reservationRepository;
    private final ItineraryAssembler itineraryAssembler;
    private final AirportExistenceValidator airportValidator;
    private final EventOutboxPort eventOutbox;
    private final AuditTrailPort auditTrail;
    private final Clock clock;

    public ModifyReservationService(ReservationRepositoryPort reservationRepository,
                                    ItineraryAssembler itineraryAssembler,
                                    AirportExistenceValidator airportValidator,
                                    EventOutboxPort eventOutbox,
                                    AuditTrailPort auditTrail,
                                    Clock clock) {
        this.reservationRepository = Objects.requireNonNull(reservationRepository);
        this.itineraryAssembler = Objects.requireNonNull(itineraryAssembler);
        this.airportValidator = Objects.requireNonNull(airportValidator);
        this.eventOutbox = Objects.requireNonNull(eventOutbox);
        this.auditTrail = Objects.requireNonNull(auditTrail);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public Reservation modify(ModifyReservationCommand command) {
        Objects.requireNonNull(command, "El comando es obligatorio");
        ReservationId reservationId = ReservationId.of(command.reservationId());
        Instant now = clock.instant();

        Reservation current = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        // Primero quién, después todo lo demás: sin esto, un pedido sobre una
        // reserva ajena llegaba a consultar el catálogo de aeropuertos y a
        // devolver un 409 con la versión real del recurso.
        if (!ReservationAccessPolicy.canWrite(command.actor(), current)) {
            auditTrail.record(AuditEntry.denied(AuditAction.RESERVATION_ACCESS_DENIED,
                    command.actor().email(), reservationId.toString(), now));
            throw new ReservationNotFoundException(reservationId);
        }

        // Corte temprano: si el cliente trabajó sobre una versión vieja, se
        // rechaza antes de validar aeropuertos y de intentar escribir.
        if (current.version() != command.expectedVersion()) {
            throw new ConcurrentUpdateException(reservationId, command.expectedVersion(), current.version());
        }

        Itinerary newItinerary = itineraryAssembler.toItinerary(command.newItinerary());
        airportValidator.validate(newItinerary);

        Itinerary previousItinerary = current.itinerary();
        Reservation modified = current.changeItinerary(newItinerary, now);
        Reservation saved = reservationRepository.save(modified);

        eventOutbox.enqueue(List.of(ReservationModified.of(saved, previousItinerary)));
        auditTrail.record(AuditEntry.allowed(AuditAction.RESERVATION_MODIFIED,
                command.actor().email(), saved.requireId().toString(), saved.version(), now));

        log.info("Reserva modificada id={} itinerario={}-{} version={}",
                saved.requireId(), saved.itinerary().origin(), saved.itinerary().destination(), saved.version());
        return saved;
    }
}
