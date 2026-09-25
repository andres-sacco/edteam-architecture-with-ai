package com.edteam.reservations.infrastructure.adapter.in.rest.dto;

import com.edteam.reservations.domain.model.ReservationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Estado de la reserva tal como lo ve el cliente de la API.
 *
 * <p>Duplica los valores del enum del dominio a propósito: son dos contratos
 * distintos. Que hoy coincidan es una coincidencia cómoda; el día que el
 * dominio agregue un estado intermedio, la API puede seguir exponiendo los
 * tres que sus clientes conocen hasta que se decida versionar.
 */
@Schema(name = "ReservationStatus", description = """
                Estado de la reserva dentro del contrato de la API. El conjunto de valores
                es propio de la API: los literales que use el almacenamiento interno no se
                filtran acá.

                - `PENDING`: creada, todavía sin confirmar.
                - `CONFIRMED`: confirmada y vigente.
                - `CANCELLED`: cancelada; el registro se conserva por trazabilidad.

                Los clientes deben tolerar valores nuevos: agregar un estado es un cambio
                compatible dentro de `/v1`.""", example = "PENDING")
public enum ReservationStatusDto {
    PENDING,
    CONFIRMED,
    CANCELLED;

    public static ReservationStatusDto from(ReservationStatus status) {
        return switch (status) {
            case PENDING -> PENDING;
            case CONFIRMED -> CONFIRMED;
            case CANCELLED -> CANCELLED;
        };
    }

    public ReservationStatus toDomain() {
        return switch (this) {
            case PENDING -> ReservationStatus.PENDING;
            case CONFIRMED -> ReservationStatus.CONFIRMED;
            case CANCELLED -> ReservationStatus.CANCELLED;
        };
    }
}
