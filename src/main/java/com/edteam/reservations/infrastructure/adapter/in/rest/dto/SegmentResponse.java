package com.edteam.reservations.infrastructure.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Tramo del itinerario.
 *
 * <p>A diferencia del pedido, la respuesta sí lleva {@code position}: el
 * cliente la necesita para mostrar "tramo 1 de 2" sin depender de que su
 * parser preserve el orden del arreglo.
 */
@Schema(name = "Segment", description = "Tramo del itinerario reservado.")
public record SegmentResponse(

        @Schema(description = "Identificador opaco del tramo.", example = "310")
        String id,

        @Schema(description = "Posición del tramo dentro del itinerario, base 1.", example = "1")
        int position,

        @Schema(description = "Código IATA del aeropuerto de origen.", example = "EZE")
        String originAirportCode,

        @Schema(description = "Código IATA del aeropuerto de destino.", example = "GRU")
        String destinationAirportCode,

        @Schema(description = "Aerolínea operadora.", example = "AR")
        String airline,

        @Schema(description = "Fecha y hora de salida, en UTC.", example = "2027-03-15T22:40:00Z")
        Instant departureAt) {
}
