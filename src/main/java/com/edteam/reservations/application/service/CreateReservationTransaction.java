package com.edteam.reservations.application.service;

import com.edteam.reservations.application.audit.AuditAction;
import com.edteam.reservations.application.audit.AuditEntry;
import com.edteam.reservations.application.port.in.CreateReservationCommand;
import com.edteam.reservations.application.port.in.CreateReservationResult;
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
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La parte transaccional del alta: sólo base de datos, nada de red.
 *
 * <h2>Por qué está separada del caso de uso</h2>
 * La validación de los aeropuertos es HTTP contra un servicio externo: hasta
 * tres intentos y ~6,5 s de peor caso <em>por ciudad</em>. Adentro de la
 * transacción, un itinerario de tres tramos contra un catálogo degradado
 * retiene una conexión del pool ~26 s; con {@code maximum-pool-size: 20},
 * veinte pedidos así agotan el pool y la API entera devuelve error —incluidos
 * los {@code GET}, que no tocan el catálogo ni escriben nada—.
 *
 * <p>El {@code INSERT} del outbox empeoraba el cuadro: metía una escritura más
 * en esa transacción larga. Con la llamada afuera, la transacción dura lo que
 * duran unas pocas sentencias SQL.
 *
 * <p>La separación es en dos beans y no en dos métodos de la misma clase
 * porque {@code @Transactional} se aplica por proxy: una autoinvocación no
 * abre transacción, y el bug sería invisible. {@code HexagonalArchitectureTest}
 * fija la regla: ninguna clase transaccional puede alcanzar
 * {@code AirportCatalogPort}.
 */
@Service
class CreateReservationTransaction {

    private static final Logger log = LoggerFactory.getLogger(CreateReservationTransaction.class);

    private final ReservationRepositoryPort reservationRepository;
    private final UserRepositoryPort userRepository;
    private final ItineraryAssembler itineraryAssembler;
    private final EventOutboxPort eventOutbox;
    private final AuditTrailPort auditTrail;

    CreateReservationTransaction(
            ReservationRepositoryPort reservationRepository,
            UserRepositoryPort userRepository,
            ItineraryAssembler itineraryAssembler,
            EventOutboxPort eventOutbox,
            AuditTrailPort auditTrail) {
        this.reservationRepository = Objects.requireNonNull(reservationRepository);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.itineraryAssembler = Objects.requireNonNull(itineraryAssembler);
        this.eventOutbox = Objects.requireNonNull(eventOutbox);
        this.auditTrail = Objects.requireNonNull(auditTrail);
    }

    /**
     * Busca al usuario, resuelve la idempotencia, persiste, encola el evento y
     * audita. Todo en una transacción.
     *
     * <p>El {@code enqueue} entra en esta misma transacción, y es lo que hace
     * que no se emita un evento fantasma: si el commit falla —un
     * {@code UNIQUE}, un conflicto optimista, la conexión que se corta— el
     * evento se va con el rollback.
     */
    // timeout = 1: el techo del CONJUNTO, no de cada sentencia. El
    // statement_timeout del driver acota cada una; sin este, seis sentencias
    // de 1,9 s cada una siguen sumando doce segundos con una conexión del pool
    // retenida.
    //
    // Un segundo y no dos porque este número ES el renglón de persistencia del
    // presupuesto del pedido: seis lecturas y escrituras por índice único no
    // tienen derecho a tardar más, y un techo más alto que lo presupuestado
    // convierte al presupuesto en una declaración de intenciones. El
    // statement_timeout de 2 s del driver queda como red por si alguna
    // sentencia se sale de lo previsto.
    @Transactional(timeout = 1)
    CreateReservationResult apply(
            CreateReservationCommand command, Itinerary itinerary, List<Passenger> passengers, Instant now) {
        IdempotencyKey idempotencyKey = IdempotencyKey.of(command.idempotencyKey());

        // Se BUSCA al usuario, no se lo da de alta todavía. La clave de
        // idempotencia se resuelve dentro del usuario, así que hace falta
        // saber quién es; pero registrarlo acá haría que cualquier pedido
        // inválido dejara una fila en el maestro. Un usuario que no existe
        // tampoco puede haber usado la clave antes, así que saltear la
        // búsqueda en ese caso es correcto y no sólo barato.
        Optional<User> registered = userRepository.findByEmail(command.actor().email());
        Optional<Reservation> alreadyCreated = registered.flatMap(
                user -> reservationRepository.findByIdempotencyKey(user.requireId(), idempotencyKey));
        if (alreadyCreated.isPresent()) {
            Reservation existing = alreadyCreated.get();
            log.atInfo()
                    .addKeyValue("event", "reservation.replayed")
                    .addKeyValue("idempotencyKey", idempotencyKey.value())
                    .addKeyValue("reservationId", existing.requireId().value())
                    .addKeyValue("userId", existing.userId().value())
                    .addKeyValue("reservationVersion", existing.version())
                    .log("Reintento con la misma clave de idempotencia: se devuelve la reserva existente");
            return CreateReservationResult.alreadyExisted(existing);
        }

        User user = userRepository.findOrRegister(itineraryAssembler.toUser(command.actor(), now));
        Reservation reservation = Reservation.create(user, idempotencyKey, itinerary, passengers, now);
        Reservation saved = reservationRepository.save(reservation);

        eventOutbox.enqueue(List.of(ReservationCreated.of(saved)));
        auditTrail.record(AuditEntry.allowed(
                AuditAction.RESERVATION_CREATED,
                command.actor().email(),
                saved.requireId().toString(),
                saved.version(),
                now));

        // El usuario se identifica por su id interno y no por su email: estos
        // logs salen del perímetro hacia el SaaS de observabilidad, que no
        // tiene por qué heredar un dato personal regulado.
        //
        // Un campo por dato. El `message` es texto fijo: que el dato viajara
        // interpolado adentro del mensaje era lo que obligaba a una expresión
        // regular distinta por formato de línea para poder filtrar por reserva
        // o por usuario. Los cuatro eventos de dominio llevan ahora el MISMO
        // juego de campos obligatorios —`reservationId`, `userId`,
        // `reservationVersion`— que es lo que permite preguntar «todo lo que le
        // pasó al usuario 4471» y obtener las cuatro cosas, no una.
        log.atInfo()
                .addKeyValue("event", "reservation.created")
                .addKeyValue("reservationId", saved.requireId().value())
                .addKeyValue("userId", saved.userId().value())
                .addKeyValue("reservationVersion", saved.version())
                .addKeyValue("itinerary.origin", saved.itinerary().origin().value())
                .addKeyValue(
                        "itinerary.destination", saved.itinerary().destination().value())
                .addKeyValue("passengers", saved.passengers().size())
                .log("Reserva creada");
        return CreateReservationResult.created(saved);
    }
}
