package com.edteam.reservations.domain.model;

import com.edteam.reservations.domain.exception.InvalidSegmentException;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Tramo de vuelo: origen, destino, aerolínea y fecha de salida.
 *
 * <p>Es compartido entre itinerarios: el vuelo EZE-SCL de mañana es el mismo
 * hecho para todas las reservas que lo incluyan. Por eso el modelo de datos lo
 * declara {@code UNIQUE (origen, destino, aerolinea, fecha_vuelo)} —esa es su
 * clave natural, expresada acá como {@link #naturalKey()}— y el adaptador de
 * persistencia reutiliza la fila existente en lugar de duplicarla.
 *
 * <p>Sólo hay fecha de salida: el modelo de datos no registra la de llegada, así
 * que el dominio tampoco la inventa.
 *
 * @param id vacío mientras el segmento no esté persistido
 */
public record Segment(
        Optional<SegmentId> id, AirportCode origin, AirportCode destination, String airline, Instant departureAt) {

    private static final int MAX_AIRLINE_LENGTH = 50;

    public Segment {
        Objects.requireNonNull(id, "El id es obligatorio (usar Optional.empty() si no está asignado)");
        Objects.requireNonNull(origin, "El origen es obligatorio");
        Objects.requireNonNull(destination, "El destino es obligatorio");
        Objects.requireNonNull(departureAt, "La fecha de vuelo es obligatoria");

        if (airline == null || airline.isBlank()) {
            throw new InvalidSegmentException("La aerolínea es obligatoria");
        }
        airline = airline.trim().toUpperCase(Locale.ROOT);
        if (airline.length() > MAX_AIRLINE_LENGTH) {
            throw new InvalidSegmentException(
                    "La aerolínea no puede superar los %d caracteres".formatted(MAX_AIRLINE_LENGTH));
        }
        if (origin.equals(destination)) {
            throw new InvalidSegmentException(
                    "El origen y el destino no pueden ser el mismo aeropuerto (%s)".formatted(origin));
        }
    }

    /** Segmento nuevo, todavía sin id. */
    public static Segment newSegment(AirportCode origin, AirportCode destination, String airline, Instant departureAt) {
        return new Segment(Optional.empty(), origin, destination, airline, departureAt);
    }

    /** Segmento ya persistido. */
    public static Segment existing(
            SegmentId id, AirportCode origin, AirportCode destination, String airline, Instant departureAt) {
        return new Segment(Optional.of(id), origin, destination, airline, departureAt);
    }

    /**
     * Clave natural del segmento, la misma que el {@code UNIQUE} del modelo de
     * datos. Dos segmentos con la misma clave son el mismo vuelo, tengan o no
     * el mismo id asignado.
     */
    public String naturalKey() {
        return "%s|%s|%s|%s".formatted(origin, destination, airline, departureAt);
    }

    /** {@code true} si el tramo ya salió respecto del instante indicado. */
    public boolean hasDeparted(Instant reference) {
        Objects.requireNonNull(reference, "El instante de referencia es obligatorio");
        return !departureAt.isAfter(reference);
    }
}
