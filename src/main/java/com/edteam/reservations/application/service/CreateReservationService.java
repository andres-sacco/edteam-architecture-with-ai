package com.edteam.reservations.application.service;

import com.edteam.reservations.application.audit.AuditAction;
import com.edteam.reservations.application.audit.AuditEntry;
import com.edteam.reservations.application.port.in.CreateReservationCommand;
import com.edteam.reservations.application.port.in.CreateReservationResult;
import com.edteam.reservations.application.port.in.CreateReservationUseCase;
import com.edteam.reservations.application.port.out.AuditTrailPort;
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
 * Alta de una reserva, o recuperación de la que ya existe para esa clave de
 * idempotencia.
 *
 * <h2>Dos cambios de orden que son de seguridad, no de estilo</h2>
 * <ol>
 *   <li><b>El comprador sale del token.</b> Ya no hay {@code UserData} en el
 *       comando: el usuario se arma con el {@code actor}. Con el email en el
 *       cuerpo, cualquiera reservaba a nombre de {@code victima@ejemplo.com} y
 *       la notificación de «tu reserva» le llegaba a la víctima desde nuestro
 *       canal.</li>
 *   <li><b>La clave de idempotencia se resuelve dentro del usuario.</b> Eso
 *       obliga a buscarlo antes de validar el pedido —no a darlo de alta:
 *       el alta sigue ocurriendo al final, cuando el pedido ya pasó todas las
 *       validaciones, para que un pedido inválido no deje una fila en el
 *       maestro de usuarios.</li>
 * </ol>
 */
@Service
public class CreateReservationService implements CreateReservationUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateReservationService.class);

    private final ReservationRepositoryPort reservationRepository;
    private final UserRepositoryPort userRepository;
    private final ItineraryAssembler itineraryAssembler;
    private final AirportExistenceValidator airportValidator;
    private final EventOutboxPort eventOutbox;
    private final AuditTrailPort auditTrail;
    private final Clock clock;

    public CreateReservationService(ReservationRepositoryPort reservationRepository,
                                    UserRepositoryPort userRepository,
                                    ItineraryAssembler itineraryAssembler,
                                    AirportExistenceValidator airportValidator,
                                    EventOutboxPort eventOutbox,
                                    AuditTrailPort auditTrail,
                                    Clock clock) {
        this.reservationRepository = Objects.requireNonNull(reservationRepository);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.itineraryAssembler = Objects.requireNonNull(itineraryAssembler);
        this.airportValidator = Objects.requireNonNull(airportValidator);
        this.eventOutbox = Objects.requireNonNull(eventOutbox);
        this.auditTrail = Objects.requireNonNull(auditTrail);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public CreateReservationResult create(CreateReservationCommand command) {
        Objects.requireNonNull(command, "El comando es obligatorio");

        IdempotencyKey idempotencyKey = IdempotencyKey.of(command.idempotencyKey());
        Instant now = clock.instant();

        // Se BUSCA al usuario, no se lo da de alta todavía. La clave de
        // idempotencia se resuelve dentro del usuario, así que hace falta
        // saber quién es; pero registrarlo acá haría que cualquier pedido
        // inválido dejara una fila en el maestro. Un usuario que no existe
        // tampoco puede haber usado la clave antes, así que saltear la
        // búsqueda en ese caso es correcto y no sólo barato.
        Optional<User> registered = userRepository.findByEmail(command.actor().email());
        Optional<Reservation> alreadyCreated = registered
                .flatMap(user -> reservationRepository.findByIdempotencyKey(user.requireId(), idempotencyKey));
        if (alreadyCreated.isPresent()) {
            Reservation existing = alreadyCreated.get();
            log.info("Reintento con clave {}: se devuelve la reserva existente id={}",
                    idempotencyKey, existing.requireId());
            return CreateReservationResult.alreadyExisted(existing);
        }

        Itinerary itinerary = itineraryAssembler.toItinerary(command.itinerary());
        airportValidator.validate(itinerary);
        List<Passenger> passengers = itineraryAssembler.toPassengers(command.passengers());

        User user = userRepository.findOrRegister(itineraryAssembler.toUser(command.actor(), now));
        Reservation reservation = Reservation.create(user, idempotencyKey, itinerary, passengers, now);
        Reservation saved = reservationRepository.save(reservation);

        eventOutbox.enqueue(List.of(ReservationCreated.of(saved)));
        auditTrail.record(AuditEntry.allowed(AuditAction.RESERVATION_CREATED,
                command.actor().email(), saved.requireId().toString(), saved.version(), now));

        // El usuario se identifica por su id interno y no por su email: estos
        // logs salen del perímetro hacia el SaaS de observabilidad, que no
        // tiene por qué heredar un dato personal regulado.
        log.info("Reserva creada id={} usuario={} itinerario={}-{} pasajeros={}",
                saved.requireId(), saved.userId(), saved.itinerary().origin(),
                saved.itinerary().destination(), saved.passengers().size());
        return CreateReservationResult.created(saved);
    }
}
