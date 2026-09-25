package com.edteam.reservations.infrastructure.adapter.out.persistence;

import com.edteam.reservations.application.exception.ConcurrentUpdateException;
import com.edteam.reservations.application.exception.DuplicateReservationException;
import com.edteam.reservations.application.exception.ReservationNotFoundException;
import com.edteam.reservations.application.exception.UnknownUserException;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.application.query.ReservationSearchCriteria;
import com.edteam.reservations.application.query.ResultPage;
import com.edteam.reservations.domain.model.IdempotencyKey;
import com.edteam.reservations.domain.model.Itinerary;
import com.edteam.reservations.domain.model.Passenger;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationId;
import com.edteam.reservations.domain.model.Segment;
import com.edteam.reservations.domain.model.UserId;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.ItineraryJpaEntity;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.PassengerJpaEntity;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.ReservationJpaEntity;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.ReservationStatusJpa;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.SegmentJpaEntity;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.UserJpaEntity;
import com.edteam.reservations.infrastructure.adapter.out.persistence.mapper.ItineraryMapper;
import com.edteam.reservations.infrastructure.adapter.out.persistence.mapper.PassengerMapper;
import com.edteam.reservations.infrastructure.adapter.out.persistence.mapper.ReservationMapper;
import com.edteam.reservations.infrastructure.adapter.out.persistence.mapper.SegmentMapper;
import com.edteam.reservations.infrastructure.adapter.out.persistence.repository.ItineraryJpaRepository;
import com.edteam.reservations.infrastructure.adapter.out.persistence.repository.PassengerJpaRepository;
import com.edteam.reservations.infrastructure.adapter.out.persistence.repository.ReservationJpaRepository;
import com.edteam.reservations.infrastructure.adapter.out.persistence.repository.SegmentJpaRepository;
import com.edteam.reservations.infrastructure.adapter.out.persistence.repository.UserJpaRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

/**
 * Adaptador de salida que implementa {@link ReservationRepositoryPort} sobre
 * PostgreSQL con Spring Data JPA.
 *
 * <p>Es el único lugar del sistema que conoce las entidades JPA: hacia afuera
 * sólo entran y salen objetos de dominio.
 *
 * <h2>Reutilización de filas</h2>
 * Segmentos y pasajeros son compartidos entre reservas, así que antes de
 * insertar se intenta reutilizar la fila existente usando las claves naturales
 * del modelo de datos: {@code (origen, destino, aerolinea, fecha_vuelo)} para
 * el segmento y {@code documento} para el pasajero. El itinerario, en cambio,
 * se crea siempre: el modelo de datos no le define clave natural, porque la
 * misma combinación de tramos puede venderse a distinto precio.
 *
 * <h2>Concurrencia</h2>
 * <ul>
 *   <li><em>Reservas duplicadas:</em> el {@code UNIQUE} sobre
 *       {@code idempotency_key} las bloquea en la base. El intento perdedor
 *       recibe {@link DuplicateReservationException}.</li>
 *   <li><em>Modificaciones simultáneas:</em> {@code @Version} en la entidad, con
 *       verificación explícita de la versión leída y traducción de
 *       {@link OptimisticLockingFailureException} a
 *       {@link ConcurrentUpdateException}.</li>
 *   <li><em>Segmentos y pasajeros compartidos:</em> {@code INSERT ... ON CONFLICT
 *       DO NOTHING}, para que dos reservas simultáneas del mismo vuelo no se
 *       pisen ni rompan la transacción.</li>
 * </ul>
 *
 * <h2>Traducción de errores</h2>
 * Las excepciones de Spring/Hibernate no cruzan el puerto: se traducen a las de
 * la capa de aplicación. Si se filtraran, los casos de uso quedarían atados a
 * JPA y no se podría cambiar de tecnología de persistencia.
 */
