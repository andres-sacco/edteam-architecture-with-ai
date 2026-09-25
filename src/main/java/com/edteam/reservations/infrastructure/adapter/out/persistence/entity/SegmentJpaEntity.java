package com.edteam.reservations.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Tabla {@code segmento}.
 *
 * <p>Se comparte entre itinerarios: la clave natural
 * {@code (origen, destino, aerolinea, fecha_vuelo)} es {@code UNIQUE}, y el
 * adaptador reutiliza la fila existente en lugar de insertar una nueva.
 *
 * <p>{@code fecha_vuelo} es {@code TIMESTAMP} sin zona y guarda UTC. Se fuerza
 * el tipo JDBC para que coincida exactamente con la columna; la conversión a
 * UTC la garantiza {@code hibernate.jdbc.time_zone}.
 */
@Entity
@Table(
        name = "segmento",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_segmento",
                        columnNames = {"origen", "destino", "aerolinea", "fecha_vuelo"}))
public class SegmentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "origen", nullable = false, length = 3)
    private String origin;

    @Column(name = "destino", nullable = false, length = 3)
    private String destination;

    @Column(name = "aerolinea", nullable = false, length = 50)
    private String airline;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "fecha_vuelo", nullable = false)
    private Instant departureAt;

    protected SegmentJpaEntity() {
        // Requerido por JPA.
    }

    public SegmentJpaEntity(Long id, String origin, String destination, String airline, Instant departureAt) {
        this.id = id;
        this.origin = origin;
        this.destination = destination;
        this.airline = airline;
        this.departureAt = departureAt;
    }

    public Long getId() {
        return id;
    }

    public String getOrigin() {
        return origin;
    }

    public String getDestination() {
        return destination;
    }

    public String getAirline() {
        return airline;
    }

    public Instant getDepartureAt() {
        return departureAt;
    }
}
