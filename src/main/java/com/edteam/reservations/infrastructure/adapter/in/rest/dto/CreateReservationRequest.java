package com.edteam.reservations.infrastructure.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Cuerpo del alta de una reserva.
 *
 * <p>La clave de idempotencia no está acá: viaja en el header
 * {@code Idempotency-Key}, porque describe el intento de ejecución y no el
 * recurso que se crea. Ponerla en el cuerpo obligaría a incluirla también en
 * las representaciones de salida, donde no significa nada para el cliente.
 *
 * <p>El usuario viaja con sus datos y no como id: ver {@link UserRequest}.
 */
@Schema(name = "CreateReservationRequest",
        description = """
                Datos para crear una reserva.

                La clave de idempotencia **no** va acá: viaja en el header
                `Idempotency-Key`, porque describe el intento de ejecución y no el recurso
                que se está creando.""")
public record CreateReservationRequest(
        @NotNull(message = "El usuario es obligatorio")
        @Valid
        UserRequest user,

        @NotNull(message = "El itinerario es obligatorio")
        @Valid
        ItineraryRequest itinerary,

        @ArraySchema(arraySchema = @Schema(description = "Pasajeros de la reserva."))
        @NotEmpty(message = "Se requiere al menos un pasajero")
        // El mínimo va en @Size y no en @ArraySchema: el generador deriva
        // minItems/maxItems de las restricciones de validación y pisa lo que
        // declare la anotación de documentación. Con @NotEmpty solo, el
        // documento anunciaría minItems: 0 y mentiría.
        @Size(min = 1, max = 9, message = "Se admiten entre 1 y 9 pasajeros por reserva")
        @Valid
        List<PassengerRequest> passengers) {
}
