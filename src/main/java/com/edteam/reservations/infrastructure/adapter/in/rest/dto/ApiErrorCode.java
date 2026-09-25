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
@Schema(name = "ErrorCode", description = """
                Código de error estable. Es la parte del contrato de errores contra la que
                los clientes pueden programar.

                Los clientes deben tolerar códigos desconocidos —agregar uno nuevo es un
                cambio compatible— y en ese caso comportarse según el `status` HTTP.

                `RESOURCE_NOT_FOUND`, `UNSUPPORTED_REQUEST`, `AIRPORT_CATALOG_UNAVAILABLE`
                e `INTERNAL_ERROR` no corresponden a ninguna regla de negocio: aparecen
                cuando el pedido no llega a ninguna operación o cuando algo falla del lado
                del servidor. `AIRPORT_CATALOG_UNAVAILABLE` y `RATE_LIMIT_EXCEEDED` son los que vale la pena
                reintentar tal cual, respetando el header `Retry-After`.""", example = "RESERVATION_NOT_FOUND")
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

    /**
     * Falta el token Bearer, está vencido o no valida. El detalle nunca dice
     * cuál de las tres: distinguirlas le indica a quien prueba tokens qué
     * corregir.
     */
    UNAUTHENTICATED("No autenticado"),

    /**
     * El token vale pero no alcanza. Notar que <b>no</b> es lo que se devuelve
     * al pedir una reserva ajena: eso responde 404, para que el par de códigos
     * no sea un censo de las reservas del sistema.
     */
    FORBIDDEN("Sin permiso"),

    /** Se superó la cuota de pedidos. Reintentable, respetando {@code Retry-After}. */
    RATE_LIMIT_EXCEEDED("Demasiados pedidos"),

    AIRPORT_CATALOG_UNAVAILABLE("Maestro de aeropuertos no disponible"),

    /**
     * La integración con el maestro está rota: credencial vencida, permisos o
     * un cuerpo que no cumple el contrato.
     *
     * <p>Tiene código propio y no cae en {@code INTERNAL_ERROR} porque es el
     * único fallo del sistema que <strong>ningún mecanismo automático
     * resuelve</strong>: no hay reintento ni fallback que arregle una API key
     * vencida, hace falta una persona. Un código genérico lo dejaba
     * indistinguible de cualquier otro defecto y retrasaba el diagnóstico
     * justo en el caso donde el diagnóstico es todo.
     *
     * <p>Sigue siendo {@code 500} y no {@code 503}: no es reintentable, y
     * decirle al cliente que reintente sería mandarlo a chocar contra la
     * misma pared.
     */
    AIRPORT_CATALOG_ERROR("Integración con el maestro de aeropuertos rota"),

    /**
     * La base de datos no dio una conexión a tiempo o la consulta superó su
     * techo. Sale como {@code 503} con {@code Retry-After}: es reintentable y
     * el pedido no tenía nada de malo.
     */
    DATABASE_UNAVAILABLE("Base de datos no disponible"),

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
