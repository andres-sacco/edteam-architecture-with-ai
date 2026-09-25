package com.edteam.reservations.domain.model;

import com.edteam.reservations.domain.exception.InvalidUserException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Dirección de correo del usuario.
 *
 * <p>Se normaliza a minúsculas porque el modelo de datos tiene un
 * {@code UNIQUE} sobre {@code usuario.email}: sin normalizar, el mismo correo
 * en distinta capitalización entraría dos veces.
 */
public record Email(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");

    private static final int MAX_LENGTH = 150;

    public Email {
        if (value == null || value.isBlank()) {
            throw new InvalidUserException("El email es obligatorio");
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_LENGTH) {
            throw new InvalidUserException("El email no puede superar los %d caracteres".formatted(MAX_LENGTH));
        }
        if (!FORMAT.matcher(value).matches()) {
            throw new InvalidUserException("El email '%s' no tiene un formato válido".formatted(value));
        }
    }

    public static Email of(String value) {
        return new Email(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
