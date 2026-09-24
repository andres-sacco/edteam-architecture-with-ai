package com.edteam.reservations.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El catálogo de métricas es un contrato, y esto es lo que lo mantiene alineado
 * con el código.
 *
 * <h2>Por qué existe</h2>
 * El hallazgo 17 de la auditoría: el §4.1 del diseño decía
 * {@code reservations.messaging.consumed{type, outcome}} y el código publicaba
 * {@code result}; decía que {@code reservations.catalog.retries} no tenía
 * etiquetas y el código la tageaba con cuatro valores. Un catálogo de métricas
 * que no coincide con el código es peor que no tenerlo: se descubre el día del
 * incidente, escribiendo la consulta, cuando el panel devuelve vacío.
 *
 * <p>La tabla está <b>acá</b> y no en el documento, y esa es la decisión:
 * un documento se desalinea en silencio, un test falla en el build.
 */
@DisplayName("Catálogo de métricas")
class MetricsCatalogTest {

    private MeterRegistry registry;
    private BusinessMetrics business;
    private SecurityMetrics security;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        business = new BusinessMetrics(registry);
        security = new SecurityMetrics(registry);
    }

    /**
     * Nombre del medidor → juego EXACTO de claves de etiqueta.
     *
     * <p>Exacto en los dos sentidos: falla si el código publica una clave que
     * esta tabla no declara, y falla si declara una que el código no publica.
     */
    private static final Map<String, Set<String>> EXPECTED_TAGS = Map.of(
            BusinessMetrics.OPERATIONS, Set.of("operation", "outcome"),
            BusinessMetrics.REQUESTS_DEGRADED, Set.of("dependency", "route"),
            SecurityMetrics.AUTH_FAILURES, Set.of("reason"),
            SecurityMetrics.RATE_LIMITED, Set.of("method", "route"),
            SecurityMetrics.QUOTA_REGISTRY_RESET, Set.of());

    @Test
    @DisplayName("cada medidor publica exactamente las claves de etiqueta que el catálogo declara")
    void everyMeterPublishesTheDeclaredTagKeys() {
        business.recordRequest("POST", "/v1/reservations", 201, null, false);
        business.recordDegradedResponse("api-catalog", "/v1/reservations");
        security.authFailure("invalid_token");
        security.rateLimited("POST", "/v1/reservations");
        security.quotaRegistryReset();

        Map<String, Set<String>> published = registry.getMeters().stream()
                .collect(Collectors.toMap(
                        meter -> meter.getId().getName(),
                        meter -> meter.getId().getTags().stream()
                                .map(io.micrometer.core.instrument.Tag::getKey)
                                .collect(Collectors.toSet()),
                        (first, second) -> first));

        assertThat(published)
                .withFailMessage("El código publica medidores que el catálogo no declara, o al revés.%n"
                        + "  publicado: %s%n  declarado: %s", published.keySet(), EXPECTED_TAGS.keySet())
                .containsOnlyKeys(EXPECTED_TAGS.keySet().toArray(String[]::new));
        EXPECTED_TAGS.forEach((name, tags) -> assertThat(published.get(name))
                .withFailMessage("El medidor '%s' publica %s y el catálogo declara %s",
                        name, published.get(name), tags)
                .isEqualTo(tags));
    }

    @Test
    @DisplayName("ninguna etiqueta lleva un id de reserva, de usuario ni una URI concreta")
    void noUnboundedTagValues() {
        // La regla del §4.4: una etiqueta sólo puede tomar valores de un
        // conjunto enumerado en el código. Si para saber qué valores puede
        // tomar hay que mirar la base de datos, no es una etiqueta.
        for (int i = 1; i <= 200; i++) {
            business.recordRequest("GET", "/v1/reservations/" + i, 200, null, false);
            business.recordDegradedResponse("api-catalog", "/v1/reservations/" + i);
        }

        // La ruta llega ya como plantilla desde el filtro; lo que se verifica
        // acá es que un valor que NO es plantilla no se convierta en 200
        // series: `operation` cae a 'other' y el contador ni siquiera se crea.
        assertThat(registry.find(BusinessMetrics.OPERATIONS).counters())
                .withFailMessage("Una URI concreta creó una serie: es una serie por reserva")
                .isEmpty();
    }

    @ParameterizedTest(name = "{0} {1} → operation={2}")
    @CsvSource({
            "POST,   /v1/reservations,                                create",
            "GET,    /v1/reservations,                                list",
            "GET,    /v1/reservations/{reservationId},                get",
            "PUT,    /v1/reservations/{reservationId},                modify",
            "DELETE, /v1/reservations/{reservationId},                cancel",
            "POST,   /v1/reservations/{reservationId}/confirmation,   confirm",
            "GET,    /swagger-ui.html,                                other",
            "GET,    unmatched,                                       other",
    })
    @DisplayName("las seis rutas de negocio mapean a una operación, y nada más entra al contador")
    void routesMapToBoundedOperations(String method, String route, String operation) {
        assertThat(BusinessMetrics.operationOf(method, route)).isEqualTo(operation);
        assertThat(BusinessMetrics.OPERATIONS_VOCABULARY).contains(operation);
    }

    @ParameterizedTest(name = "status {0} con código {1} → outcome={2}")
    @CsvSource(nullValues = "-", value = {
            "201, -,                        ok",
            "200, -,                        ok",
            "409, CONCURRENT_UPDATE,        conflict",
            "409, IDEMPOTENCY_KEY_REUSED,   duplicate",
            "404, RESERVATION_NOT_FOUND,    not_found",
            "403, FORBIDDEN,                denied",
            "401, UNAUTHENTICATED,          denied",
            "429, RATE_LIMIT_EXCEEDED,      throttled",
            "400, VALIDATION_ERROR,         rejected",
            "503, AIRPORT_CATALOG_UNAVAILABLE, unavailable",
            "503, DATABASE_UNAVAILABLE,     unavailable",
            "500, AIRPORT_CATALOG_ERROR,    error",
            "500, INTERNAL_ERROR,           error",
    })
    @DisplayName("los dos 409 y los dos 503 se separan por código de error, que es lo que http.server.requests no hace")
    void outcomeSeparatesTheTwoCausesOfTheSameStatus(int status, String errorCode, String outcome) {
        assertThat(BusinessMetrics.outcomeOf(status, errorCode, false)).isEqualTo(outcome);
        assertThat(BusinessMetrics.OUTCOME_VOCABULARY).contains(outcome);
    }

    @Test
    @DisplayName("una respuesta degradada se distingue de una sana aunque las dos sean 201")
    void aDegradedResponseHasItsOwnOutcome() {
        assertThat(BusinessMetrics.outcomeOf(201, null, true)).isEqualTo("degraded");
        assertThat(BusinessMetrics.outcomeOf(201, null, false)).isEqualTo("ok");
    }

    @Test
    @DisplayName("el motivo de un fallo de autenticación viene de un enum cerrado")
    void authFailureReasonIsBounded() {
        // Sin esto, la etiqueta la elegiría el que ataca. Y el token NUNCA
        // entra: un fragmento de JWT en una etiqueta es una credencial
        // replicada a un sistema que además la retiene por semanas.
        security.authFailure("eyJhbGciOiJIUzI1NiJ9.payload.firma");

        List<String> reasons = registry.find(SecurityMetrics.AUTH_FAILURES).counters().stream()
                .map(counter -> counter.getId().getTag("reason"))
                .toList();
        assertThat(reasons).allSatisfy(reason ->
                assertThat(SecurityMetrics.REASONS).contains(reason));
        assertThat(reasons).noneMatch(reason -> reason.contains("eyJ"));
    }
}
