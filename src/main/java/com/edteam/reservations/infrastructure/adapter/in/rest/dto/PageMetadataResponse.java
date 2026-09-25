package com.edteam.reservations.infrastructure.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Metadatos de paginación.
 *
 * @param number        número de la página devuelta, base 0
 * @param size          tamaño de página solicitado
 * @param totalElements total de elementos que cumplen el filtro
 * @param totalPages    total de páginas para ese tamaño
 */
@Schema(name = "PageMetadata", description = "Metadatos de paginación de un listado.")
public record PageMetadataResponse(
        @Schema(description = "Número de la página devuelta, base 0.", example = "0")
        int number,

        @Schema(description = "Tamaño de página solicitado.", example = "20")
        int size,

        @Schema(description = "Cantidad total de reservas que cumplen el filtro.", example = "137")
        long totalElements,

        @Schema(description = "Cantidad total de páginas para el tamaño pedido.", example = "7")
        int totalPages) {}
