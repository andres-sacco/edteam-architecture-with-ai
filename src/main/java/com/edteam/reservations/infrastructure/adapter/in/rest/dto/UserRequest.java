package com.edteam.reservations.infrastructure.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Quien reserva.
 *
 * <p>No lleva id a propósito. La API no expone un alta de usuarios, así que
 * pedir un identificador obligaría al cliente a conocer de antemano una fila
 * que quizá no existe —y a cargarla por fuera del sistema—. El email es la
 * clave natural: si ya reservó antes, se reconoce al mismo usuario; si no, se
 * lo da de alta como parte de la reserva.
 */
@Schema(name = "UserRequest",
        description = """
                Datos de quien reserva.

                Se lo identifica por email. Si ya existe un usuario con ese email se
                reutiliza —y su nombre y apellido almacenados no se modifican—; si no,
                se lo da de alta junto con la reserva.

                El email es también el identificador del usuario en el resto de la API:
                vuelve en `userId` de la respuesta y es lo que se pasa como `userId` para
                filtrar el listado. El identificador interno de la base no se expone.""")
public record UserRequest(

        @Schema(description = "Email de quien reserva. Es su identificador de negocio.",
                example = "ana.perez@example.com")
        @NotBlank(message = "El email es obligatorio")
        @Email(message = "Debe ser una dirección de correo válida")
        @Size(max = 150, message = "El email no puede superar los 150 caracteres")
        String email,

        @Schema(description = "Nombre de quien reserva.", example = "Ana")
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 100, message = "El nombre no puede superar los 100 caracteres")
        String firstName,

        @Schema(description = "Apellido de quien reserva.", example = "Pérez")
        @NotBlank(message = "El apellido es obligatorio")
        @Size(max = 100, message = "El apellido no puede superar los 100 caracteres")
        String lastName) {
}
