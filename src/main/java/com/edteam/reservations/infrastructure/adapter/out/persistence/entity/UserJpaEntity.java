package com.edteam.reservations.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Tabla {@code usuario}.
 *
 * <p>Todavía no hay casos de uso sobre el usuario: la reserva sólo lo
 * referencia por id y la clave foránea garantiza que exista. La entidad está
 * porque es parte del modelo de datos y porque es el punto de partida del día
 * que aparezca "mis reservas" o el alta de usuarios.
 */
@Entity
@Table(name = "usuario")
public class UserJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, unique = true, length = 150)
    private String email;

    @Column(name = "nombre", nullable = false, length = 100)
    private String firstName;

    @Column(name = "apellido", nullable = false, length = 100)
    private String lastName;

    @Column(name = "fecha_alta", nullable = false)
    private Instant registeredAt;

    protected UserJpaEntity() {
        // Requerido por JPA.
    }

    public UserJpaEntity(Long id, String email, String firstName, String lastName, Instant registeredAt) {
        this.id = id;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.registeredAt = registeredAt;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }
}
