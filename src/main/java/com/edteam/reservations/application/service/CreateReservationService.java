package com.edteam.reservations.application.service;

import com.edteam.reservations.application.exception.DuplicateReservationException;
import com.edteam.reservations.application.port.in.CreateReservationCommand;
import com.edteam.reservations.application.port.in.CreateReservationResult;
import com.edteam.reservations.application.port.in.CreateReservationUseCase;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.application.port.out.UserRepositoryPort;
import com.edteam.reservations.domain.event.ReservationCreated;
import com.edteam.reservations.domain.model.IdempotencyKey;
import com.edteam.reservations.domain.model.Itinerary;
import com.edteam.reservations.domain.model.Passenger;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
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
 *
 * <h2>El usuario</h2>
 * Se resuelve por email: si ya reservó antes se reutiliza su fila, y si no, se
 * da de alta. Es el mismo criterio que el adaptador de persistencia aplica a
 * segmentos y pasajeros, y por el mismo motivo: la clave natural del modelo de
 * datos es lo que permite reconocer a la misma entidad entre reservas.
 *
 * <p>Se resuelve <em>después</em> de las validaciones del itinerario y de los
 * pasajeros: no tiene sentido dar de alta a alguien por un pedido que se va a
 * rechazar. Como todo ocurre en la misma transacción, un fallo posterior
 * tampoco deja el usuario suelto.
 */
@Service
public class CreateReservationService implements CreateReservationUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateReservationService.class);

    private final ReservationRepositoryPort reservationRepository;
    private final UserRepositoryPort userRepository;
    private final ItineraryAssembler itineraryAssembler;
    private final AirportExistenceValidator airportValidator;
    private final EventOutboxPort eventOutbox;
    private final Clock clock;

    public CreateReservationService(ReservationRepositoryPort reservationRepository,
                                    UserRepositoryPort userRepository,
                                    ItineraryAssembler itineraryAssembler,
                                    AirportExistenceValidator airportValidator,
                                    EventOutboxPort eventOutbox,
                                    Clock clock) {
        this.reservationRepository = Objects.requireNonNull(reservationRepository);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.itineraryAssembler = Objects.requireNonNull(itineraryAssembler);
        this.airportValidator = Objects.requireNonNull(airportValidator);
        this.eventOutbox = Objects.requireNonNull(eventOutbox);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public CreateReservationResult create(CreateReservationCommand command) {
        Objects.requireNonNull(command, "El comando es obligatorio");

        IdempotencyKey idempotencyKey = IdempotencyKey.of(command.idempotencyKey());
        Optional<Reservation> alreadyCreated = reservationRepository.findByIdempotencyKey(idempotencyKey);
        if (alreadyCreated.isPresent()) {
            Reservation existing = alreadyCreated.get();
            log.info("Reintento con clave {}: se devuelve la reserva existente id={}",
                    idempotencyKey, existing.requireId());
            return CreateReservationResult.alreadyExisted(existing);
        }

        Itinerary itinerary = itineraryAssembler.toItinerary(command.itinerary());
        airportValidator.validate(itinerary);
        List<Passenger> passengers = itineraryAssembler.toPassengers(command.passengers());

        Instant now = clock.instant();
        User user = userRepository.findOrRegister(itineraryAssembler.toUser(command.user(), now));

        Reservation reservation = Reservation.create(
                user, idempotencyKey, itinerary, passengers, now);

        Reservation saved = reservationRepository.save(reservation);

        eventOutbox.enqueue(List.of(ReservationCreated.of(saved)));

        log.info("Reserva creada id={} usuario={} itinerario={}-{} pasajeros={}",
                saved.requireId(), saved.user().email(), saved.itinerary().origin(),
                saved.itinerary().destination(),
                saved.passengers().size());
        return CreateReservationResult.created(saved);
    }
}
