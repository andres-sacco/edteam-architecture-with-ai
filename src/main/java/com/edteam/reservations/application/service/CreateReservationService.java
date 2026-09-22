package com.edteam.reservations.application.service;

import com.edteam.reservations.application.exception.DuplicateReservationException;
import com.edteam.reservations.application.port.in.CreateReservationCommand;
import com.edteam.reservations.application.port.in.CreateReservationUseCase;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.domain.event.ReservationCreated;
import com.edteam.reservations.domain.model.IdempotencyKey;
import com.edteam.reservations.domain.model.Itinerary;
import com.edteam.reservations.domain.model.Passenger;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Orquesta la creación de una reserva.
 *
 * <p>El servicio no contiene reglas de negocio: traduce el comando a tipos del
 * dominio, dispara las validaciones que necesitan colaboradores externos
 * (existencia de aeropuertos), delega la regla en el agregado y coordina la
 * persistencia y la publicación del evento.
 *
 * <h2>Idempotencia</h2>
 * La operación es segura ante reintentos, con dos niveles:
 * <ol>
 *   <li>Antes de crear, busca por clave de idempotencia y devuelve la reserva
 *       existente si la hay. Es el caso habitual: un reintento del cliente
 *       después de un timeout, o un doble click.</li>
 *   <li>Si dos pedidos con la misma clave corren a la vez, la búsqueda previa no
 *       alcanza —ninguno ve al otro todavía— y resuelve el {@code UNIQUE} del
 *       modelo de datos: el perdedor recibe
 *       {@link DuplicateReservationException} y su transacción se descarta. No
 *       se reintenta acá adentro: una violación de constraint deja la
 *       transacción JPA marcada como <em>rollback-only</em>, así que volver a
 *       consultar en el mismo contexto no serviría. El reintento le corresponde
 *       al adaptador de entrada, y en ese segundo intento el nivel 1 encuentra
 *       la reserva ganadora.</li>
 * </ol>
 * En ninguno de los dos casos se notifica de nuevo: la notificación corresponde
 * a la reserva que se creó, no a cada intento.
 */
@Service
public class CreateReservationService implements CreateReservationUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateReservationService.class);

    private final ReservationRepositoryPort reservationRepository;
    private final ItineraryAssembler itineraryAssembler;
    private final AirportExistenceValidator airportValidator;
    private final EventOutboxPort eventOutbox;
    private final Clock clock;

    public CreateReservationService(ReservationRepositoryPort reservationRepository,
                                    ItineraryAssembler itineraryAssembler,
                                    AirportExistenceValidator airportValidator,
                                    EventOutboxPort eventOutbox,
                                    Clock clock) {
        this.reservationRepository = Objects.requireNonNull(reservationRepository);
        this.itineraryAssembler = Objects.requireNonNull(itineraryAssembler);
        this.airportValidator = Objects.requireNonNull(airportValidator);
        this.eventOutbox = Objects.requireNonNull(eventOutbox);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public Reservation create(CreateReservationCommand command) {
        Objects.requireNonNull(command, "El comando es obligatorio");

        IdempotencyKey idempotencyKey = IdempotencyKey.of(command.idempotencyKey());
        Optional<Reservation> alreadyCreated = reservationRepository.findByIdempotencyKey(idempotencyKey);
        if (alreadyCreated.isPresent()) {
            Reservation existing = alreadyCreated.get();
            log.info("Reintento con clave {}: se devuelve la reserva existente id={}",
                    idempotencyKey, existing.requireId());
            return existing;
        }

        Itinerary itinerary = itineraryAssembler.toItinerary(command.itinerary());
        airportValidator.validate(itinerary);
        List<Passenger> passengers = itineraryAssembler.toPassengers(command.passengers());

        Reservation reservation = Reservation.create(
                UserId.of(command.userId()), idempotencyKey, itinerary, passengers, clock.instant());

        Reservation saved = reservationRepository.save(reservation);

        eventOutbox.enqueue(List.of(ReservationCreated.of(saved)));

        log.info("Reserva creada id={} usuario={} itinerario={}-{} pasajeros={}",
                saved.requireId(), saved.userId(), saved.itinerary().origin(), saved.itinerary().destination(),
                saved.passengers().size());
        return saved;
    }
}
