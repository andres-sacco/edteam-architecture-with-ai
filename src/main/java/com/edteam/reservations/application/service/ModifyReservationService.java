package com.edteam.reservations.application.service;

import com.edteam.reservations.application.audit.AuditAction;
import com.edteam.reservations.application.audit.AuditEntry;
import com.edteam.reservations.application.exception.ConcurrentUpdateException;
import com.edteam.reservations.application.exception.ReservationNotFoundException;
import com.edteam.reservations.application.port.in.ModifyReservationCommand;
import com.edteam.reservations.application.port.in.ModifyReservationUseCase;
import com.edteam.reservations.application.port.out.AuditTrailPort;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.domain.access.ReservationAccessPolicy;
import com.edteam.reservations.domain.model.Itinerary;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationId;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Reemplazo del itinerario de una reserva vigente.
 *
 * <p><b>No es transaccional.</b> Las precondiciones se verifican primero, la
 * llamada al maestro de aeropuertos ocurre después y la transacción se abre al
 * final, en {@link ModifyReservationTransaction#apply}: así una degradación del
 * catálogo no retiene conexiones del pool y no se convierte en una caída de
 * toda la API. La verificación de versión se repite adentro de la transacción,
 * que es donde cuenta.
 */
@Service
public class ModifyReservationService implements ModifyReservationUseCase {

    private final ReservationRepositoryPort reservationRepository;
    private final ItineraryAssembler itineraryAssembler;
    private final AirportExistenceValidator airportValidator;
    private final ModifyReservationTransaction transaction;
    private final AuditTrailPort auditTrail;
    private final Clock clock;

    ModifyReservationService(
            ReservationRepositoryPort reservationRepository,
            ItineraryAssembler itineraryAssembler,
            AirportExistenceValidator airportValidator,
            ModifyReservationTransaction transaction,
            AuditTrailPort auditTrail,
            Clock clock) {
        this.reservationRepository = Objects.requireNonNull(reservationRepository);
        this.itineraryAssembler = Objects.requireNonNull(itineraryAssembler);
        this.airportValidator = Objects.requireNonNull(airportValidator);
        this.transaction = Objects.requireNonNull(transaction);
        this.auditTrail = Objects.requireNonNull(auditTrail);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Reservation modify(ModifyReservationCommand command) {
        Objects.requireNonNull(command, "El comando es obligatorio");
        ReservationId reservationId = ReservationId.of(command.reservationId());
        Instant now = clock.instant();

        Reservation current = reservationRepository
                .findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        // Primero quién, después todo lo demás: sin esto, un pedido sobre una
        // reserva ajena llegaba a consultar el catálogo de aeropuertos y a
        // devolver un 409 con la versión real del recurso.
        //
        // El registro del rechazo se escribe en su propia transacción —lo
        // resuelve el adaptador de auditoría—, así que no depende de que acá
        // haya una abierta.
        if (!ReservationAccessPolicy.canWrite(command.actor(), current)) {
            auditTrail.record(AuditEntry.denied(
                    AuditAction.RESERVATION_ACCESS_DENIED, command.actor().email(), reservationId.toString(), now));
            throw new ReservationNotFoundException(reservationId);
        }

        // Corte temprano: si el cliente trabajó sobre una versión vieja, se
        // rechaza antes de validar aeropuertos y de intentar escribir.
        if (current.version() != command.expectedVersion()) {
            throw new ConcurrentUpdateException(reservationId, command.expectedVersion(), current.version());
        }

        // La red, fuera de la transacción.
        Itinerary newItinerary = itineraryAssembler.toItinerary(command.newItinerary());
        airportValidator.validate(newItinerary);

        return transaction.apply(command, newItinerary, now);
    }
}
