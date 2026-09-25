package com.edteam.reservations.infrastructure.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Cuerpo de la actualización de una reserva.
 *
 * <p>Sólo el itinerario: el usuario y los pasajeros son parte de la identidad
 * comercial de la reserva, y cambiarlos implica cancelar y reservar de nuevo.
 * El caso de uso que hay detrás ({@code ModifyReservationUseCase}) tampoco
 * admite otra cosa.
 */
@Schema(name = "UpdateReservationRequest", description = """
                Datos para actualizar una reserva.

                Sólo el itinerario es modificable: el usuario dueño y los pasajeros forman
                parte de la identidad comercial de la reserva, y cambiarlos exige cancelar
                y reservar de nuevo.""")
public record UpdateReservationRequest(
        @NotNull(message = "El itinerario es obligatorio") @Valid
        ItineraryRequest itinerary) {}
