package com.edteam.reservations.infrastructure.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Un campo rechazado por la validación.
 *
 * <p>Va dentro del {@code errors} del cuerpo de error, y es lo que le permite
 * a un formulario marcar el campo exacto en lugar de mostrar un cartel
 * genérico.
 *
 * @param field   ruta del campo dentro del cuerpo del pedido
 * @param code    motivo del rechazo, legible por máquina
 * @param message explicación legible por humanos
 */
@Schema(name = "FieldError", description = "Un campo rechazado por la validación del pedido.")
public record FieldErrorResponse(

        @Schema(description = "Ruta del campo inválido dentro del cuerpo del pedido, "
                + "en notación de punto y corchetes.",
                example = "itinerary.segments[0].originAirportCode")
        String field,

        @Schema(description = "Motivo del rechazo, legible por máquina.", example = "PATTERN")
        String code,

        @Schema(description = "Explicación legible por humanos.",
                example = "Debe ser un código IATA de 3 letras mayúsculas")
        String message) {
}
