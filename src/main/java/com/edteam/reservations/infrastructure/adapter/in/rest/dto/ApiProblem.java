package com.edteam.reservations.infrastructure.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.util.List;

/**
 * Forma del cuerpo de error, para el documento OpenAPI.
 *
 * <p>En tiempo de ejecución las respuestas de error son
 * {@code org.springframework.http.ProblemDetail}, que es lo correcto: implementa
 * RFC 7807 y Spring lo usa también para los errores que resuelve el framework.
 * Pero {@code ProblemDetail} guarda las extensiones en un mapa, así que
 * documentarlo directamente produciría un esquema con un
 * {@code properties: {}} y el cliente no se enteraría de que existen
 * {@code code} ni {@code errors}.
 *
 * <p>Este record no se serializa nunca: existe para que el generador tenga algo
 * concreto que describir. Que coincida con lo que realmente sale lo verifica
 * {@code OpenApiContractTest}, comparando este esquema contra el cuerpo de una
 * respuesta de error real.
 *
 * @param type     URI que identifica el tipo de problema
 * @param title    resumen legible del tipo de problema
 * @param status   código HTTP, repetido en el cuerpo para facilitar el logueo
 * @param code     código estable, legible por máquina
 * @param detail   explicación puntual de este error en particular
 * @param instance URI del pedido que produjo el error
 * @param errors   detalle campo por campo, si el error se puede atribuir a campos
 */
@Schema(name = "Problem", description = """
                Error en formato RFC 7807 (`application/problem+json`), extendido con un
                campo `code`.

                Los clientes deben decidir en base a `status` y `code`, nunca en base a
                `title` o `detail`: esos dos son texto para humanos y pueden cambiar o
                traducirse sin previo aviso.""")
public record ApiProblem(
        @Schema(
                description = "URI que identifica el tipo de problema.",
                example = "https://api.edteam.example/problems/reservation-not-found")
        URI type,

        @Schema(description = "Resumen legible del tipo de problema.", example = "Reserva inexistente")
        String title,

        @Schema(
                description = "Código HTTP, repetido en el cuerpo para facilitar el logueo.",
                example = "404",
                minimum = "400",
                maximum = "599")
        Integer status,

        @Schema(
                description = "Código de error estable. Es la parte del contrato de errores "
                        + "contra la que los clientes pueden programar.",
                example = "RESERVATION_NOT_FOUND")
        ApiErrorCode code,

        @Schema(description = "Explicación puntual de este error en particular.", example = "No existe la reserva 9999")
        String detail,

        @Schema(description = "URI del pedido que produjo el error.", example = "/v1/reservations/9999")
        URI instance,

        @Schema(description = """
                Detalle campo por campo, presente cuando el error se puede atribuir a
                campos concretos del pedido. Un error que no señala ningún campo —un
                usuario inexistente, un conflicto de concurrencia— no lo trae.""") List<FieldErrorResponse> errors) {}
