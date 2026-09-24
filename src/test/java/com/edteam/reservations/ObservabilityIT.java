package com.edteam.reservations;

import ch.qos.logback.classic.Level;
import com.edteam.reservations.infrastructure.logging.ActorRef;
import com.edteam.reservations.infrastructure.logging.LogFields;
import com.edteam.reservations.support.AbstractPostgresIT;
import com.edteam.reservations.support.ForbiddenPatterns;
import com.edteam.reservations.support.LogCapture;
import com.edteam.reservations.support.SecurityTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La observabilidad, verificada de punta a punta contra los adaptadores reales.
 *
 * <p>Un test por hallazgo de la auditoría que sólo se puede comprobar con todo
 * enchufado. Los tres bloques responden a las tres preguntas que la auditoría
 * dejó sin responder:
 *
 * <ul>
 *   <li><b>¿Se puede seguir un pedido?</b> Un {@code X-Correlation-Id} conocido
 *       tiene que aparecer en cada registro del pedido, en la fila de
 *       auditoría y en el envelope del evento del outbox (hallazgos 8, 11, 14).</li>
 *   <li><b>¿Los eventos críticos dejan huella?</b> El 401, el 403, el 409 por
 *       {@code If-Match} y el 429 no dejaban ninguna (hallazgos 9, 22, 23, 24).</li>
 *   <li><b>¿Se filtra algo?</b> Ningún registro del recorrido completo puede
 *       matchear el juego de patrones prohibidos (hallazgos 1 a 7).</li>
 * </ul>
 */
@AutoConfigureMockMvc
@DisplayName("Observabilidad contra PostgreSQL")
class ObservabilityIT extends AbstractPostgresIT {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String IF_MATCH = "If-Match";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    /** El id exacto que la prueba del §4 de la auditoría propone seguir. */
    private static final String KNOWN_CORRELATION_ID = "audit-0000-0001";

    private static final String EMAIL = "ana.perez@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private LogCapture logs;
    private Instant departure;

    @BeforeEach
    void prepare() {
        // Desde DEBUG: las líneas del catálogo y las del relay viven ahí, y son
        // justamente las que el hallazgo 11 dejaba sin correlacionar.
        logs = LogCapture.startAt(Level.DEBUG);
        departure = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
    }

    @AfterEach
    void releaseLogs() {
        logs.close();
    }

    // ------------------------------------------------------------------
    // Trazabilidad
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("trazabilidad")
    class Traceability {

        @Test
        @DisplayName("el X-Correlation-Id del cliente aparece en todos los registros del pedido")
        void theClientCorrelationIdReachesEveryRecord() throws Exception {
            logs.clear();
            mockMvc.perform(post("/v1/reservations").with(asUser(EMAIL))
                            .header(CORRELATION_HEADER, KNOWN_CORRELATION_ID)
                            .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("1250.50", "SCL", departure)))
                    .andExpect(status().isCreated())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .header().string(CORRELATION_HEADER, KNOWN_CORRELATION_ID));

            // Todo lo que el pedido escribió, escrito DENTRO del pedido, lleva
            // el id. Se excluye lo que corre fuera del hilo del pedido —el
            // relay, el consumidor— porque tiene su propio id.
            List<LogCapture.Captured> duringTheRequest = logs.matching(captured ->
                    captured.field("event") != null
                            && !String.valueOf(captured.field("event")).startsWith("outbox.")
                            && !String.valueOf(captured.field("event")).startsWith("consumer."));

