package com.edteam.reservations.infrastructure.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** Pasajero incluido en la reserva. */
@Schema(name = "Passenger", description = "Pasajero incluido en la reserva.")
public record PassengerResponse(
        @Schema(description = "Identificador opaco del pasajero.", example = "118")
        String id,

        @Schema(example = "Ana") String firstName,

        @Schema(example = "Pérez") String lastName,

        @Schema(description = "Fecha de nacimiento, sin hora ni zona horaria.", example = "1990-04-17")
        LocalDate birthDate,

        @Schema(
                description = "Número de documento. Nulo si el pasajero no lo tiene cargado.",
                example = "30123456",
                nullable = true)
        String documentNumber) {}
