package com.edteam.reservations.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Tabla {@code reserva}.
 *
 * <h2>Concurrencia</h2>
 * {@code @Version} sobre la columna {@code version} activa el optimistic locking
 * de JPA: dos transacciones que lean la misma reserva y quieran escribirla, sólo
 * una gana; la otra recibe {@code OptimisticLockingFailureException}, que el
 * adaptador traduce a {@code ConcurrentUpdateException}.
 *
 * <p>{@code idempotency_key} es {@code UNIQUE}: es lo que impide que un
 * reintento del cliente —o dos pedidos simultáneos con la misma clave— generen
 * dos reservas. La garantía la da la base, no la aplicación.
 *
 * <h2>Relaciones</h2>
 * El usuario es un {@code @ManyToOne} y se trae junto con la reserva. Antes era
 * una columna suelta, con el argumento de que a la reserva le alcanzaba el id;
 * dejó de alcanzar cuando la API pasó a identificar al usuario por su email,
 * que es el dato que el cliente conoce. El join se paga una vez por lectura y
 * evita una consulta por reserva para resolver el mismo dato.
 */
@Entity
@Table(name = "reserva")
public class ReservationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private UserJpaEntity user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "itinerario_id", nullable = false)
    private ItineraryJpaEntity itinerary;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 20)
    private ReservationStatusJpa status;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "fecha_actualizacion", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false)
    private UUID idempotencyKey;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "reserva_pasajero",
            joinColumns = @JoinColumn(name = "reserva_id"),
            inverseJoinColumns = @JoinColumn(name = "pasajero_id"))
    private Set<PassengerJpaEntity> passengers = new LinkedHashSet<>();

    protected ReservationJpaEntity() {
        // Requerido por JPA.
    }

    public ReservationJpaEntity(
            UserJpaEntity user,
            ItineraryJpaEntity itinerary,
            ReservationStatusJpa status,
            Instant createdAt,
            Instant updatedAt,
            UUID idempotencyKey,
            Set<PassengerJpaEntity> passengers) {
        this.user = user;
        this.itinerary = itinerary;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.idempotencyKey = idempotencyKey;
        this.passengers = new LinkedHashSet<>(passengers);
    }

    public Long getId() {
        return id;
    }

    public UserJpaEntity getUser() {
        return user;
    }

    public ItineraryJpaEntity getItinerary() {
        return itinerary;
    }

    public ReservationStatusJpa getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Integer getVersion() {
        return version;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public Set<PassengerJpaEntity> getPassengers() {
        return passengers;
    }

    /**
     * Aplica los cambios que admite una modificación de reserva.
     *
     * <p>Sólo estado, itinerario y fecha de actualización: el usuario, la fecha
     * de creación, la clave de idempotencia y los pasajeros no cambian en las
     * operaciones que existen hoy, y las columnas están marcadas
     * {@code updatable = false} donde corresponde para que quede explícito.
     */
    public void apply(ReservationStatusJpa newStatus, ItineraryJpaEntity newItinerary, Instant newUpdatedAt) {
        this.status = newStatus;
        this.itinerary = newItinerary;
        this.updatedAt = newUpdatedAt;
    }
}
