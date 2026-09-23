package com.edteam.reservations;

import com.edteam.reservations.domain.access.Actor;
import com.edteam.reservations.domain.model.Email;
import com.edteam.reservations.support.AbstractPostgresIT;
import com.edteam.reservations.support.SecurityTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La autorización por recurso, de punta a punta y contra PostgreSQL.
 *
 * <p>Los tests de slice prueban que el adaptador traduce bien y que el caso de
 * uso decide bien, con todo lo demás mockeado. Este prueba lo que sólo existe
 * cuando todo está enchufado: que un token firmado de verdad se valide, que la
 * identidad que sale de sus claims sea la que llega al caso de uso, que la
 * reserva de otro no se alcance con datos reales en la base, y que el intento
 * quede escrito en la tabla de auditoría dentro de la misma transacción.
 *
 * <p>Es el test que había que escribir: que el camino feliz siga funcionando
 * no dice nada sobre si el acceso indebido está cerrado.
 */
@AutoConfigureMockMvc
@DisplayName("Autorización de reservas contra PostgreSQL")
class ReservationSecurityIT extends AbstractPostgresIT {

    private static final String OWNER = "ana.perez@example.com";
    private static final String STRANGER = "bruno.diaz@example.com";
    private static final String SUPPORT = "soporte@edteam.example";

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private Instant departure;

    @BeforeEach
    void prepare() {
        departure = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
    }

    // ------------------------------------------------------------------
    // T-02 / T-05 — una reserva ajena no se ve ni se toca
    // ------------------------------------------------------------------

