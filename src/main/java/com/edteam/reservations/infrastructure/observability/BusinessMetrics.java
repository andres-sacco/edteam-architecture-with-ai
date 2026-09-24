package com.edteam.reservations.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.http.HttpMethod;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Las métricas del camino del pedido, en lenguaje de negocio.
 *
 * <h2>Por qué no alcanza con {@code http.server.requests}</h2>
 * El medidor de Actuator responde «cuántos 409 hubo» y no responde «cuántos de
 * esos 409 fueron un {@code If-Match} viejo —el sistema funcionando— y cuántos
 * una clave de idempotencia reusada —un contrato roto con el integrador—».
 * Son dos problemas distintos, con dos dueños distintos, y hasta acá se veían
 * como la misma serie. Lo mismo con el 503: uno por catálogo caído y otro por
 * pool agotado piden decisiones opuestas.
 *
 * <p>Dos contadores, y el segundo no duplica al primero:
 *
 * <ul>
 *   <li>{@code reservations.operations{operation, outcome}} — una por pedido de
 *       negocio, con el resultado traducido a un enum del código;</li>
 *   <li>{@code reservations.requests.degraded{dependency, route}} — una por
 *       <b>respuesta</b> que salió degradada. No es
 *       {@code reservations.degraded.responses}, que cuenta <b>eventos</b>: un
 *       {@code POST} con tres ciudades servidas del <i>stale</i> incrementa
 *       aquél tres veces y éste una. «El 4 % de las respuestas salió
 *       degradada» es una frase que se le puede decir a producto; «hubo 1.200
 *       degradaciones» no.</li>
 * </ul>
 *
 * <h2>Cardinalidad</h2>
 * Las cuatro etiquetas toman valores de conjuntos enumerados <b>en este
 * archivo</b>: {@link #OPERATIONS_BY_ROUTE} tiene 6 rutas, {@code outcome}
 * tiene 9 valores, {@code dependency} sale de {@code Degradation} y está
 * acotado por las dependencias del sistema. Nada que salga de un pedido entra
 * a una etiqueta: ni el id de la reserva, ni el del usuario, ni la URI
 * concreta, ni el código IATA —que tiene ~9.000 valores posibles y los elige
 * el cliente—. Qué ciudad falló lo responde el log, que sí lo lleva.
 */
public class BusinessMetrics {

    public static final String OPERATIONS = "reservations.operations";
    public static final String REQUESTS_DEGRADED = "reservations.requests.degraded";

    /** Operaciones de negocio. Es el conjunto cerrado de la etiqueta {@code operation}. */
    public static final Set<String> OPERATIONS_VOCABULARY =
            Set.of("create", "get", "list", "modify", "confirm", "cancel", "other");

    /**
     * Resultados. Es el conjunto cerrado de la etiqueta {@code outcome}, y es
     * el que hace que la métrica valga: cada valor lleva a una decisión
     * distinta.
     */
    public static final Set<String> OUTCOME_VOCABULARY =
            Set.of("ok", "duplicate", "conflict", "not_found", "denied", "rejected",
                    "throttled", "degraded", "unavailable", "error");

    /**
     * Plantilla de ruta a operación. La clave es la plantilla del
     * {@code HandlerMapping} y el método, que es lo único que el filtro tiene
     * a mano y lo único con cardinalidad acotada.
     */
    private static final Map<String, String> OPERATIONS_BY_ROUTE = Map.of(
            "POST /v1/reservations", "create",
            "GET /v1/reservations", "list",
            "GET /v1/reservations/{reservationId}", "get",
            "PUT /v1/reservations/{reservationId}", "modify",
            "POST /v1/reservations/{reservationId}/confirmation", "confirm",
            "DELETE /v1/reservations/{reservationId}", "cancel");

    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "El registro de métricas es obligatorio");
    }

    /**
     * Una respuesta de la API.
     *
     * @param errorCode el {@code ApiErrorCode} de la respuesta, si hubo error.
     *                  Es lo que separa las dos causas de un mismo status
     */
    public void recordRequest(String method, String route, int status, String errorCode, boolean degraded) {
        String operation = operationOf(method, route);
        if ("other".equals(operation)) {
            // Un pedido que no es de negocio —una ruta que no existe, el
            // preflight, la UI de OpenAPI— no entra al contador de
            // operaciones: inflaría el denominador de la tasa de error con
            // tráfico que no es el que la alerta mide.
            return;
        }
        Counter.builder(OPERATIONS)
                .tags(Tags.of("operation", operation, "outcome", outcomeOf(status, errorCode, degraded)))
                .description("Operaciones de negocio por resultado, en el vocabulario del dominio")
                .register(registry)
                .increment();
    }

    /** Una respuesta que salió con datos que no vinieron del origen. */
    public void recordDegradedResponse(String dependency, String route) {
        Counter.builder(REQUESTS_DEGRADED)
                .tags(Tags.of("dependency", dependency, "route", route))
                .description("Respuestas —no eventos— servidas con al menos un dato degradado")
                .register(registry)
                .increment();
    }

    static String operationOf(String method, String route) {
        if (method == null || route == null) {
            return "other";
        }
        return OPERATIONS_BY_ROUTE.getOrDefault(
                method.toUpperCase(Locale.ROOT) + " " + route, "other");
    }

    /**
     * Status y código de error a resultado de negocio.
     *
     * <p>El orden importa: el código de error manda sobre el status, porque es
     * el que distingue las dos causas del mismo 409 y las dos del mismo 503.
     */
    static String outcomeOf(int status, String errorCode, boolean degraded) {
        if (errorCode != null) {
            switch (errorCode) {
                case "IDEMPOTENCY_KEY_REUSED" -> {
                    return "duplicate";
                }
                case "CONCURRENT_UPDATE", "RESERVATION_ALREADY_CANCELLED", "RESERVATION_NOT_MODIFIABLE" -> {
                    return "conflict";
                }
                case "RESERVATION_NOT_FOUND", "RESOURCE_NOT_FOUND" -> {
                    return "not_found";
                }
                case "FORBIDDEN", "UNAUTHENTICATED" -> {
                    return "denied";
                }
                case "RATE_LIMIT_EXCEEDED" -> {
                    return "throttled";
                }
                case "AIRPORT_CATALOG_UNAVAILABLE", "DATABASE_UNAVAILABLE" -> {
                    return "unavailable";
                }
                default -> {
                    // Sigue por status: un VALIDATION_ERROR es 'rejected' y un
                    // AIRPORT_CATALOG_ERROR es 'error', y las dos salen de ahí.
                }
            }
        }
        if (status >= 500) {
            return "error";
        }
        if (status == HTTP_TOO_MANY_REQUESTS) {
            return "throttled";
        }
        if (status >= 400) {
            return "rejected";
        }
        return degraded ? "degraded" : "ok";
    }

    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    /** Sólo para que el vocabulario del test de contrato no se escriba a mano dos veces. */
    public static boolean isWriteMethod(String method) {
        return HttpMethod.POST.matches(method) || HttpMethod.PUT.matches(method)
                || HttpMethod.DELETE.matches(method) || HttpMethod.PATCH.matches(method);
    }
}
