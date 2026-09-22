package com.edteam.reservations.application.port.in;

import java.time.Instant;
import java.util.Objects;

/**
 * Datos de un tramo tal como los recibe el caso de uso.
 *
 * <p>Tipos primitivos a propósito: es el contrato con los adaptadores de
 * entrada, que no deben construir value objects del dominio. La traducción
 * —con sus validaciones— ocurre en un solo lugar.
 */
public record SegmentData(String originAirportCode,
                          String destinationAirportCode,
                          String airline,
                          Instant departureAt) {

    public SegmentData {
        Objects.requireNonNull(departureAt, "departureAt es obligatorio");
    }
}