    @Test
    @DisplayName("la reserva de otro responde 404 en las tres operaciones, igual que una inexistente")
    void aForeignReservationIsUnreachable() throws Exception {
        String id = createReservationAs(OWNER);

        // La de otro...
        mockMvc.perform(get("/v1/reservations/{id}", id).with(asUser(STRANGER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        mockMvc.perform(put("/v1/reservations/{id}", id).with(asUser(STRANGER))
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(departure)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/v1/reservations/{id}", id).with(asUser(STRANGER))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isNotFound());

        // ...y una que no existe. Byte por byte la misma respuesta salvo el
        // identificador: es lo que hace que recorrer los ids no diga nada.
        mockMvc.perform(get("/v1/reservations/999999").with(asUser(STRANGER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));

        // Y nada se escribió: la reserva sigue en su versión original.
        mockMvc.perform(get("/v1/reservations/{id}", id).with(asUser(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("enumerar identificadores con otro token no devuelve un solo dato de pasajero")
    void enumerationYieldsNothing() throws Exception {
        createReservationAs(OWNER);
        createReservationAs(OWNER, departure.plus(Duration.ofDays(1)));
        createReservationAs(OWNER, departure.plus(Duration.ofDays(2)));

        for (long id = 1; id <= 5; id++) {
            mockMvc.perform(get("/v1/reservations/{id}", id).with(asUser(STRANGER)))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("backoffice sí alcanza la reserva ajena, y queda auditado")
    void backofficeReachesForeignReservations() throws Exception {
        String id = createReservationAs(OWNER);

        mockMvc.perform(get("/v1/reservations/{id}", id).with(asBackoffice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(OWNER));

        mockMvc.perform(delete("/v1/reservations/{id}", id).with(asBackoffice())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT actor, accion, resultado, recurso_id, recurso_version FROM auditoria "
                        + "WHERE accion = 'RESERVATION_CANCELLED'");
        assertThat(audit)
                .containsEntry("actor", SUPPORT)
                .containsEntry("resultado", "ALLOWED")
                .containsEntry("recurso_id", id)
                .containsEntry("recurso_version", 1);
    }

    // ------------------------------------------------------------------
    // T-03 — el listado está acotado al titular
    // ------------------------------------------------------------------

    @Test
    @DisplayName("el listado sin filtro devuelve sólo lo propio, no la base entera")
    void theListingIsScopedToTheCaller() throws Exception {
        createReservationAs(OWNER);
        createReservationAs(STRANGER, departure.plus(Duration.ofDays(3)));

        assertThat(countRows("reserva")).isEqualTo(2L);

        mockMvc.perform(get("/v1/reservations").with(asUser(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].userId").value(OWNER));
    }

    @Test
    @DisplayName("pedir el listado de otro usuario responde 403")
    void listingSomeoneElseIsForbidden() throws Exception {
        createReservationAs(OWNER);

        mockMvc.perform(get("/v1/reservations").with(asUser(STRANGER)).param("userId", OWNER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("backoffice puede listar el de cualquiera")
    void backofficeCanListSomeoneElse() throws Exception {
        createReservationAs(OWNER);

        mockMvc.perform(get("/v1/reservations").with(asBackoffice()).param("userId", OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    // ------------------------------------------------------------------
    // T-16 — la clave de idempotencia es del usuario
    // ------------------------------------------------------------------

    @Test
    @DisplayName("una clave de idempotencia filtrada no devuelve la reserva de su dueño")
    void aLeakedIdempotencyKeyIsUseless() throws Exception {
        String key = UUID.randomUUID().toString();

        MvcResult original = mockMvc.perform(post("/v1/reservations").with(asUser(OWNER))
                        .header(IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(departure)))
                .andExpect(status().isCreated())
                .andReturn();

        // El atacante reenvía la clave que encontró en un log de acceso. Antes
        // esto respondía 200 con la reserva completa del dueño, documentos de
        // los pasajeros incluidos. Ahora crea la suya.
        MvcResult attempt = mockMvc.perform(post("/v1/reservations").with(asUser(STRANGER))
                        .header(IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(departure)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(STRANGER))
                .andReturn();

        assertThat(idOf(attempt)).isNotEqualTo(idOf(original));
        assertThat(countRows("reserva")).isEqualTo(2L);
    }

    @Test
    @DisplayName("la idempotencia sigue funcionando dentro del mismo usuario")
    void idempotencyStillWorksForTheSameUser() throws Exception {
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/v1/reservations").with(asUser(OWNER))
                        .header(IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(departure)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/reservations").with(asUser(OWNER))
                        .header(IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(departure)))
                .andExpect(status().isOk());

        assertThat(countRows("reserva")).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // T-06 / T-21 — los datos del pasajero
    // ------------------------------------------------------------------

    @Test
    @DisplayName("mandar el documento de otro no devuelve sus datos: la respuesta refleja lo enviado")
    void theDocumentIsNoLongerAnOracle() throws Exception {
        // La víctima reserva con su documento y sus datos reales.
        createReservationAs(OWNER);

        // El atacante manda el MISMO documento con datos inventados. Antes, la
        // respuesta del 201 devolvía el nombre, el apellido y la fecha de
        // nacimiento reales de la víctima, porque el adaptador reutilizaba su
        // fila.
        mockMvc.perform(post("/v1/reservations").with(asUser(STRANGER))
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(departure.plus(Duration.ofDays(1)), "Inventado", "1970-01-01")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passengers[0].firstName").value("Inventado"))
                .andExpect(jsonPath("$.passengers[0].birthDate").value("1970-01-01"));

        // Y la reserva de la víctima quedó intacta: el atacante tampoco pudo
        // imponerle sus datos.
        mockMvc.perform(get("/v1/reservations").with(asUser(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].passengers[0].firstName").value("Ana"))
                .andExpect(jsonPath("$.items[0].passengers[0].birthDate").value("1990-05-20"));
    }

    @Test
    @DisplayName("el documento no queda en claro en la base")
    void documentsAreEncryptedAtRest() throws Exception {
        createReservationAs(OWNER);

        List<String> stored = jdbcTemplate.queryForList("SELECT documento FROM pasajero", String.class);

        assertThat(stored).singleElement()
                .satisfies(value -> assertThat(value).startsWith("v1:").doesNotContain("30123456"));
    }

    // ------------------------------------------------------------------
    // T-01 — la credencial
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sin token, con un token vencido o con uno firmado por otro: 401")
    void rejectsInvalidCredentials() throws Exception {
        mockMvc.perform(get("/v1/reservations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(get("/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION,
                                SecurityTestSupport.expiredBearer(customer(OWNER))))
                .andExpect(status().isUnauthorized());

        // Firmado con otra clave, y pidiendo backoffice: si la firma no se
        // verificara, los roles del token serían un formulario de privilegios.
        mockMvc.perform(get("/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION,
                                SecurityTestSupport.forgedBearer(customer(OWNER))))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // T-11 — auditoría
    // ------------------------------------------------------------------

    @Test
    @DisplayName("el intento sobre una reserva ajena queda auditado aunque la respuesta sea 404")
    void auditsDeniedAttempts() throws Exception {
        String id = createReservationAs(OWNER);

        mockMvc.perform(get("/v1/reservations/{id}", id).with(asUser(STRANGER)))
                .andExpect(status().isNotFound());

        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT actor, accion, resultado, recurso_id, correlation_id, client_ip FROM auditoria "
                        + "WHERE accion = 'RESERVATION_ACCESS_DENIED'");

        assertThat(audit)
                .containsEntry("actor", STRANGER)
                .containsEntry("resultado", "DENIED")
                .containsEntry("recurso_id", id);
        assertThat((String) audit.get("correlation_id"))
                .as("permite ir de esta línea a todos los logs de ese pedido")
                .isNotBlank();
        assertThat((String) audit.get("client_ip")).isNotBlank();
    }

    @Test
    @DisplayName("el alta y la cancelación quedan auditadas con el actor y la versión resultante")
    void auditsWrites() throws Exception {
        String id = createReservationAs(OWNER);

        mockMvc.perform(delete("/v1/reservations/{id}", id).with(asUser(OWNER))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());

        assertThat(countAudit("RESERVATION_CREATED")).isEqualTo(1L);
        assertThat(countAudit("RESERVATION_CANCELLED")).isEqualTo(1L);
        assertThat(countAudit("RESERVATION_ACCESS_DENIED")).isZero();
    }

    @Test
    @DisplayName("una lectura propia no genera auditoría: sería una fila por GET")
    void doesNotAuditSuccessfulReads() throws Exception {
        String id = createReservationAs(OWNER);
        long before = countAudit("RESERVATION_CREATED");

        mockMvc.perform(get("/v1/reservations/{id}", id).with(asUser(OWNER)))
                .andExpect(status().isOk());

        assertThat(countRows("auditoria")).isEqualTo(before);
    }

    @Test
    @DisplayName("la auditoría es append-only: la base rechaza modificarla o borrarla")
    void theAuditTrailIsAppendOnly() throws Exception {
        createReservationAs(OWNER);

        // Un registro que la aplicación puede reescribir no prueba nada: quien
        // la comprometa borra su rastro. Hace falta un DROP TRIGGER —que es
        // DDL, y queda en los logs del motor— para poder tocar una línea.
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE auditoria SET actor = 'otro'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM auditoria"))
                .hasMessageContaining("append-only");
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private String createReservationAs(String email) throws Exception {
        return createReservationAs(email, departure);
    }

    private String createReservationAs(String email, Instant departureAt) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/reservations").with(asUser(email))
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(departureAt)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private String idOf(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asText();
    }

    private static Actor customer(String email) {
        return SecurityTestSupport.customer(email);
    }

    private static RequestPostProcessor asUser(String email) {
        return bearer(SecurityTestSupport.bearer(customer(email)));
    }

    private static RequestPostProcessor asBackoffice() {
        return bearer(SecurityTestSupport.bearer(
                Actor.backoffice(Email.of(SUPPORT), "Soporte", "Reservas")));
    }

    private static RequestPostProcessor bearer(String authorization) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
            return request;
        };
    }

    private static String createBody(Instant departureAt) {
        return createBody(departureAt, "Ana", "1990-05-20");
    }

    private static String createBody(Instant departureAt, String firstName, String birthDate) {
        return """
                {
                  "itinerary": {
                    "price": "1250.50",
                    "currency": "USD",
                    "segments": [
                      {
                        "originAirportCode": "EZE",
                        "destinationAirportCode": "SCL",
                        "airline": "AEROLINEAS ARGENTINAS",
                        "departureAt": "%s"
                      }
                    ]
                  },
                  "passengers": [
                    {
                      "firstName": "%s",
                      "lastName": "Pérez",
                      "birthDate": "%s",
                      "documentNumber": "30123456"
                    }
                  ]
                }
                """.formatted(departureAt, firstName, birthDate);
    }

    private static String updateBody(Instant departureAt) {
        return """
                {
                  "itinerary": {
                    "price": "1980.00",
                    "currency": "USD",
                    "segments": [
                      {
                        "originAirportCode": "EZE",
                        "destinationAirportCode": "MAD",
                        "airline": "IBERIA",
                        "departureAt": "%s"
                      }
                    ]
                  }
                }
                """.formatted(departureAt);
    }
}
