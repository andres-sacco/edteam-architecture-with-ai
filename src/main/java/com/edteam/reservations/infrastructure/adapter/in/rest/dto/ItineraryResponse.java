package com.edteam.reservations.infrastructure.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Itinerario reservado.
 *
 * <p>{@code origin}, {@code destination} y {@code firstDepartureAt} se derivan
 * de los tramos. Se exponen igual porque son lo que todo listado muestra, y
 * calcularlos obligaría a cada cliente a recorrer el arreglo y a conocer la
 * regla de que el primer tramo manda.
 */
@Schema(name = "Itinerary", description = "Itinerario reservado, con sus tramos en orden de vuelo.")
public record ItineraryResponse(
        @Schema(description = "Identificador opaco del itinerario.", example = "204")
        String id,

        MoneyResponse price,

        @Schema(description = "Origen del viaje: el del primer tramo.", example = "BUE")
        String origin,

        @Schema(description = "Destino del viaje: el del último tramo.", example = "MIA")
        String destination,

        @Schema(description = "Salida del primer tramo, en UTC.", example = "2027-03-15T22:40:00Z")
        Instant firstDepartureAt,

        @Schema(description = "Tramos en orden de vuelo.") List<SegmentResponse> segments) {}
