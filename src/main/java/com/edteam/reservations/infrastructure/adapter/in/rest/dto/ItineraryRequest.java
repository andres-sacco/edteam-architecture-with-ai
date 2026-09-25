package com.edteam.reservations.infrastructure.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Itinerario a reservar.
 *
 * <p>El precio viaja como string decimal y no como número JSON: los clientes
 * JavaScript parsean los números como {@code double} y {@code 1350.10} deja de
 * ser exactamente eso. La validación de formato ocurre acá; la de negocio
 * (rango, escala) la hace {@code Money} en el dominio.
 */
@Schema(name = "ItineraryRequest", description = """
                Itinerario a reservar.

                El orden del arreglo `segments` **es** el orden de vuelo: no se envía un
                campo de posición, para que no haya dos fuentes de verdad que puedan
                contradecirse.

                Reglas que validan el 400: cada tramo debe tener origen distinto del
                destino, las salidas deben ser crecientes, el destino de un tramo debe ser
                el origen del siguiente, y todos los códigos deben existir en el catálogo
                de aeropuertos.""")
public record ItineraryRequest(
        @Schema(description = "Precio total del itinerario, en decimal exacto como string.", example = "1350.00")
        @NotBlank(message = "El precio es obligatorio")
        @Pattern(
                regexp = ApiFormats.DECIMAL_AMOUNT,
                message = "Debe ser un importe con hasta 8 dígitos enteros y 2 decimales")
        String price,

        @Schema(description = "Código de moneda ISO 4217.", example = "USD")
        @NotBlank(message = "La moneda es obligatoria")
        @Pattern(regexp = ApiFormats.CURRENCY_CODE, message = "Debe ser un código ISO 4217 de 3 letras mayúsculas")
        String currency,

        @ArraySchema(arraySchema = @Schema(description = "Tramos en orden de vuelo. El primero sale del origen."))
        @NotEmpty(message = "El itinerario debe tener al menos un tramo")
        // Ver la nota en CreateReservationRequest: el mínimo tiene que estar en
        // @Size para que llegue al documento como minItems: 1.
        @Size(min = 1, max = 10, message = "El itinerario debe tener entre 1 y 10 tramos")
        @Valid
        List<SegmentRequest> segments) {}
