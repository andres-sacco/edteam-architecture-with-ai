package com.edteam.reservations.infrastructure.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.util.Locale;

/**
 * Códigos de error del contrato.
 *
 * <p>Es la parte del cuerpo de error contra la que los clientes pueden
 * programar. El {@code title} y el {@code detail} son texto para humanos y
 * pueden cambiar o traducirse; el código, no: sacar uno es un cambio
 * incompatible.
 *
 * <p>Cada código lleva su {@code type}, la URI que RFC 7807 usa para
 * identificar el tipo de problema.
 */
@Schema(name = "ErrorCode",
        description = """
                Código de error estable. Es la parte del contrato de errores contra la que
                los clientes pueden programar.

                Los clientes deben tolerar códigos desconocidos —agregar uno nuevo es un
                cambio compatible— y en ese caso comportarse según el `status` HTTP.

                `RESOURCE_NOT_FOUND`, `UNSUPPORTED_REQUEST` e `INTERNAL_ERROR` no
                corresponden a ninguna regla de negocio: aparecen cuando el pedido no
                llega a ninguna operación o cuando algo falla del lado del servidor.""",
        example = "RESERVATION_NOT_FOUND")
public enum ApiErrorCode {

    VALIDATION_ERROR("Pedido inválido"),
    UNKNOWN_AIRPORT("Aeropuerto desconocido"),
    INVALID_ITINERARY("Itinerario inválido"),
    INVALID_PASSENGER("Pasajero inválido"),
    INVALID_RESERVATION("Reserva inválida"),
    USER_NOT_FOUND("Usuario inexistente"),
    RESERVATION_NOT_FOUND("Reserva inexistente"),
    IDEMPOTENCY_KEY_REUSED("Clave de idempotencia reutilizada"),
    CONCURRENT_UPDATE("Conflicto de concurrencia"),
    RESERVATION_ALREADY_CANCELLED("Reserva ya cancelada"),
    RESERVATION_NOT_MODIFIABLE("Reserva no modificable"),
    ITINERARY_ALREADY_DEPARTED("El itinerario ya salió"),
    RESOURCE_NOT_FOUND("Recurso inexistente"),
    UNSUPPORTED_REQUEST("Pedido no soportado"),
    INTERNAL_ERROR("Error interno");

    private static final String PROBLEM_BASE = "https://api.edteam.example/problems/";

    private final String title;

    ApiErrorCode(String title) {
        this.title = title;
    }

    /** Resumen legible del tipo de problema. */
    public String title() {
        return title;
    }

    /** URI que identifica el tipo de problema. */
    public URI type() {
        return URI.create(PROBLEM_BASE + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
