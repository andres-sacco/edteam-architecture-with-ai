package com.edteam.reservations.application.port.in;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Datos del itinerario tal como los recibe el caso de uso: precio, moneda y los
 * tramos en orden.
 *
 * <p>El orden de la lista es el orden del viaje; se traslada tal cual a la
 * columna {@code itinerario_segmento.orden}.
 */
public record ItineraryData(BigDecimal price, String currency, List<SegmentData> segments) {

    public ItineraryData {
        Objects.requireNonNull(segments, "segments es obligatorio");
        segments = List.copyOf(segments);
    }
}
