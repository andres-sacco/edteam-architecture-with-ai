package com.edteam.reservations.infrastructure.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Tramo de un itinerario, tal como llega en el cuerpo del pedido.
 *
 * <p>No tiene campo de posición: el orden es el del arreglo. Tener las dos
 * cosas abriría la puerta a que se contradigan.
 */
@Schema(name = "SegmentRequest", description = "Tramo de un itinerario a reservar.")
public record SegmentRequest(
        @Schema(description = "Código IATA de la ciudad de origen. Se valida contra el catálogo.", example = "BUE")
        @NotBlank(message = "El origen es obligatorio")
        @Pattern(regexp = ApiFormats.AIRPORT_CODE, message = "Debe ser un código IATA de 3 letras mayúsculas")
        String originAirportCode,

        @Schema(description = "Código IATA de la ciudad de destino. Se valida contra el catálogo.", example = "SCL")
        @NotBlank(message = "El destino es obligatorio")
        @Pattern(regexp = ApiFormats.AIRPORT_CODE, message = "Debe ser un código IATA de 3 letras mayúsculas")
        String destinationAirportCode,

        @Schema(description = "Aerolínea operadora del tramo.", example = "AR")
        @NotBlank(message = "La aerolínea es obligatoria")
        @Size(max = 50, message = "La aerolínea no puede superar los 50 caracteres")
        String airline,

        @Schema(description = "Fecha y hora de salida, en UTC. Debe ser futura.",
                example = "2027-03-15T22:40:00Z")
        @NotNull(message = "La fecha de salida es obligatoria")
        Instant departureAt) {
}
