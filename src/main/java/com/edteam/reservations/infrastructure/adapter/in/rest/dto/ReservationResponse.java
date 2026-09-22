package com.edteam.reservations.infrastructure.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Representación de una reserva.
 *
 * <p>Dos cosas que este DTO deliberadamente <em>no</em> expone:
 *
 * <ul>
 *   <li><b>La versión.</b> Es control de concurrencia, no un dato de la
 *       reserva: viaja en el header {@code ETag} y vuelve en {@code If-Match}.
 *       Si estuviera en el cuerpo, el cliente tendría dos mecanismos para lo
 *       mismo y el número de versión de la base pasaría a ser parte del
 *       contrato.</li>
 *   <li><b>La clave de idempotencia.</b> Identifica un intento de creación, no
 *       la reserva; no le sirve a quien la lee.</li>
 * </ul>
 *
 * <p>Los identificadores son strings opacos: que en la base sean enteros es un
 * detalle que la API no filtra, y así se pueden migrar a otro formato sin
 * romper clientes.
 *
 * @param userId      dueño de la reserva, identificado por su email. Es el
 *                    mismo valor que se envía al crearla y el que filtra el
 *                    listado
 * @param cancelledAt momento de la cancelación; {@code null} si la reserva no
 *                    está cancelada
 */
@Schema(name = "Reservation",
        description = """
                Representación de una reserva.

                No incluye el número de versión interno: la concurrencia se maneja con
                `ETag` / `If-Match`. Tampoco la clave de idempotencia, que identifica un
                intento de creación y no le sirve a quien lee la reserva.""")
public record ReservationResponse(

        @Schema(description = "Identificador opaco de la reserva.", example = "1042")
        String id,

        ReservationStatusDto status,

        @Schema(description = """
                Identificador del usuario dueño de la reserva: su email.

                Es el mismo valor que se envía en `user.email` al crear la reserva, y el
                que se pasa como `userId` para filtrar el listado. El identificador
                interno de la base no se expone: el email es lo que el cliente conoce y
                lo que no cambia de significado entre entornos.

                El nombre y el apellido del usuario no forman parte de este recurso.""",
                example = "ana.perez@example.com")
        String userId,

        ItineraryResponse itinerary,

        @Schema(description = "Pasajeros de la reserva, al menos uno.")
        List<PassengerResponse> passengers,

        @Schema(description = "Momento de creación, en UTC.", example = "2026-09-22T14:03:11Z")
        Instant createdAt,

        @Schema(description = "Momento de la última modificación, en UTC.",
                example = "2026-09-22T14:03:11Z")
        Instant updatedAt,

        @Schema(description = "Momento de la cancelación, en UTC. Nulo si la reserva no "
                + "está cancelada.",
                example = "null", nullable = true)
        Instant cancelledAt) {
}
