package com.edteam.reservations.domain.model;

import com.edteam.reservations.domain.exception.InvalidUserException;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Usuario que opera el sistema y es dueño de sus reservas.
 *
 * <p>Es un agregado propio: la reserva lo referencia por {@link UserId}. Vive
 * en el dominio porque el sistema lo necesita para atribuir la reserva, pero su
 * ciclo de vida (alta, baja, modificación) no forma parte de este alcance.
 *
 * @param id           vacío mientras el usuario no esté persistido
 * @param registeredAt fecha de alta; la asigna el modelo de datos por defecto
 */
public record User(Optional<UserId> id, Email email, String firstName, String lastName, Instant registeredAt) {

    public User {
        Objects.requireNonNull(id, "El id es obligatorio (usar Optional.empty() si no está asignado)");
        Objects.requireNonNull(email, "El email es obligatorio");
        Objects.requireNonNull(registeredAt, "La fecha de alta es obligatoria");
        firstName = requireText(firstName, "nombre");
        lastName = requireText(lastName, "apellido");
    }

    public static User of(UserId id, Email email, String firstName, String lastName, Instant registeredAt) {
        return new User(Optional.of(id), email, firstName, lastName, registeredAt);
    }

    public String fullName() {
        return firstName + " " + lastName;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidUserException("El %s del usuario es obligatorio".formatted(field));
        }
        return value.trim();
    }
}
