package com.edteam.reservations.domain.model;

import com.edteam.reservations.domain.exception.InvalidPassengerException;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Persona que viaja.
 *
 * <p>Es una entidad reutilizable entre reservas (relación N:M con la reserva a
 * través de {@code reserva_pasajero}): la misma persona puede aparecer en
 * varias reservas, y una reserva puede llevar varios pasajeros.
 *
 * <p>El documento es opcional —hay pasajeros sin documento cargado, por eso la
 * columna admite nulos— pero, cuando está, el modelo de datos lo declara
 * {@code UNIQUE}: es la clave natural que permite reconocer al mismo pasajero
 * en una reserva posterior en lugar de duplicarlo.
 *
 * @param id vacío mientras el pasajero no esté persistido
 */
public record Passenger(
        Optional<PassengerId> id,
        String firstName,
        String lastName,
        LocalDate birthDate,
        Optional<String> documentNumber) {

    /** Cota de sanidad: descarta fechas de nacimiento evidentemente erróneas. */
    private static final LocalDate EARLIEST_BIRTH_DATE = LocalDate.of(1900, 1, 1);

    private static final int MAX_DOCUMENT_LENGTH = 50;

    public Passenger {
        Objects.requireNonNull(id, "El id es obligatorio (usar Optional.empty() si no está asignado)");
        Objects.requireNonNull(documentNumber, "El documento es obligatorio (usar Optional.empty() si no hay)");
        firstName = requireText(firstName, "nombre");
        lastName = requireText(lastName, "apellido");

        if (birthDate == null) {
            throw new InvalidPassengerException("La fecha de nacimiento del pasajero es obligatoria");
        }
        if (birthDate.isBefore(EARLIEST_BIRTH_DATE)) {
            throw new InvalidPassengerException("La fecha de nacimiento %s es anterior al mínimo admitido (%s)"
                    .formatted(birthDate, EARLIEST_BIRTH_DATE));
        }

        Optional<String> normalizedDocument = documentNumber.map(String::trim);
        if (normalizedDocument.filter(String::isBlank).isPresent()) {
            throw new InvalidPassengerException("El documento, si se informa, no puede estar vacío");
        }
        if (normalizedDocument
                .filter(document -> document.length() > MAX_DOCUMENT_LENGTH)
                .isPresent()) {
            throw new InvalidPassengerException(
                    "El documento no puede superar los %d caracteres".formatted(MAX_DOCUMENT_LENGTH));
        }
        documentNumber = normalizedDocument;
    }

    /** Pasajero nuevo, todavía sin id. */
    public static Passenger newPassenger(
            String firstName, String lastName, LocalDate birthDate, String documentNumber) {
        return new Passenger(Optional.empty(), firstName, lastName, birthDate, Optional.ofNullable(documentNumber));
    }

    /** Pasajero ya persistido. */
    public static Passenger existing(
            PassengerId id, String firstName, String lastName, LocalDate birthDate, String documentNumber) {
        return new Passenger(Optional.of(id), firstName, lastName, birthDate, Optional.ofNullable(documentNumber));
    }

    public String fullName() {
        return firstName + " " + lastName;
    }

    /** {@code true} si la fecha de nacimiento es posterior al día indicado. */
    public boolean isBornAfter(LocalDate reference) {
        return birthDate.isAfter(Objects.requireNonNull(reference, "La fecha de referencia es obligatoria"));
    }

    /**
     * Clave con la que se decide si dos pasajeros son la misma persona.
     *
     * <p>Si hay documento, manda el documento (es la clave natural del modelo de
     * datos). Si no, se cae a nombre completo más fecha de nacimiento, que es lo
     * único que queda para detectar un duplicado dentro de la misma reserva.
     */
    public String identityKey() {
        return documentNumber
                .map(document -> "doc:" + document)
                .orElseGet(() -> "name:%s|%s|%s".formatted(firstName, lastName, birthDate));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidPassengerException("El %s del pasajero es obligatorio".formatted(field));
        }
        return value.trim();
    }
}
