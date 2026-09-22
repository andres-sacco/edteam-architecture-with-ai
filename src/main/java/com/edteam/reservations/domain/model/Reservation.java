package com.edteam.reservations.domain.model;

import com.edteam.reservations.domain.exception.InvalidReservationException;
import com.edteam.reservations.domain.exception.ItineraryAlreadyDepartedException;
import com.edteam.reservations.domain.exception.ReservationAlreadyCancelledException;
import com.edteam.reservations.domain.exception.ReservationNotModifiableException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Raíz del agregado: la reserva.
 *
 * <p>Pertenece a un {@link User}, apunta a un {@link Itinerary} y lleva uno o
 * más {@link Passenger}. Concentra las reglas
 * de creación, confirmación, modificación y cancelación. No conoce
 * persistencia, HTTP ni Spring: sólo tipos de {@code java.*} y del dominio.
 *
 * <h2>Identidad</h2>
 * El {@link ReservationId} lo asigna la base de datos, así que está vacío hasta
 * que la reserva se persiste. La identidad del agregado es la
 * {@link IdempotencyKey}, que existe desde el primer momento y es única en el
 * modelo de datos: por eso es la que usan {@code equals} y {@code hashCode}.
 *
 * <h2>Inmutabilidad</h2>
 * Cada operación devuelve una instancia nueva en lugar de mutar la actual. Con
 * muchos usuarios concurrentes esto elimina una clase entera de errores: una
 * instancia compartida no puede quedar en un estado intermedio mientras otro
 * hilo la lee.
 *
 * <h2>Control de concurrencia</h2>
 * {@link #version()} es la versión con la que se leyó el agregado. El dominio no
 * la incrementa: lo hace el adaptador de persistencia vía {@code @Version}, y
 * rechaza la escritura si otro proceso modificó la reserva mientras tanto.
 *
 * <h2>Eventos</h2>
 * Los eventos de dominio no se acumulan acá: se construyen desde la reserva ya
 * guardada (ver {@code domain.event}), porque hasta ese momento no existe el
 * {@link ReservationId} que el consumidor necesita.
 */
public final class Reservation {

    private final Optional<ReservationId> id;
    private final User user;
    private final IdempotencyKey idempotencyKey;
    private final Itinerary itinerary;
    private final List<Passenger> passengers;
    private final ReservationStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;

    private Reservation(Optional<ReservationId> id,
                        User user,
                        IdempotencyKey idempotencyKey,
                        Itinerary itinerary,
                        List<Passenger> passengers,
                        ReservationStatus status,
                        Instant createdAt,
                        Instant updatedAt,
                        long version) {
        this.id = Objects.requireNonNull(id, "El id es obligatorio (usar Optional.empty() si no está asignado)");
        this.user = Objects.requireNonNull(user, "El usuario es obligatorio");
        if (user.id().isEmpty()) {
            // Una reserva se atribuye a un usuario que ya existe: la clave
            // foránea del modelo de datos no admite otra cosa. Verificarlo acá
            // evita que el error aparezca recién al persistir.
            throw new InvalidReservationException(
                    "No se puede atribuir una reserva al usuario %s: todavía no está dado de alta"
                            .formatted(user.email()));
        }
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "La clave de idempotencia es obligatoria");
        this.itinerary = Objects.requireNonNull(itinerary, "El itinerario es obligatorio");
        this.status = Objects.requireNonNull(status, "El estado es obligatorio");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt es obligatorio");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt es obligatorio");
        Objects.requireNonNull(passengers, "Los pasajeros son obligatorios");
        if (version < 0) {
            throw new IllegalArgumentException("La versión no puede ser negativa");
        }
        this.passengers = List.copyOf(passengers);
        this.version = version;
    }

    /**
     * Crea una reserva nueva, en estado {@link ReservationStatus#PENDING}.
     *
     * @param now instante actual, inyectado por el caso de uso desde un
     *            {@link java.time.Clock}, para que las reglas temporales sean
     *            verificables en los tests
     * @throws InvalidReservationException        si no hay pasajeros, si hay repetidos
     *                                            o si alguno tiene fecha de nacimiento futura
     * @throws ItineraryAlreadyDepartedException si el itinerario ya arrancó
     */
    public static Reservation create(User user,
                                     IdempotencyKey idempotencyKey,
                                     Itinerary itinerary,
                                     List<Passenger> passengers,
                                     Instant now) {
        Objects.requireNonNull(itinerary, "El itinerario es obligatorio");
        Objects.requireNonNull(passengers, "Los pasajeros son obligatorios");
        Objects.requireNonNull(now, "El instante actual es obligatorio");

        validatePassengers(passengers, now);
        if (itinerary.hasDeparted(now)) {
            throw new ItineraryAlreadyDepartedException(
                    "No se puede reservar el itinerario %s-%s: el primer tramo ya salió (%s)"
                            .formatted(itinerary.origin(), itinerary.destination(), itinerary.firstDeparture()));
        }

        return new Reservation(Optional.empty(), user, idempotencyKey, itinerary, passengers,
                ReservationStatus.PENDING, now, now, 0L);
    }

    /**
     * Reconstruye una reserva ya existente a partir de su estado persistido.
     * Es el único camino que deben usar los adaptadores de salida: no dispara
     * las reglas de creación, así que puede leer reservas históricas cuyo vuelo
     * ya pasó.
     */
    public static Reservation rehydrate(ReservationId id,
                                        User user,
                                        IdempotencyKey idempotencyKey,
                                        Itinerary itinerary,
                                        List<Passenger> passengers,
                                        ReservationStatus status,
                                        Instant createdAt,
                                        Instant updatedAt,
                                        long version) {
        Objects.requireNonNull(id, "El id es obligatorio al reconstruir una reserva");
        if (passengers.isEmpty()) {
            throw new InvalidReservationException("La reserva %s no tiene pasajeros".formatted(id));
        }
        return new Reservation(Optional.of(id), user, idempotencyKey, itinerary, passengers,
                status, createdAt, updatedAt, version);
    }

    /**
     * Confirma la reserva.
     *
     * @throws ReservationNotModifiableException  si no está pendiente
     * @throws ItineraryAlreadyDepartedException si el itinerario ya arrancó
     */
    public Reservation confirm(Instant now) {
        Objects.requireNonNull(now, "El instante actual es obligatorio");

        if (status != ReservationStatus.PENDING) {
            throw new ReservationNotModifiableException(describeId(), status);
        }
        requireNotDeparted(now, "confirmar");

        return copyWith(itinerary, ReservationStatus.CONFIRMED, now);
    }

    /**
     * Cambia el itinerario de la reserva.
     *
     * @throws ReservationNotModifiableException  si la reserva está cancelada
     * @throws ItineraryAlreadyDepartedException si el itinerario actual ya arrancó
     *                                            o si el nuevo ya arrancó
     */
    public Reservation changeItinerary(Itinerary newItinerary, Instant now) {
        Objects.requireNonNull(newItinerary, "El nuevo itinerario es obligatorio");
        Objects.requireNonNull(now, "El instante actual es obligatorio");

        if (!status.isActive()) {
            throw new ReservationNotModifiableException(describeId(), status);
        }
        requireNotDeparted(now, "modificar");
        if (newItinerary.hasDeparted(now)) {
            throw new ItineraryAlreadyDepartedException(
                    "No se puede reservar el itinerario %s-%s: el primer tramo ya salió (%s)"
                            .formatted(newItinerary.origin(), newItinerary.destination(),
                                    newItinerary.firstDeparture()));
        }

        return copyWith(newItinerary, status, now);
    }

    /**
     * Cancela la reserva. Es una baja lógica: el registro se conserva.
     *
     * @throws ReservationAlreadyCancelledException si ya estaba cancelada
     * @throws ItineraryAlreadyDepartedException   si el itinerario ya arrancó
     */
    public Reservation cancel(Instant now) {
        Objects.requireNonNull(now, "El instante actual es obligatorio");

        if (status == ReservationStatus.CANCELLED) {
            throw new ReservationAlreadyCancelledException(describeId());
        }
        requireNotDeparted(now, "cancelar");

        return copyWith(itinerary, ReservationStatus.CANCELLED, now);
    }

    /**
     * Devuelve una copia con el id indicado. La usa el adaptador de persistencia
     * para reflejar el id que asignó la base al insertar.
     */
    public Reservation withId(ReservationId assignedId) {
        Objects.requireNonNull(assignedId, "El id asignado es obligatorio");
        return new Reservation(Optional.of(assignedId), user, idempotencyKey, itinerary, passengers,
                status, createdAt, updatedAt, version);
    }

    /** Devuelve una copia con la versión indicada, tal como quedó almacenada. */
    public Reservation withVersion(long newVersion) {
        return new Reservation(id, user, idempotencyKey, itinerary, passengers,
                status, createdAt, updatedAt, newVersion);
    }

    /**
     * Devuelve una copia con los pasajeros indicados. La usa el adaptador de
     * persistencia para reemplazar los pasajeros nuevos por los ya persistidos
     * (con su id asignado), sin volver a pasar por las reglas de creación.
     */
    public Reservation withPassengers(List<Passenger> resolvedPassengers) {
        Objects.requireNonNull(resolvedPassengers, "Los pasajeros son obligatorios");
        if (resolvedPassengers.size() != passengers.size()) {
            throw new IllegalArgumentException(
                    "La lista de pasajeros resueltos debe tener el mismo tamaño que la original");
        }
        return new Reservation(id, user, idempotencyKey, itinerary, resolvedPassengers,
                status, createdAt, updatedAt, version);
    }

    /** Devuelve una copia con el itinerario indicado, ya persistido y con su id. */
    public Reservation withItinerary(Itinerary persistedItinerary) {
        Objects.requireNonNull(persistedItinerary, "El itinerario es obligatorio");
        return new Reservation(id, user, idempotencyKey, persistedItinerary, passengers,
                status, createdAt, updatedAt, version);
    }

    public Optional<ReservationId> id() {
        return id;
    }

    /**
     * Id de la reserva, exigiendo que esté asignado.
     *
     * @throws IllegalStateException si la reserva todavía no se persistió
     */
    public ReservationId requireId() {
        return id.orElseThrow(() -> new IllegalStateException(
                "La reserva %s todavía no tiene id asignado".formatted(idempotencyKey)));
    }

    /**
     * Dueño de la reserva.
     *
     * <p>Se guarda el usuario y no sólo su id porque hacen falta las dos
     * caras: los eventos de dominio identifican al destinatario por
     * {@link UserId} —para que un cambio de email no obligue a reemitirlos— y
     * la API lo expone por {@link Email}, que es lo que el cliente conoce. Un
     * único campo obligaría a resolver la otra mitad en cada lectura.
     */
    public User user() {
        return user;
    }

    /** Id del dueño de la reserva. */
    public UserId userId() {
        return user.requireId();
    }

    public IdempotencyKey idempotencyKey() {
        return idempotencyKey;
    }

    public Itinerary itinerary() {
        return itinerary;
    }

    public List<Passenger> passengers() {
        return passengers;
    }

    public ReservationStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    private Reservation copyWith(Itinerary newItinerary, ReservationStatus newStatus, Instant now) {
        return new Reservation(id, user, idempotencyKey, newItinerary, passengers,
                newStatus, createdAt, now, version);
    }

    private void requireNotDeparted(Instant now, String operation) {
        if (itinerary.hasDeparted(now)) {
            throw new ItineraryAlreadyDepartedException(
                    "No se puede %s la reserva %s: el itinerario ya arrancó el %s"
                            .formatted(operation, describeId(), itinerary.firstDeparture()));
        }
    }

    /** Referencia legible de la reserva, sirva o no el id (puede no estar asignado). */
    private String describeId() {
        return id.map(ReservationId::toString).orElseGet(idempotencyKey::toString);
    }

    private static void validatePassengers(List<Passenger> passengers, Instant now) {
        if (passengers.isEmpty()) {
            throw new InvalidReservationException("La reserva debe tener al menos un pasajero");
        }

        Set<String> identities = new HashSet<>();
        for (Passenger passenger : passengers) {
            if (!identities.add(passenger.identityKey())) {
                throw new InvalidReservationException(
                        "El pasajero %s aparece más de una vez en la reserva".formatted(passenger.fullName()));
            }
            if (passenger.isBornAfter(now.atZone(ZoneOffset.UTC).toLocalDate())) {
                throw new InvalidReservationException(
                        "La fecha de nacimiento de %s es futura".formatted(passenger.fullName()));
            }
        }
    }

    /**
     * Identidad por clave de idempotencia: es única en el modelo de datos y
     * existe desde antes de persistir, a diferencia del id.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Reservation that && idempotencyKey.equals(that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return idempotencyKey.hashCode();
    }

    @Override
    public String toString() {
        return "Reservation[id=%s, usuario=%s, estado=%s, itinerario=%s-%s, pasajeros=%d, version=%d]"
                .formatted(describeId(), user.email(), status, itinerary.origin(), itinerary.destination(),
                        passengers.size(), version);
    }
}
