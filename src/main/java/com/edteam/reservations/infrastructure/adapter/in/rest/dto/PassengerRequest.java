package com.edteam.reservations.infrastructure.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Pasajero de la reserva, tal como llega en el cuerpo del pedido. */
@Schema(name = "PassengerRequest", description = "Pasajero a incluir en la reserva.")
public record PassengerRequest(
        @Schema(description = "Nombre del pasajero.", example = "Ana")
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 100, message = "El nombre no puede superar los 100 caracteres")
        String firstName,

        @Schema(description = "Apellido del pasajero.", example = "Pérez")
        @NotBlank(message = "El apellido es obligatorio")
        @Size(max = 100, message = "El apellido no puede superar los 100 caracteres")
        String lastName,

        @Schema(description = "Fecha de nacimiento, sin hora ni zona horaria.", example = "1990-04-17")
        @NotNull(message = "La fecha de nacimiento es obligatoria")
        @Past(message = "La fecha de nacimiento debe ser pasada")
        LocalDate birthDate,

        @Schema(description = "Número de documento. Opcional; si se envía, identifica "
                + "unívocamente al pasajero y permite reconocerlo en reservas posteriores.",
                example = "30123456")
        @Size(max = 50, message = "El documento no puede superar los 50 caracteres")
        String documentNumber) {
}