@Repository
public class ReservationPersistenceAdapter implements ReservationRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(ReservationPersistenceAdapter.class);

    /** Nombres de constraints del modelo de datos, usados para traducir errores. */
    private static final String UQ_IDEMPOTENCY_KEY = "uq_reserva_usuario_idempotency_key";

    private static final String FK_RESERVA_USUARIO = "fk_reserva_usuario";

    private final ReservationJpaRepository reservationRepository;
    private final ReservationSearchQuery reservationSearch;
    private final ItineraryJpaRepository itineraryRepository;
    private final SegmentJpaRepository segmentRepository;
    private final PassengerJpaRepository passengerRepository;
    private final UserJpaRepository userRepository;
    private final ReservationMapper reservationMapper;
    private final ItineraryMapper itineraryMapper;
    private final SegmentMapper segmentMapper;
    private final PassengerMapper passengerMapper;

    public ReservationPersistenceAdapter(
            ReservationJpaRepository reservationRepository,
            ReservationSearchQuery reservationSearch,
            ItineraryJpaRepository itineraryRepository,
            SegmentJpaRepository segmentRepository,
            PassengerJpaRepository passengerRepository,
            UserJpaRepository userRepository,
            ReservationMapper reservationMapper,
            ItineraryMapper itineraryMapper,
            SegmentMapper segmentMapper,
            PassengerMapper passengerMapper) {
        this.reservationRepository = Objects.requireNonNull(reservationRepository);
        this.reservationSearch = Objects.requireNonNull(reservationSearch);
        this.itineraryRepository = Objects.requireNonNull(itineraryRepository);
        this.segmentRepository = Objects.requireNonNull(segmentRepository);
        this.passengerRepository = Objects.requireNonNull(passengerRepository);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.reservationMapper = Objects.requireNonNull(reservationMapper);
        this.itineraryMapper = Objects.requireNonNull(itineraryMapper);
        this.segmentMapper = Objects.requireNonNull(segmentMapper);
        this.passengerMapper = Objects.requireNonNull(passengerMapper);
    }

    @Override
    public Optional<Reservation> findById(ReservationId reservationId) {
        Objects.requireNonNull(reservationId, "El id es obligatorio");
        return reservationRepository.findById(reservationId.value()).map(reservationMapper::toDomain);
    }

    @Override
    public Optional<Reservation> findByIdempotencyKey(UserId owner, IdempotencyKey idempotencyKey) {
        Objects.requireNonNull(owner, "El usuario es obligatorio");
        Objects.requireNonNull(idempotencyKey, "La clave de idempotencia es obligatoria");
        return reservationRepository
                .findByUserIdAndIdempotencyKey(owner.value(), idempotencyKey.value())
                .map(reservationMapper::toDomain);
    }

    @Override
    public ResultPage<Reservation> search(ReservationSearchCriteria criteria) {
        Objects.requireNonNull(criteria, "El criterio de búsqueda es obligatorio");

        long total = reservationSearch.count(criteria);
        if (total == 0) {
            // Sin resultados no tiene sentido pedir la página: se ahorra la
            // segunda consulta, que es la cara (trae las colecciones).
            return ResultPage.empty(criteria.page(), criteria.size());
        }

        List<Long> pageOfIds = reservationSearch.findPageOfIds(criteria);
        if (pageOfIds.isEmpty()) {
            // Se pidió una página más allá del final: no es un error, es una
            // página vacía con el total real.
            return new ResultPage<>(List.of(), criteria.page(), criteria.size(), total);
        }

        return new ResultPage<>(hydrateInOrder(pageOfIds), criteria.page(), criteria.size(), total);
    }

    /**
     * Lee los agregados de la página y los devuelve en el orden de los ids.
     *
     * <p>El {@code IN} no garantiza orden, así que reponerlo acá es lo que hace
     * que el {@code ORDER BY} de la consulta paginada llegue hasta el cliente.
     */
    private List<Reservation> hydrateInOrder(List<Long> orderedIds) {
        Map<Long, ReservationJpaEntity> byId = new LinkedHashMap<>();
        for (ReservationJpaEntity entity : reservationRepository.findAllByIdIn(orderedIds)) {
            byId.put(entity.getId(), entity);
        }
        return orderedIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(reservationMapper::toDomain)
                .toList();
    }

    @Override
    public Reservation save(Reservation reservation) {
        Objects.requireNonNull(reservation, "La reserva es obligatoria");
        return reservation.id().isPresent() ? update(reservation) : insert(reservation);
    }

    private Reservation insert(Reservation reservation) {
        ItineraryJpaEntity itinerary = persistItinerary(reservation.itinerary());
        List<PassengerJpaEntity> passengers =
                reservation.passengers().stream().map(this::resolvePassenger).toList();

        // Referencia perezosa: para escribir la clave foránea alcanza con el
        // id, que el caso de uso ya resolvió al dar de alta o encontrar al
        // usuario. No hace falta traer la fila.
        UserJpaEntity user =
                userRepository.getReferenceById(reservation.userId().value());

        ReservationJpaEntity entity = reservationMapper.toNewEntity(reservation, user, itinerary, passengers);
        try {
            ReservationJpaEntity saved = reservationRepository.saveAndFlush(entity);
            return reservationMapper.toDomain(saved);
        } catch (DataIntegrityViolationException e) {
            throw translateIntegrityViolation(e, reservation);
        }
    }

    private Reservation update(Reservation reservation) {
        ReservationId reservationId = reservation.requireId();
        ReservationJpaEntity entity = reservationRepository
                .findById(reservationId.value())
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        // Verificación explícita: detecta el conflicto antes de tocar nada, con
        // la versión real en el mensaje. El @Version cubre además la ventana
        // entre esta lectura y el flush.
        if (entity.getVersion() != reservation.version()) {
            throw new ConcurrentUpdateException(reservationId, reservation.version(), entity.getVersion());
        }

        ItineraryJpaEntity itinerary = resolveItineraryForUpdate(reservation, entity);
        entity.apply(ReservationStatusJpa.fromDomain(reservation.status()), itinerary, reservation.updatedAt());

        try {
            ReservationJpaEntity saved = reservationRepository.saveAndFlush(entity);
            return reservationMapper.toDomain(saved);
        } catch (OptimisticLockingFailureException e) {
            throw new ConcurrentUpdateException(reservationId, reservation.version(), e);
        }
    }

    /**
     * Devuelve el itinerario a asociar en una actualización: el mismo que ya
     * tenía si no cambió, o uno nuevo persistido si la reserva trae otro.
     */
    private ItineraryJpaEntity resolveItineraryForUpdate(Reservation reservation, ReservationJpaEntity entity) {
        boolean sameItinerary = reservation
                .itinerary()
                .id()
                .map(id -> id.value() == entity.getItinerary().getId())
                .orElse(false);
        return sameItinerary ? entity.getItinerary() : persistItinerary(reservation.itinerary());
    }

    /** Crea el itinerario, reutilizando los segmentos que ya existan. */
    private ItineraryJpaEntity persistItinerary(Itinerary itinerary) {
        List<SegmentJpaEntity> segments =
                itinerary.segments().stream().map(this::resolveSegment).toList();
        return itineraryRepository.save(itineraryMapper.toNewEntity(itinerary, segments));
    }

    /**
     * Devuelve la fila del segmento, creándola si es la primera vez que se
     * reserva ese tramo.
     *
     * <p>El {@code ON CONFLICT DO NOTHING} del insert deja la operación segura
     * ante concurrencia: si otra transacción insertó el mismo tramo, no falla y
     * la consulta posterior encuentra la fila de la otra.
     */
    private SegmentJpaEntity resolveSegment(Segment segment) {
        return findSegment(segment).orElseGet(() -> {
            segmentRepository.insertIfAbsent(
                    segment.origin().value(), segment.destination().value(), segment.airline(), segment.departureAt());
            return findSegment(segment)
                    .orElseThrow(() -> new IllegalStateException(
                            "El segmento %s no quedó disponible después del insert".formatted(segment.naturalKey())));
        });
    }

    private Optional<SegmentJpaEntity> findSegment(Segment segment) {
        return segmentRepository.findByOriginAndDestinationAndAirlineAndDepartureAt(
                segment.origin().value(), segment.destination().value(), segment.airline(), segment.departureAt());
    }

    /**
     * Escribe el pasajero tal como vino en el pedido.
     *
     * <p>Antes reutilizaba la fila existente con el mismo documento, y esa
     * reutilización cruzaba el borde de confianza: la respuesta del alta
     * devolvía los datos <em>almacenados</em>, así que mandar el documento de
     * otra persona respondía con su nombre, su apellido y su fecha de
     * nacimiento reales. A la inversa, registrar primero un documento con
     * datos falsos se los imponía a la reserva legítima que viniera después.
     *
     * <p>Lo que se pierde es una fila por pasajero repetido. Lo que se gana es
     * que la representación que sale sea exactamente la que entró, y que el
     * documento pueda guardarse cifrado: una columna cifrada con IV aleatorio
     * no es buscable, así que la deduplicación por documento dejó de ser
     * posible de todas formas.
     *
     * <p>Reconciliar la identidad de un pasajero entre reservas sigue siendo
     * deseable, pero como proceso interno del lado del servidor y no como un
     * efecto observable del alta.
     */
    private PassengerJpaEntity resolvePassenger(Passenger passenger) {
        return passengerRepository.save(passengerMapper.toNewEntity(passenger));
    }

    /**
     * Traduce una violación de integridad a la excepción de aplicación que
     * corresponda, según qué constraint del modelo de datos se rompió.
     */
    private RuntimeException translateIntegrityViolation(DataIntegrityViolationException e, Reservation reservation) {
        String constraint = constraintNameOf(e);
        log.debug("Violación de integridad al insertar la reserva (constraint={})", constraint);

        if (matches(constraint, UQ_IDEMPOTENCY_KEY)) {
            return new DuplicateReservationException(reservation.idempotencyKey(), e);
        }
        if (matches(constraint, FK_RESERVA_USUARIO)) {
            return new UnknownUserException(reservation.userId(), e);
        }
        return e;
    }

    private static String constraintNameOf(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                return violation.getConstraintName();
            }
            cause = cause.getCause();
        }
        // Sin nombre de constraint queda el mensaje, que en PostgreSQL lo incluye.
        return e.getMostSpecificCause().getMessage();
    }

    private static boolean matches(String constraintOrMessage, String expectedConstraint) {
        return constraintOrMessage != null
                && constraintOrMessage.toLowerCase(Locale.ROOT).contains(expectedConstraint);
    }
}
