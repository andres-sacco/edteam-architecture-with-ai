package com.edteam.reservations;

import com.edteam.reservations.support.AbstractPostgresIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        MvcResult created = mockMvc.perform(post("/v1/reservations")
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(EMAIL, "1250.50", "SCL", departure)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.itinerary.price.amount").value("1250.50"))
                .andReturn();

        String id = jsonOf(created).get("id").asText();
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/v1/reservations/" + id);

        // --- consulta ---
        mockMvc.perform(get("/v1/reservations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.userId").value(EMAIL))
                .andExpect(jsonPath("$.itinerary.origin").value("EZE"))
                .andExpect(jsonPath("$.itinerary.destination").value("SCL"))
                .andExpect(jsonPath("$.itinerary.segments[0].position").value(1))
                .andExpect(jsonPath("$.passengers[0].documentNumber").value("30123456"));

        // --- listado ---
        mockMvc.perform(get("/v1/reservations")
                        .param("userId", EMAIL)
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        // --- modificación con la versión que devolvió la lectura ---
        MvcResult modified = mockMvc.perform(put("/v1/reservations/{id}", id)
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
        mockMvc.perform(delete("/v1/reservations/{id}", id).header(IF_MATCH, "\"1\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists());

        mockMvc.perform(get("/v1/reservations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(countRows("reserva")).isEqualTo(1L);
    }

    @Test
    @DisplayName("el reintento con la misma clave devuelve 200 y no crea una segunda reserva")
    void isIdempotentOverHttp() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = createBody(EMAIL, "1250.50", "SCL", departure);

        MvcResult first = mockMvc.perform(post("/v1/reservations")
                        .header(IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        mockMvc.perform(post("/v1/reservations")
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

        mockMvc.perform(put("/v1/reservations/{id}", id)
                        .header(IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("1980.00", departure)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/v1/reservations/{id}", id)
                        .header(IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("2500.00", departure)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"));

        mockMvc.perform(get("/v1/reservations/{id}", id))
                .andExpect(jsonPath("$.itinerary.price.amount").value("1980.00"));
    }

    @Test
    @DisplayName("cancelar dos veces responde 409")
    void rejectsDoubleCancellation() throws Exception {
        String id = createReservation();

        mockMvc.perform(delete("/v1/reservations/{id}", id).header(IF_MATCH, "\"0\""))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/v1/reservations/{id}", id).header(IF_MATCH, "\"1\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_ALREADY_CANCELLED"));
    }

    @Test
    @DisplayName("reservar da de alta al usuario: no hace falta crearlo por otro endpoint")
    void registersTheUserWhileBooking() throws Exception {
        assertThat(countRows("usuario")).isZero();

        MvcResult created = mockMvc.perform(post("/v1/reservations")
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("nueva.clienta@example.com", "1250.50", "SCL", departure)))
                .andExpect(status().isCreated())
                // La API identifica al usuario por su email, no por el id de la base.
                .andExpect(jsonPath("$.userId").value("nueva.clienta@example.com"))
                .andReturn();

        assertThat(jsonOf(created).get("userId").asText()).isEqualTo("nueva.clienta@example.com");
        assertThat(countRows("usuario")).isEqualTo(1L);

        // Y ese mismo email es lo que filtra el listado.
        mockMvc.perform(get("/v1/reservations").param("userId", "nueva.clienta@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mockMvc.perform(get("/v1/reservations").param("userId", "otra.persona@example.com"))
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
    @DisplayName("el filtro del listado ignora mayúsculas porque el email se normaliza al guardarlo")
    void filtersByEmailRegardlessOfCase() throws Exception {
        createReservation();

        mockMvc.perform(get("/v1/reservations").param("userId", EMAIL.toUpperCase(java.util.Locale.ROOT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("un userId que no es un email responde 400")
    void rejectsNonEmailUserIdFilter() throws Exception {
        mockMvc.perform(get("/v1/reservations").param("userId", "77"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("userId"));
    }

    @Test
    @DisplayName("un email malformado responde 400 y no da de alta a nadie")
    void rejectsMalformedEmail() throws Exception {
        mockMvc.perform(post("/v1/reservations")
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("no-es-un-email", "1250.50", "SCL", departure)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("user.email"));

        assertThat(countRows("usuario")).isZero();
        assertThat(countRows("reserva")).isZero();
    }

    @Test
    @DisplayName("un aeropuerto fuera del catálogo responde 400 antes de tocar la base")
    void rejectsUnknownAirport() throws Exception {
        mockMvc.perform(post("/v1/reservations")
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(EMAIL, "1250.50", "XXX", departure)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_AIRPORT"));

        assertThat(countRows("reserva")).isZero();
        assertThat(countRows("itinerario")).isZero();
    }

    @Test
    @DisplayName("una reserva inexistente responde 404 con el cuerpo uniforme")
    void returnsNotFound() throws Exception {
        mockMvc.perform(get("/v1/reservations/999999"))
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
        mockMvc.perform(get("/v1/reservations")
                        .param("sort", "firstDepartureAt,asc")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(idPrimera))
                .andExpect(jsonPath("$.items[1].id").value(idSegunda))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));

        // Segunda página del mismo orden.
        mockMvc.perform(get("/v1/reservations")
                        .param("sort", "firstDepartureAt,asc")
                        .param("size", "2")
                        .param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(idTercera));

        // Filtro por rango: sólo la del medio.
        mockMvc.perform(get("/v1/reservations")
                        .param("departureFrom", segunda.minus(Duration.ofDays(1)).toString())
                        .param("departureTo", segunda.plus(Duration.ofDays(1)).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(idSegunda));

        // Una página más allá del final: vacía, con el total real, no un error.
        mockMvc.perform(get("/v1/reservations").param("page", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(0)))
                .andExpect(jsonPath("$.page.totalElements").value(3));
    }

    @Test
    @DisplayName("el listado filtra por estado")
    void filtersByStatus() throws Exception {
        String cancelada = createReservation();
        createReservation(departure.plus(Duration.ofDays(5)));

        mockMvc.perform(delete("/v1/reservations/{id}", cancelada).header(IF_MATCH, "\"0\""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/reservations").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"));

        mockMvc.perform(get("/v1/reservations").param("status", "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(cancelada));

        mockMvc.perform(get("/v1/reservations")
                        .param("status", "PENDING")
                        .param("status", "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    @DisplayName("la aplicación genera y publica el contrato que implementa, y Swagger UI para explorarlo")
    void servesTheGeneratedContract() throws Exception {
        // El documento sale del contexto completo, con todos los controllers
        // reales: es lo que va a ver un partner, no una versión de test.
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("API de Reservas de Vuelos"))
                .andExpect(jsonPath("$.paths['/v1/reservations'].post").exists())
                .andExpect(jsonPath("$.paths['/v1/reservations/{reservationId}'].delete").exists())
                .andExpect(jsonPath("$.components.schemas.Reservation").exists())
                .andExpect(jsonPath("$.components.schemas.Problem.properties.code").exists());

        mockMvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("title: API de Reservas de Vuelos")
                        .contains("/v1/reservations/{reservationId}"));

        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private String createReservation() throws Exception {
        return createReservation(departure);
    }

    private String createReservation(Instant departureAt) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/reservations")
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(EMAIL, "1250.50", "SCL", departureAt)))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonOf(result).get("id").asText();
    }

    private String userIdOf(String reservationId) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/reservations/{id}", reservationId))
                .andExpect(status().isOk())
                .andReturn();
        return jsonOf(result).get("userId").asText();
    }

    private JsonNode jsonOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String createBody(String email, String price, String destination, Instant departureAt) {
        return """
                {
                  "user": {
                    "email": "%s",
                    "firstName": "Ana",
                    "lastName": "Pérez"
                  },
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
                """.formatted(email, price, destination, departureAt);
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
