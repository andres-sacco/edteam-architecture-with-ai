package com.edteam.reservations.domain.model;

import com.edteam.reservations.domain.exception.InvalidUserException;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Usuario que opera el sistema y es dueño de sus reservas.
 *
 * <p>Es un agregado propio: la reserva lo referencia por {@link UserId}.
 *
 * <p>Su identidad de negocio es el {@link Email}, no el id: el modelo de datos
 * lo declara {@code UNIQUE} y es lo que permite reconocer a la misma persona
 * en una reserva posterior en lugar de duplicarla. El id lo asigna la base.
 *
 * <p>Del ciclo de vida sólo existe el alta, y ocurre como parte de reservar:
 * la baja y la modificación del perfil no forman parte de este alcance.
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

    /**
     * Usuario todavía sin persistir: un candidato a dar de alta.
     *
     * <p>Mismo patrón que {@code Segment.newSegment} y
     * {@code Passenger.newPassenger}: el adaptador de salida se encarga de
     * reutilizar la fila que ya exista para ese email, o de crearla.
     */
    public static User newUser(Email email, String firstName, String lastName, Instant registeredAt) {
        return new User(Optional.empty(), email, firstName, lastName, registeredAt);
    }

    /**
     * Id del usuario, exigiendo que esté asignado.
     *
     * @throws IllegalStateException si el usuario todavía no se persistió
     */
    public UserId requireId() {
        return id.orElseThrow(() -> new IllegalStateException(
                "El usuario %s todavía no tiene id asignado".formatted(email)));
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
