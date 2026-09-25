package com.edteam.reservations.infrastructure.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Página de reservas.
 *
 * <p>La colección viaja envuelta en un objeto y no como arreglo en la raíz:
 * así se le pueden agregar metadatos —hoy la paginación, mañana un cursor o
 * un total filtrado— sin romper a ningún cliente.
 */
@Schema(name = "ReservationPage", description = """
                Página de reservas.

                La colección viaja envuelta en un objeto y nunca como arreglo en la raíz,
                para poder agregarle metadatos sin romper a los clientes.""")
public record ReservationPageResponse(
        @Schema(description = "Reservas de la página, en el orden pedido.")
        List<ReservationResponse> items,

        PageMetadataResponse page) {}
