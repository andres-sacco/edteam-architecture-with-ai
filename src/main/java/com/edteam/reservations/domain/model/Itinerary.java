package com.edteam.reservations.domain.model;

import com.edteam.reservations.domain.exception.InvalidItineraryException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Secuencia ordenada de segmentos con su precio.
 *
 * <p>El orden importa y es parte del modelo de datos ({@code itinerario_segmento.orden}):
 * EZE-SCL seguido de SCL-MAD es un itinerario con escala, y al revés es otro
 * viaje distinto. Acá el orden es la posición en la lista.
 *
 * <p>El precio vive en el itinerario y no en el segmento porque es el precio del
 * viaje completo: la misma combinación de tramos puede venderse a distinto
 * precio, y por eso el modelo de datos permite varios itinerarios sobre los
 * mismos segmentos.
 *
 * @param id vacío mientras el itinerario no esté persistido
 */
public record Itinerary(Optional<ItineraryId> id, Money price, List<Segment> segments) {

    public Itinerary {
        Objects.requireNonNull(id, "El id es obligatorio (usar Optional.empty() si no está asignado)");
        Objects.requireNonNull(price, "El precio es obligatorio");
        Objects.requireNonNull(segments, "Los segmentos son obligatorios");

        if (segments.isEmpty()) {
            throw new InvalidItineraryException("El itinerario debe tener al menos un segmento");
        }
        segments = List.copyOf(segments);
        validateNoDuplicates(segments);
        validateChaining(segments);
        validateChronology(segments);
    }

    /** Itinerario nuevo, todavía sin id. */
    public static Itinerary newItinerary(Money price, List<Segment> segments) {
        return new Itinerary(Optional.empty(), price, segments);
    }

    /** Itinerario ya persistido. */
    public static Itinerary existing(ItineraryId id, Money price, List<Segment> segments) {
        return new Itinerary(Optional.of(id), price, segments);
    }

    public Segment firstSegment() {
        return segments.getFirst();
    }

    public Segment lastSegment() {
        return segments.getLast();
    }

    public AirportCode origin() {
        return firstSegment().origin();
    }

    public AirportCode destination() {
        return lastSegment().destination();
    }

    public Instant firstDeparture() {
        return firstSegment().departureAt();
    }

    /** {@code true} si el viaje ya arrancó: el primer tramo salió. */
    public boolean hasDeparted(Instant reference) {
        return firstSegment().hasDeparted(reference);
    }

    /**
     * Todos los aeropuertos que toca el itinerario, sin repetir y en orden de
     * aparición. Es lo que se valida contra el maestro de aeropuertos.
     */
    public Set<AirportCode> airports() {
        Set<AirportCode> airports = new LinkedHashSet<>();
        for (Segment segment : segments) {
            airports.add(segment.origin());
            airports.add(segment.destination());
        }
        return Collections.unmodifiableSet(airports);
    }

    public ItinerarySummary summary() {
        return new ItinerarySummary(origin(), destination(), firstDeparture(), segments.size(), price);
    }

    private static void validateNoDuplicates(List<Segment> segments) {
        Set<String> seen = new HashSet<>();
        for (Segment segment : segments) {
            if (!seen.add(segment.naturalKey())) {
                throw new InvalidItineraryException(
                        "El segmento %s aparece más de una vez en el itinerario".formatted(segment.naturalKey()));
            }
        }
    }

    /** El destino de cada tramo tiene que ser el origen del siguiente. */
    private static void validateChaining(List<Segment> segments) {
        for (int i = 0; i < segments.size() - 1; i++) {
            Segment current = segments.get(i);
            Segment next = segments.get(i + 1);
            if (!current.destination().equals(next.origin())) {
                throw new InvalidItineraryException(
                        ("Los segmentos no se encadenan: el tramo %d llega a %s y el siguiente sale de %s")
                                .formatted(i + 1, current.destination(), next.origin()));
            }
        }
    }

    /** Cada tramo tiene que salir después del anterior. */
    private static void validateChronology(List<Segment> segments) {
        for (int i = 0; i < segments.size() - 1; i++) {
            Instant current = segments.get(i).departureAt();
            Instant next = segments.get(i + 1).departureAt();
            if (!next.isAfter(current)) {
                throw new InvalidItineraryException(
                        ("Los segmentos no están en orden cronológico: el tramo %d sale el %s "
                                        + "y el siguiente el %s")
                                .formatted(i + 1, current, next));
            }
        }
    }
}
