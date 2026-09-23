package com.edteam.reservations;

import com.edteam.reservations.domain.access.Actor;
import com.edteam.reservations.domain.model.Email;
import com.edteam.reservations.support.AbstractPostgresIT;
import com.edteam.reservations.support.SecurityTestSupport;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flujo completo por HTTP contra los adaptadores reales.
 *
 * <p>Es el test que cierra el círculo: el slice de controllers prueba el
 * contrato con los puertos mockeados, y acá el mismo contrato corre contra
 * PostgreSQL con el esquema de Flyway. Sin esto quedarían sin probar
 * justamente las partes que sólo existen cuando todo está enchufado: que el
 * {@code ETag} que devuelve una operación sirva para la siguiente, que el
 * reintento con la misma clave no duplique filas y que la consulta del listado
 * —con sus filtros y su orden— sea SQL válido.
 */
@AutoConfigureMockMvc
@DisplayName("API de reservas contra PostgreSQL")
class ReservationApiIT extends AbstractPostgresIT {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String IF_MATCH = "If-Match";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String EMAIL = "ana.perez@example.com";

    private Instant departure;

    @BeforeEach
    void prepare() {
        // No se inserta ningún usuario: la reserva lo da de alta. Antes había
        // que cargarlo a mano acá, que era justamente lo que la API no permitía
        // hacer a un cliente real.
        // El reloj de la aplicación es el real, así que el vuelo tiene que ser
        // futuro de verdad o el dominio rechaza la reserva.
        departure = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    @DisplayName("crea, consulta, lista, modifica y cancela una reserva de punta a punta")
    void runsTheFullFlowOverHttp() throws Exception {
        // --- alta ---
        MvcResult created = mockMvc.perform(post("/v1/reservations").with(asUser(EMAIL))
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("1250.50", "SCL", departure)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.itinerary.price.amount").value("1250.50"))
                .andReturn();

        String id = jsonOf(created).get("id").asText();
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/v1/reservations/" + id);

        // --- consulta ---
        mockMvc.perform(get("/v1/reservations/{id}", id).with(asUser(EMAIL)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.userId").value(EMAIL))
                .andExpect(jsonPath("$.itinerary.origin").value("EZE"))
                .andExpect(jsonPath("$.itinerary.destination").value("SCL"))
                .andExpect(jsonPath("$.itinerary.segments[0].position").value(1))
                .andExpect(jsonPath("$.passengers[0].documentNumber").value("30123456"));

        // --- listado ---
        mockMvc.perform(get("/v1/reservations").with(asUser(EMAIL))
                        .param("userId", EMAIL)
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        // --- modificación con la versión que devolvió la lectura ---
        MvcResult modified = mockMvc.perform(put("/v1/reservations/{id}", id).with(asUser(EMAIL))
                        .header(IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("1980.00", departure)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.itinerary.destination").value("MAD"))
                .andExpect(jsonPath("$.itinerary.segments", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.itinerary.segments[1].position").value(2))
                .andReturn();

        assertThat(jsonOf(modified).get("itinerary").get("price").get("amount").asText()).isEqualTo("1980.00");

        // --- cancelación: baja lógica, la reserva sigue estando ---
        mockMvc.perform(delete("/v1/reservations/{id}", id).with(asUser(EMAIL)).header(IF_MATCH, "\"1\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists());

        mockMvc.perform(get("/v1/reservations/{id}", id).with(asUser(EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(countRows("reserva")).isEqualTo(1L);
    }

    @Test
    @DisplayName("el reintento con la misma clave devuelve 200 y no crea una segunda reserva")
    void isIdempotentOverHttp() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = createBody("1250.50", "SCL", departure);

        MvcResult first = mockMvc.perform(post("/v1/reservations").with(asUser(EMAIL))
                        .header(IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        mockMvc.perform(post("/v1/reservations").with(asUser(EMAIL))
                        .header(IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.id").value(jsonOf(first).get("id").asText()));

        assertThat(countRows("reserva")).isEqualTo(1L);
    }

    @Test
    @DisplayName("una modificación con un ETag viejo responde 409 y no escribe")
    void rejectsStaleETag() throws Exception {
        String id = createReservation();

        mockMvc.perform(put("/v1/reservations/{id}", id).with(asUser(EMAIL))
                        .header(IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("1980.00", departure)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/v1/reservations/{id}", id).with(asUser(EMAIL))
                        .header(IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("2500.00", departure)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"));

        mockMvc.perform(get("/v1/reservations/{id}", id).with(asUser(EMAIL)))
                .andExpect(jsonPath("$.itinerary.price.amount").value("1980.00"));
    }

    @Test
    @DisplayName("cancelar dos veces responde 409")
    void rejectsDoubleCancellation() throws Exception {
        String id = createReservation();

        mockMvc.perform(delete("/v1/reservations/{id}", id).with(asUser(EMAIL)).header(IF_MATCH, "\"0\""))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/v1/reservations/{id}", id).with(asUser(EMAIL)).header(IF_MATCH, "\"1\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_ALREADY_CANCELLED"));
    }

    @Test
    @DisplayName("reservar da de alta al usuario: no hace falta crearlo por otro endpoint")
    void registersTheUserWhileBooking() throws Exception {
        assertThat(countRows("usuario")).isZero();

        String nueva = "nueva.clienta@example.com";

        MvcResult created = mockMvc.perform(post("/v1/reservations").with(asUser(nueva))
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("1250.50", "SCL", departure)))
                .andExpect(status().isCreated())
                // El comprador es el del token; el cuerpo ya no lo nombra.
                .andExpect(jsonPath("$.userId").value(nueva))
                .andReturn();

        assertThat(jsonOf(created).get("userId").asText()).isEqualTo(nueva);
        assertThat(countRows("usuario")).isEqualTo(1L);

        // Su propio listado la encuentra...
        mockMvc.perform(get("/v1/reservations").with(asUser(nueva)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1));

        // ...y el de cualquier otro, no: ni siquiera hace falta que exista.
        mockMvc.perform(get("/v1/reservations").with(asUser("otra.persona@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("dos reservas del mismo email comparten usuario en lugar de duplicarlo")
    void reusesTheUserAcrossBookings() throws Exception {
        String primera = createReservation();
        String segunda = createReservation(departure.plus(Duration.ofDays(5)));

        assertThat(countRows("usuario")).isEqualTo(1L);
        assertThat(userIdOf(primera)).isEqualTo(EMAIL);
        assertThat(userIdOf(segunda)).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("el alcance ignora mayúsculas: el email se normaliza antes de comparar identidades")
    void scopeIgnoresCase() throws Exception {
        // Ahora esto es una propiedad de seguridad y no sólo del filtro: si la
        // comparación entre el email del token y el del parámetro fuera
        // sensible a mayúsculas, un cliente que manda su propio email con otra
        // caja se comería un 403.
        String email = "case.sensitive@example.com";

        mockMvc.perform(post("/v1/reservations").with(asUser(email))
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("1250.50", "SCL", departure)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/v1/reservations").with(asUser(email))
                        .param("userId", email.toUpperCase(java.util.Locale.ROOT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("un userId que no es un email responde 400")
    void rejectsNonEmailUserIdFilter() throws Exception {
        mockMvc.perform(get("/v1/reservations").with(asUser(EMAIL)).param("userId", "77"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("userId"));
    }

    @Test
    @DisplayName("un token con un email malformado es 401 y no da de alta a nadie")
    void rejectsMalformedEmailInTheToken() throws Exception {
        // Antes este test mandaba el email en el cuerpo y esperaba un 400 de
        // validación. Ya no hay cuerpo que nombre al comprador: el único
        // origen posible es el token, así que un email inválido deja de ser un
        // pedido mal formado y pasa a ser una credencial inválida.
        mockMvc.perform(post("/v1/reservations")
                        .header(HttpHeaders.AUTHORIZATION, tokenWithClaims("no-es-un-email", "Ana", "Pérez"))
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("1250.50", "SCL", departure)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(countRows("usuario")).isZero();
        assertThat(countRows("reserva")).isZero();
    }

    @Test
    @DisplayName("un aeropuerto fuera del catálogo responde 400 antes de tocar la base")
    void rejectsUnknownAirport() throws Exception {
        mockMvc.perform(post("/v1/reservations").with(asUser(EMAIL))
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("1250.50", "XXX", departure)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_AIRPORT"));

        assertThat(countRows("reserva")).isZero();
        assertThat(countRows("itinerario")).isZero();
    }

    @Test
    @DisplayName("una reserva inexistente responde 404 con el cuerpo uniforme")
    void returnsNotFound() throws Exception {
        mockMvc.perform(get("/v1/reservations/999999").with(asUser(EMAIL)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value("/v1/reservations/999999"));
    }

    @Test
    @DisplayName("el listado pagina, ordena por salida del primer tramo y filtra por rango de fechas")
    void paginatesFiltersAndSorts() throws Exception {
        Instant primera = departure;
        Instant segunda = departure.plus(Duration.ofDays(10));
        Instant tercera = departure.plus(Duration.ofDays(20));

        String idTercera = createReservation(tercera);
        String idPrimera = createReservation(primera);
        String idSegunda = createReservation(segunda);

        // Orden por salida ascendente: el orden de alta no importa.
        mockMvc.perform(get("/v1/reservations").with(asUser(EMAIL))
                        .param("sort", "firstDepartureAt,asc")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(idPrimera))
                .andExpect(jsonPath("$.items[1].id").value(idSegunda))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));

        // Segunda página del mismo orden.
        mockMvc.perform(get("/v1/reservations").with(asUser(EMAIL))
                        .param("sort", "firstDepartureAt,asc")
                        .param("size", "2")
                        .param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(idTercera));

        // Filtro por rango: sólo la del medio.
        mockMvc.perform(get("/v1/reservations").with(asUser(EMAIL))
                        .param("departureFrom", segunda.minus(Duration.ofDays(1)).toString())
                        .param("departureTo", segunda.plus(Duration.ofDays(1)).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(idSegunda));

        // Una página más allá del final: vacía, con el total real, no un error.
        mockMvc.perform(get("/v1/reservations").with(asUser(EMAIL)).param("page", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(0)))
                .andExpect(jsonPath("$.page.totalElements").value(3));
    }

    @Test
    @DisplayName("el listado filtra por estado")
    void filtersByStatus() throws Exception {
        String cancelada = createReservation();
        createReservation(departure.plus(Duration.ofDays(5)));

        mockMvc.perform(delete("/v1/reservations/{id}", cancelada).with(asUser(EMAIL)).header(IF_MATCH, "\"0\""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/reservations").with(asUser(EMAIL)).param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"));

        mockMvc.perform(get("/v1/reservations").with(asUser(EMAIL)).param("status", "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(cancelada));

        mockMvc.perform(get("/v1/reservations").with(asUser(EMAIL))
                        .param("status", "PENDING")
                        .param("status", "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    @DisplayName("la aplicación genera y publica el contrato que implementa, y Swagger UI para explorarlo")
    void servesTheGeneratedContract() throws Exception {
        // Sin token, y a propósito. Exigirlo acá dejaría la UI inusable: una
        // navegación del navegador no puede llevar un header Authorization, así
        // que el 401 llegaría antes de que exista la pantalla donde apretar
        // "Authorize", y la UI busca el documento por XHR sin credencial. El
        // control de T-08 es el interruptor —en producción estos endpoints no
        // existen—, no una regla de autorización que además rompe la UI.
        //
        // El documento sale del contexto completo, con todos los controllers
        // reales: es lo que va a ver un partner, no una versión de test.
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("API de Reservas de Vuelos"))
                .andExpect(jsonPath("$.paths['/v1/reservations'].post").exists())
                .andExpect(jsonPath("$.paths['/v1/reservations/{reservationId}'].delete").exists())
                .andExpect(jsonPath("$.components.schemas.Reservation").exists())
                .andExpect(jsonPath("$.components.schemas.Problem.properties.code").exists())
                // El esquema de seguridad tiene que estar declarado: es lo que
                // hace que la UI muestre el botón "Authorize" y que los
                // clientes generados sepan que hay que mandar el header.
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/v1/reservations'].post.security[0].bearerAuth").exists());

        mockMvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("title: API de Reservas de Vuelos")
                        .contains("/v1/reservations/{reservationId}"));

        // La UI carga entera sin credencial: el index y el config que pide por XHR.
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("pero lo que la UI ejecuta sigue exigiendo token")
    void whatTheUiExecutesStillNeedsAToken() throws Exception {
        // Es la mitad que importa: que la UI sea alcanzable no abre la API.
        // El botón "Try it out" pega contra /v1/** como cualquier otro cliente.
        mockMvc.perform(get("/v1/reservations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Firma un token de verdad con la clave de desarrollo y lo pone en el
     * pedido.
     *
     * <p>Acá no se inyecta la autenticación ya resuelta, como sí hace el slice
     * de controllers: en el test de integración lo que se está probando es la
     * cadena completa —firma, claims, conversión a {@code Actor}, autorización
     * por recurso—, y saltearse la primera mitad dejaría sin cubrir justo lo
     * que sólo existe cuando todo está enchufado.
     */
    private static RequestPostProcessor asUser(String email) {
        return bearer(SecurityTestSupport.bearer(SecurityTestSupport.customer(email)));
    }

    private static RequestPostProcessor asBackoffice(String email) {
        return bearer(SecurityTestSupport.bearer(
                Actor.backoffice(Email.of(email), "Soporte", "Reservas")));
    }

    private static RequestPostProcessor bearer(String authorization) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
            return request;
        };
    }

    /** Token firmado con claims arbitrarios, para probar los rechazos. */
    private static String tokenWithClaims(String email, String givenName, String familyName) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(email)
                    .claim("email", email)
                    .claim("given_name", givenName)
                    .claim("family_name", familyName)
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SecurityTestSupport.DEV_SECRET.getBytes(StandardCharsets.UTF_8)));
            return "Bearer " + jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo firmar el token de prueba", e);
        }
    }

    private String createReservation() throws Exception {
        return createReservation(departure);
    }

    private String createReservation(Instant departureAt) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/reservations").with(asUser(EMAIL))
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("1250.50", "SCL", departureAt)))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonOf(result).get("id").asText();
    }

    private String userIdOf(String reservationId) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/reservations/{id}", reservationId).with(asUser(EMAIL)))
                .andExpect(status().isOk())
                .andReturn();
        return jsonOf(result).get("userId").asText();
    }

    private JsonNode jsonOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Sin objeto {@code user}: el comprador sale del token. */
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
