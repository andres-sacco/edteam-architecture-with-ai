package com.edteam.reservations.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * Tabla {@code pasajero}.
 *
 * <p>El documento es {@code UNIQUE} y admite nulos: es la clave natural que
 * permite reconocer al mismo pasajero en una reserva posterior en lugar de
 * duplicarlo.
 */
@Entity
@Table(name = "pasajero")
public class PassengerJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre", nullable = false, length = 100)
    private String firstName;

    @Column(name = "apellido", nullable = false, length = 100)
    private String lastName;

    @Column(name = "fecha_nacimiento", nullable = false)
    private LocalDate birthDate;

    @Column(name = "documento", unique = true, length = 50)
    private String documentNumber;

    protected PassengerJpaEntity() {
        // Requerido por JPA.
    }

    public PassengerJpaEntity(Long id, String firstName, String lastName, LocalDate birthDate, String documentNumber) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.birthDate = birthDate;
        this.documentNumber = documentNumber;
    }

    public Long getId() {
        return id;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public String getDocumentNumber() {
        return documentNumber;
    }
}
