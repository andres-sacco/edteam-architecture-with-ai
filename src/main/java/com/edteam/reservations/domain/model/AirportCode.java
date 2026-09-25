package com.edteam.reservations.domain.model;

import com.edteam.reservations.domain.exception.InvalidAirportCodeException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Código IATA de aeropuerto (tres letras). El value object sólo valida el
 * <em>formato</em>; que el aeropuerto realmente exista es una regla que
 * necesita consultar el maestro de aeropuertos y vive en la capa de
 * aplicación, detrás de un puerto.
 */
public record AirportCode(String value) {

    private static final Pattern IATA = Pattern.compile("^[A-Z]{3}$");

    public AirportCode {
        if (value == null || value.isBlank()) {
            throw new InvalidAirportCodeException("El código de aeropuerto es obligatorio");
        }
        value = value.trim().toUpperCase(Locale.ROOT);
        if (!IATA.matcher(value).matches()) {
            throw new InvalidAirportCodeException(
                    "El código de aeropuerto '%s' no es un código IATA válido (3 letras)".formatted(value));
        }
    }

    public static AirportCode of(String value) {
        return new AirportCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
