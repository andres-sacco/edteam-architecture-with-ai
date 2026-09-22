package com.edteam.reservations.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Tabla {@code itinerario}.
 *
 * <p>La relación con los segmentos se mapea con {@code @ManyToMany} sobre la
 * tabla intermedia {@code itinerario_segmento} y {@code @OrderColumn}: esa
 * columna {@code orden} es parte de la clave primaria de la intermedia, así que
 * el orden de los tramos queda garantizado por el modelo de datos y no por el
 * orden en que la base devuelva las filas.
 *
 * <p>Es {@code @ManyToMany} y no {@code @OneToMany} porque el mismo segmento
 * pertenece a varios itinerarios; por eso tampoco hay cascada de borrado hacia
 * el segmento.
 */
@Entity
@Table(name = "itinerario")
public class ItineraryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "precio", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "moneda", nullable = false, length = 3)
    private String currency;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "itinerario_segmento",
            joinColumns = @JoinColumn(name = "itinerario_id"),
            inverseJoinColumns = @JoinColumn(name = "segmento_id"))
    @OrderColumn(name = "orden")
    private List<SegmentJpaEntity> segments = new ArrayList<>();

    protected ItineraryJpaEntity() {
        // Requerido por JPA.
    }

    public ItineraryJpaEntity(Long id, BigDecimal price, String currency, List<SegmentJpaEntity> segments) {
        this.id = id;
        this.price = price;
        this.currency = currency;
        this.segments = new ArrayList<>(segments);
    }

    public Long getId() {
        return id;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getCurrency() {
        return currency;
    }

    public List<SegmentJpaEntity> getSegments() {
        return segments;
    }
}
