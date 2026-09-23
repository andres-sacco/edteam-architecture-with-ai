package com.edteam.reservations.application.service;

import com.edteam.reservations.application.audit.AuditAction;
import com.edteam.reservations.application.audit.AuditEntry;
import com.edteam.reservations.application.exception.ReservationNotFoundException;
import com.edteam.reservations.application.port.in.GetReservationQuery;
import com.edteam.reservations.application.port.in.GetReservationUseCase;
import com.edteam.reservations.application.port.out.AuditTrailPort;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.domain.access.ReservationAccessPolicy;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

/**
 * Lectura de una reserva por id.
 *
 * <p>Es el caso de uso que cerró la enumeración: el id es un {@code BIGSERIAL}
 * expuesto tal cual, así que sin autorización por recurso un {@code for} sobre
 * los ids volcaba documento y fecha de nacimiento de todos los pasajeros del
 * sistema.
 */
@Service
public class GetReservationService implements GetReservationUseCase {

    private final ReservationRepositoryPort reservationRepository;
    private final AuditTrailPort auditTrail;
    private final Clock clock;

    public GetReservationService(ReservationRepositoryPort reservationRepository,
                                 AuditTrailPort auditTrail,
                                 Clock clock) {
        this.reservationRepository = Objects.requireNonNull(reservationRepository);
        this.auditTrail = Objects.requireNonNull(auditTrail);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Deja de ser {@code readOnly}: el rechazo escribe una fila de auditoría, y
     * tiene que escribirse en la misma transacción que lo detecta para que no
     * pueda perderse la evidencia del intento mientras la respuesta sí sale.
     */
    @Override
    @Transactional
    public Reservation get(GetReservationQuery query) {
        Objects.requireNonNull(query, "El pedido es obligatorio");
        ReservationId reservationId = query.reservationId();

        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        if (!ReservationAccessPolicy.canRead(query.actor(), reservation)) {
            // Se audita el intento y se responde lo mismo que si no existiera.
            // El que la pidió no aprende nada; nosotros, sí: una ráfaga de
            // estas filas es una enumeración en curso.
            auditTrail.record(AuditEntry.denied(AuditAction.RESERVATION_ACCESS_DENIED,
                    query.actor().email(), reservationId.toString(), clock.instant()));
            throw new ReservationNotFoundException(reservationId);
        }
        return reservation;
    }
}
