package com.edteam.reservations.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Resumen del itinerario que viaja en los eventos de dominio.
 *
 * <p>Los eventos no llevan el itinerario completo: el consumidor de
 * notificaciones necesita saber qué se reservó, no la lista entera de tramos.
 * Mandar menos también evita romper a los consumidores cada vez que cambia el
 * detalle interno del itinerario.
 */
public record ItinerarySummary(AirportCode origin,
                               AirportCode destination,
                               Instant firstDeparture,
                               int segmentCount,
                               Money price) {

    public ItinerarySummary {
        Objects.requireNonNull(origin, "El origen es obligatorio");
        Objects.requireNonNull(destination, "El destino es obligatorio");
        Objects.requireNonNull(firstDeparture, "La fecha de salida es obligatoria");
        Objects.requireNonNull(price, "El precio es obligatorio");
        if (segmentCount < 1) {
            throw new IllegalArgumentException("El itinerario debe tener al menos un segmento");
        }
    }

    /** {@code true} si el itinerario tiene escalas. */
    public boolean hasConnections() {
        return segmentCount > 1;
    }
}
