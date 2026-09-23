package com.edteam.reservations.infrastructure.adapter.out.persistence.entity;

import com.edteam.reservations.infrastructure.security.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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

    /**
     * Documento del pasajero, cifrado en la columna.
     *
     * <p>Dos cambios respecto de la versión anterior, y los dos son de
     * seguridad:
     *
     * <ul>
     *   <li>Ya no es {@code unique}. El {@code UNIQUE} convertía el alta en un
     *       oráculo: mandando un documento ajeno, la respuesta devolvía el
     *       nombre y la fecha de nacimiento reales de su titular, porque el
     *       adaptador reutilizaba la fila existente.</li>
     *   <li>Va cifrado (AES-256-GCM). Un {@code pg_dump} de esta tabla era un
     *       dump de PII en claro, y el backup heredaba el problema.</li>
     * </ul>
     *
     * <p>El largo sube a 512 porque el valor guardado es
     * {@code v1:} + Base64(IV ‖ ciphertext ‖ tag), no el documento.
     */
    @Column(name = "documento", length = 512)
    @Convert(converter = EncryptedStringConverter.class)
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
