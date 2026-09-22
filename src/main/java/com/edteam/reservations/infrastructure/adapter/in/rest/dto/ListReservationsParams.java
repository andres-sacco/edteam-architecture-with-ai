package com.edteam.reservations.infrastructure.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;
import java.util.List;

/**
 * Parámetros de consulta del listado de reservas.
 *
 * <p>Se agrupan en un objeto en lugar de declararlos sueltos en la firma del
 * controller por dos razones: la validación declarativa produce un
 * {@code MethodArgumentNotValidException} con el campo señalado —el mismo
 * camino que los cuerpos JSON, así que el 400 sale con la misma forma—, y la
 * regla que cruza dos parámetros ({@code departureFrom} ≤ {@code departureTo})
 * necesita verlos juntos.
 *
 * <p>Los valores por defecto se resuelven en el constructor compacto: el binder
 * pasa {@code null} por cada parámetro ausente. La paginación no es opcional,
 * así que nunca se devuelve la colección entera.
 *
 * <p>Hay un solo constructor a propósito: el binder de Spring construye el
 * record por su constructor canónico y no sabe elegir si hay más de uno.
 */
public record ListReservationsParams(

        @Schema(description = "Devuelve sólo las reservas del usuario indicado. "
                + "El usuario se identifica por su email, igual que en el alta.",
                example = "ana.perez@example.com")
        @Email(message = "Debe ser una dirección de correo válida")
        @Size(max = 150, message = "El email no puede superar los 150 caracteres")
        String userId,

        @Schema(description = "Devuelve sólo las reservas en alguno de los estados indicados. "
                + "Repetible: `?status=PENDING&status=CONFIRMED`.")
        List<ReservationStatusDto> status,

        @Schema(description = "Devuelve las reservas cuyo primer tramo despega en esta "
                + "fecha/hora o después.",
                example = "2027-03-01T00:00:00Z")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant departureFrom,

        @Schema(description = "Devuelve las reservas cuyo primer tramo despega en esta "
                + "fecha/hora o antes. Si es anterior a `departureFrom`, la respuesta es 400.",
                example = "2027-03-31T23:59:59Z")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant departureTo,

        @Schema(description = "Número de página, base 0.", defaultValue = "0")
        @Min(value = 0, message = "La página no puede ser negativa")
        Integer page,

        @Schema(description = "Cantidad de elementos por página.", defaultValue = "20")
        @Min(value = 1, message = "El tamaño de página debe ser al menos 1")
        @Max(value = 100, message = "El tamaño de página no puede superar 100")
        Integer size,

        @Schema(description = "Criterio de ordenamiento, como `campo,dirección`. Los campos "
                + "ordenables son parte del contrato y no del modelo interno.",
                defaultValue = "createdAt,desc",
                allowableValues = {"createdAt,asc", "createdAt,desc",
                        "firstDepartureAt,asc", "firstDepartureAt,desc"})
        @Pattern(regexp = ApiFormats.SORT,
                message = "Valores admitidos: createdAt|firstDepartureAt seguido de asc|desc")
        String sort) {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final String DEFAULT_SORT = "createdAt,desc";

    public ListReservationsParams {
        status = status == null ? List.of() : List.copyOf(status);
        page = page == null ? DEFAULT_PAGE : page;
        size = size == null ? DEFAULT_SIZE : size;
        sort = sort == null || sort.isBlank() ? DEFAULT_SORT : sort;
    }

    /** Parámetros por defecto: primera página, sin filtros. */
    public static ListReservationsParams defaults() {
        return new ListReservationsParams(null, null, null, null, null, null, null);
    }

    /**
     * Regla que cruza dos parámetros. Se valida como el resto para que el error
     * salga con la misma forma que los demás y apuntando a un campo.
     */
    @Schema(hidden = true)
    @AssertTrue(message = "departureFrom no puede ser posterior a departureTo")
    public boolean isDepartureRangeOrdered() {
        return departureFrom == null || departureTo == null || !departureFrom.isAfter(departureTo);
    }
}
