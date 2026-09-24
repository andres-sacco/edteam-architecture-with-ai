package com.edteam.reservations.application.service;

import com.edteam.reservations.application.audit.AuditAction;
import com.edteam.reservations.application.audit.AuditEntry;
import com.edteam.reservations.application.exception.ConcurrentUpdateException;
import com.edteam.reservations.application.exception.ReservationNotFoundException;
import com.edteam.reservations.application.port.in.ModifyReservationCommand;
import com.edteam.reservations.application.port.out.AuditTrailPort;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.domain.event.ReservationModified;
import com.edteam.reservations.domain.model.Itinerary;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * La parte transaccional de la modificación: sólo base de datos.
 *
 * <p>Vuelve a leer la reserva y a verificar la versión dentro de la
 * transacción. No es una repetición gratuita de lo que ya hizo el caso de uso:
 * entre la lectura de las precondiciones y esta transacción hay una llamada
 * HTTP al maestro de aeropuertos, y en esa ventana otro pedido pudo modificar
 * la reserva. La verificación de acá es la que cuenta, y la del caso de uso
 * existe para cortar temprano y no gastar la llamada al catálogo.
 *
 * <p>La razón de tener la llamada afuera está en
 * {@link CreateReservationTransaction}: adentro, un catálogo lento retiene una
 * conexión del pool durante todos sus reintentos.
 */
@Service
class ModifyReservationTransaction {

    private static final Logger log = LoggerFactory.getLogger(ModifyReservationTransaction.class);

    private final ReservationRepositoryPort reservationRepository;
    private final EventOutboxPort eventOutbox;
    private final AuditTrailPort auditTrail;

    ModifyReservationTransaction(ReservationRepositoryPort reservationRepository,
                                 EventOutboxPort eventOutbox,
                                 AuditTrailPort auditTrail) {
        this.reservationRepository = Objects.requireNonNull(reservationRepository);
        this.eventOutbox = Objects.requireNonNull(eventOutbox);
        this.auditTrail = Objects.requireNonNull(auditTrail);
    }

    // timeout = 1: ver CreateReservationTransaction. El techo del conjunto,
    // que el statement_timeout por sentencia no cubre, y el renglón de
    // persistencia del presupuesto del pedido.
    @Transactional(timeout = 1)
    Reservation apply(ModifyReservationCommand command, Itinerary newItinerary, Instant now) {
        ReservationId reservationId = ReservationId.of(command.reservationId());

        Reservation current = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        if (current.version() != command.expectedVersion()) {
            throw new ConcurrentUpdateException(reservationId, command.expectedVersion(), current.version());
        }

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