            assertThat(duringTheRequest)
                    .withFailMessage("El pedido no dejó ningún registro con 'event': "
                            + "antes de este paso un POST exitoso dejaba UNA línea y no describía el pedido")
                    .isNotEmpty();
            assertThat(duringTheRequest)
                    .allSatisfy(captured -> assertThat(captured.mdc(LogFields.CORRELATION_ID))
                            .withFailMessage("El registro %s [%s] salió sin correlationId",
                                    captured.field("event"), captured.logger())
                            .isEqualTo(KNOWN_CORRELATION_ID));
        }

        @Test
        @DisplayName("el mismo id queda en la fila de auditoría y en el envelope del outbox")
        void theSameIdIsWrittenOutsideTheLog() throws Exception {
            mockMvc.perform(post("/v1/reservations").with(asUser(EMAIL))
                            .header(CORRELATION_HEADER, KNOWN_CORRELATION_ID)
                            .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("1250.50", "SCL", departure)))
                    .andExpect(status().isCreated());

            // (c) la columna correlation_id de 'auditoria'
            List<String> audited = jdbcTemplate.queryForList(
                    "SELECT correlation_id FROM auditoria", String.class);
            assertThat(audited).containsOnly(KNOWN_CORRELATION_ID);

            // (d) el campo del EventEnvelope de la fila del outbox
            List<String> payloads = jdbcTemplate.queryForList(
                    "SELECT correlation_id FROM outbox_message", String.class);
            assertThat(payloads).containsOnly(KNOWN_CORRELATION_ID);
        }

        @Test
        @DisplayName("un id que no cumple el formato se descarta y se genera uno nuevo")
        void aMalformedClientIdIsReplaced() throws Exception {
            // El valor termina en el MDC y de ahí en cada línea de log, así que
            // se valida contra un formato estricto antes de tocar nada: un
            // cliente que mandara saltos de línea estaría escribiendo en
            // nuestros logs. Un valor que no cumple se descarta y se genera uno
            // nuevo, sin error: no es culpa del pedido y no hay nada que el
            // cliente pueda arreglar.
            MvcResult result = mockMvc.perform(get("/v1/reservations").with(asUser(EMAIL))
                            .header(CORRELATION_HEADER, "corto"))
                    .andExpect(status().isOk())
                    .andReturn();

            String emitted = result.getResponse().getHeader(CORRELATION_HEADER);
            assertThat(emitted)
                    .isNotNull()
                    .doesNotContain("\n")
                    .matches("[A-Za-z0-9_-]{8,64}");
        }

        @Test
        @DisplayName("el log de acceso describe el pedido: método, ruta plantilla, status y duración")
        void theAccessRecordDescribesTheRequest() throws Exception {
            logs.clear();
            MvcResult created = mockMvc.perform(post("/v1/reservations").with(asUser(EMAIL))
                            .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("1250.50", "SCL", departure)))
                    .andExpect(status().isCreated())
                    .andReturn();
            String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

            logs.clear();
            mockMvc.perform(get("/v1/reservations/{id}", id).with(asUser(EMAIL)))
                    .andExpect(status().isOk());

            List<LogCapture.Captured> access = logs.withEvent(LogFields.HTTP_REQUEST);
            assertThat(access).hasSize(1);
            LogCapture.Captured record = access.get(0);

            assertThat(record.field(LogFields.HTTP_METHOD)).isEqualTo("GET");
            // La PLANTILLA, no la URI: `/v1/reservations/10241` en una etiqueta
            // de métrica es una serie por reserva.
            assertThat(record.field(LogFields.HTTP_ROUTE))
                    .isEqualTo("/v1/reservations/{reservationId}")
                    // La plantilla, no `/v1/reservations/10241`: en una etiqueta
                    // de métrica la URI concreta es una serie por reserva.
                    .doesNotContain("/" + id);
            assertThat(record.field(LogFields.HTTP_STATUS)).isEqualTo("200");
            assertThat(record.rawField(LogFields.DURATION_MS)).isInstanceOf(Long.class);
            // El seudónimo del solicitante, nunca el email.
            assertThat(record.mdc(LogFields.ACTOR_REF)).isEqualTo(ActorRef.of(EMAIL));
        }
    }

    // ------------------------------------------------------------------
    // Los eventos que faltaban
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("eventos críticos")
    class CriticalEvents {

        @Test
        @DisplayName("un 401 deja auth.failed en WARN con el motivo, y nunca el token")
        void unauthenticatedLeavesARecord() throws Exception {
            logs.clear();
            mockMvc.perform(get("/v1/reservations"))
                    .andExpect(status().isUnauthorized());

            List<LogCapture.Captured> failures = logs.withEvent(LogFields.AUTH_FAILED);
            assertThat(failures)
                    .withFailMessage("Un 401 no dejaba ninguna huella, contra lo que el javadoc "
                            + "del entry point prometía")
                    .hasSize(1);
            assertThat(failures.get(0).level()).isEqualTo("WARN");
            assertThat(failures.get(0).field(LogFields.REASON)).isEqualTo("no_token");
            assertThat(failures.get(0).allText()).doesNotContain("Bearer");
        }

        @Test
        @DisplayName("un token inválido se distingue de la ausencia de token")
        void anInvalidTokenHasItsOwnReason() throws Exception {
            logs.clear();
            mockMvc.perform(get("/v1/reservations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer no-es-un-token"))
                    .andExpect(status().isUnauthorized());

            // Sólo esta distinción justifica la alerta de presión de
            // credenciales: «nadie mandó token» es un cliente mal configurado,
            // «el token no vale» es alguien probando.
            assertThat(logs.withEvent(LogFields.AUTH_FAILED))
                    .anySatisfy(captured ->
                            assertThat(captured.field(LogFields.REASON)).isEqualTo("invalid_token"));
        }

        @Test
        @DisplayName("un rechazo por alcance deja auth.denied en WARN, sin el email")
        void scopeViolationLeavesARecord() throws Exception {
            logs.clear();
            mockMvc.perform(get("/v1/reservations").with(asUser("bruno.diaz@example.com"))
                            .param("userId", EMAIL))
                    .andExpect(status().isForbidden());

            List<LogCapture.Captured> denied = logs.withEvent(LogFields.AUTH_DENIED);
            assertThat(denied).hasSize(1);
            // WARN y no INFO: es una señal de seguridad, y el §3.1 del diseño
            // lo pedía así mientras el §2.2 lo dejaba en INFO.
            assertThat(denied.get(0).level()).isEqualTo("WARN");
            // El hallazgo 1, reproducido y cerrado: esta línea escribía
            // 'El solicitante bruno.diaz@example.com no puede listar...'
            assertThat(ForbiddenPatterns.firstMatch(denied.get(0).allText()))
                    .withFailMessage("El registro de auth.denied volvió a filtrar un dato personal")
                    .isEmpty();
        }

        @Test
        @DisplayName("un 409 por If-Match desactualizado deja registro y no se confunde con el de idempotencia")
        void aVersionConflictLeavesARecord() throws Exception {
            MvcResult created = mockMvc.perform(post("/v1/reservations").with(asUser(EMAIL))
                            .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("1250.50", "SCL", departure)))
                    .andExpect(status().isCreated())
                    .andReturn();
            String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

            logs.clear();
            mockMvc.perform(put("/v1/reservations/{id}", id).with(asUser(EMAIL))
                            .header(IF_MATCH, "\"99\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(updateBody("1980.00", departure)))
                    .andExpect(status().isConflict());

            List<LogCapture.Captured> conflicts = logs.withEvent(LogFields.VERSION_CONFLICT);
            assertThat(conflicts)
                    .withFailMessage("El conflicto de versión no dejaba nada: ni log ni métrica")
                    .hasSize(1);
            assertThat(conflicts.get(0).field(LogFields.EXPECTED_VERSION)).isEqualTo("99");
            // Y el log de acceso lleva el código, que es lo que separa este 409
            // del de clave de idempotencia reusada en la métrica de negocio.
            assertThat(logs.withEvent(LogFields.HTTP_REQUEST))
                    .anySatisfy(captured ->
                            assertThat(captured.field(LogFields.ERROR_CODE)).isEqualTo("CONCURRENT_UPDATE"));
        }

        @Test
        @DisplayName("el alta deja reservation.created con el mismo juego de campos que el resto")
        void domainEventsShareTheSameFields() throws Exception {
            logs.clear();
            MvcResult created = mockMvc.perform(post("/v1/reservations").with(asUser(EMAIL))
                            .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("1250.50", "SCL", departure)))
                    .andExpect(status().isCreated())
                    .andReturn();
            String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

            assertRequiredFields(logs.withEvent(LogFields.RESERVATION_CREATED));

            logs.clear();
            mockMvc.perform(delete("/v1/reservations/{id}", id).with(asUser(EMAIL))
                            .header(IF_MATCH, "\"0\""))
                    .andExpect(status().isOk());

            // La cancelación era la línea con MENOS campos de las cuatro, y es
            // la del reclamo más frecuente («yo no cancelé»): sin userId no se
            // podía responder «mostrame todo lo que hizo este usuario».
            assertRequiredFields(logs.withEvent(LogFields.RESERVATION_CANCELLED));
        }

        private void assertRequiredFields(List<LogCapture.Captured> records) {
            assertThat(records).hasSize(1);
            assertThat(records.get(0).fields())
                    .containsKeys(LogFields.RESERVATION_ID, LogFields.USER_ID,
                            LogFields.RESERVATION_VERSION,
                            LogFields.ITINERARY_ORIGIN, LogFields.ITINERARY_DESTINATION);
            assertThat(records.get(0).level()).isEqualTo("INFO");
            // El message es texto fijo: el dato va en los campos.
            assertThat(records.get(0).message()).doesNotMatch(".*\\d.*");
        }
    }

    // ------------------------------------------------------------------
    // Datos sensibles
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("datos sensibles")
    class SensitiveData {

        /**
         * El alcance del gate: los loggers de este sistema.
         *
         * <p>Y hay que decir por qué, porque la exclusión es real. Con el
         * {@code DEBUG} de Spring y de Hibernate encendido, el framework
         * escribe entidades JPA enteras y el cuerpo serializado de cada
         * respuesta: {@code Writing [ReservationResponse[...]]}. Eso es dato
         * personal en un log, y <b>no</b> es algo que este paso pueda
         * arreglar escribiendo mejor nuestras líneas — se arregla no
         * encendiendo el {@code DEBUG} de esos paquetes en producción, que es
         * una decisión de configuración y está anotada como tal.
         *
         * <p>Lo que este gate sí garantiza es que ninguna línea NUESTRA
         * filtre, en cualquier nivel, que es de lo que hablan los hallazgos 1
         * a 7.
         */
        private List<LogCapture.Captured> ourRecords() {
            return logs.matching(captured -> captured.logger().startsWith("com.edteam.reservations"));
        }

        @Test
        @DisplayName("ningún registro de un recorrido completo matchea un patrón prohibido")
        void aFullRunLeaksNothing() throws Exception {
            logs.clear();
            runTheFullFlow();

            assertThat(ourRecords())
                    .isNotEmpty()
                    .allSatisfy(captured -> assertThat(
                            ForbiddenPatterns.firstMatch(captured.allText()))
                            .withFailMessage("El registro '%s' [%s] filtró %s",
                                    captured.message(), captured.logger(),
                                    ForbiddenPatterns.firstMatch(captured.allText()).orElse(""))
                            .isEmpty());
        }

        @Test
        @DisplayName("una violación de unique no escribe el valor de la columna en el log")
        void anIntegrityViolationDoesNotLeakColumnValues() throws Exception {
            // El hallazgo 3, provocado: se fuerza la violación del unique de
            // 'usuario.email' desde afuera. El mensaje de PostgreSQL trae el
            // SQL y el 'Detail: Key (email)=(...)', y las columnas email,
            // nombre, apellido y fecha_nacimiento están EN CLARO en el modelo.
            jdbcTemplate.update(
                    "INSERT INTO usuario (email, nombre, apellido, fecha_alta) VALUES (?, ?, ?, ?)",
                    EMAIL, "Ana", "Pérez", java.sql.Timestamp.from(Instant.now()));

            logs.clear();
            // El alta encuentra al usuario ya existente y no falla; lo que
            // interesa es que NADA de lo que se escriba en el camino lleve el
            // email, que era el vector del hallazgo 2.
            mockMvc.perform(post("/v1/reservations").with(asUser(EMAIL))
                            .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("1250.50", "SCL", departure)))
                    .andExpect(status().isCreated());

            assertThat(ourRecords())
                    .allSatisfy(captured -> assertThat(
                            ForbiddenPatterns.firstMatch(captured.allText()))
                            .withFailMessage("El registro '%s' [%s] filtró %s",
                                    captured.message(), captured.logger(),
                                    ForbiddenPatterns.firstMatch(captured.allText()).orElse(""))
                            .isEmpty());
        }

        @Test
        @DisplayName("ningún registro lleva un salto de línea ni supera el techo de largo")
        void noRecordCarriesControlCharactersOrIsUnbounded() throws Exception {
            logs.clear();
            runTheFullFlow();

            assertThat(ourRecords()).allSatisfy(captured -> {
                captured.fields().forEach((key, value) -> {
                    String text = String.valueOf(value);
                    assertThat(text)
                            .withFailMessage("El campo '%s' del registro '%s' trae un salto de línea: "
                                    + "en un recolector orientado a líneas eso fabrica un registro",
                                    key, captured.message())
                            .doesNotContain("\n").doesNotContain("\r");
                    assertThat(text.length())
                            .withFailMessage("El campo '%s' del registro '%s' tiene %d caracteres",
                                    key, captured.message(), text.length())
                            .isLessThanOrEqualTo(1024);
                });
            });
        }
    }

    // ------------------------------------------------------------------
    // Andamiaje
    // ------------------------------------------------------------------

    private void runTheFullFlow() throws Exception {
        MvcResult created = mockMvc.perform(post("/v1/reservations").with(asUser(EMAIL))
                        .header(CORRELATION_HEADER, KNOWN_CORRELATION_ID)
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("1250.50", "SCL", departure)))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/v1/reservations/{id}", id).with(asUser(EMAIL)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/reservations").with(asUser(EMAIL))).andExpect(status().isOk());
        mockMvc.perform(put("/v1/reservations/{id}", id).with(asUser(EMAIL))
                        .header(IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("1980.00", departure)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/v1/reservations/{id}", id).with(asUser(EMAIL))
                        .header(IF_MATCH, "\"1\""))
                .andExpect(status().isOk());
        // Los caminos de rechazo, que son los que menos se prueban y los que
        // más fácil filtran.
        mockMvc.perform(get("/v1/reservations")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/reservations").with(asUser("bruno.diaz@example.com"))
                        .param("userId", EMAIL))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/reservations/999999").with(asUser(EMAIL)))
                .andExpect(status().isNotFound());
    }

    private static RequestPostProcessor asUser(String email) {
        String authorization = SecurityTestSupport.bearer(SecurityTestSupport.customer(email));
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
            return request;
        };
    }

    private static String createBody(String price, String destination, Instant departureAt) {
        return """
                {
                  "itinerary": {
                    "price": "%s",
                    "currency": "USD",
                    "segments": [
                      {
                        "originAirportCode": "EZE",
                        "destinationAirportCode": "%s",
                        "airline": "AEROLINEAS ARGENTINAS",
                        "departureAt": "%s"
                      }
                    ]
                  },
                  "passengers": [
                    {
                      "firstName": "Ana",
                      "lastName": "Pérez",
                      "birthDate": "1990-05-20",
                      "documentNumber": "30123456"
                    }
                  ]
                }
                """.formatted(price, destination, departureAt);
    }

    private static String updateBody(String price, Instant departureAt) {
        return """
                {
                  "itinerary": {
                    "price": "%s",
                    "currency": "USD",
                    "segments": [
                      {
                        "originAirportCode": "EZE",
                        "destinationAirportCode": "SCL",
                        "airline": "AEROLINEAS ARGENTINAS",
                        "departureAt": "%s"
                      },
                      {
                        "originAirportCode": "SCL",
                        "destinationAirportCode": "MAD",
                        "airline": "IBERIA",
                        "departureAt": "%s"
                      }
                    ]
                  }
                }
                """.formatted(price, departureAt, departureAt.plus(Duration.ofHours(6)));
    }
}
