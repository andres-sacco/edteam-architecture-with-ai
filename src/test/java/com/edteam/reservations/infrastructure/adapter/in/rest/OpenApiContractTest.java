package com.edteam.reservations.infrastructure.adapter.in.rest;

import com.edteam.reservations.application.exception.ReservationNotFoundException;
import com.edteam.reservations.application.port.in.CancelReservationUseCase;
import com.edteam.reservations.application.port.in.CreateReservationUseCase;
import com.edteam.reservations.application.port.in.GetReservationUseCase;
import com.edteam.reservations.application.port.in.ListReservationsUseCase;
import com.edteam.reservations.application.port.in.ModifyReservationUseCase;
import com.edteam.reservations.domain.model.ReservationId;
import com.edteam.reservations.infrastructure.adapter.in.rest.mapper.ReservationRestMapper;
import com.edteam.reservations.infrastructure.config.OpenApiConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica que el documento que genera springdoc describa de verdad esta API.
 *
 * <p>Generar la documentación resuelve el problema de que el archivo quede
 * viejo, pero introduce otro: lo generado sólo es tan bueno como las
 * anotaciones. Un endpoint sin {@code @Operation} igual aparece —vacío—, y un
 * código de estado que el {@code @RestControllerAdvice} produce pero que nadie
 * declaró no aparece en ningún lado. Nada de eso rompe el build por sí solo.
 *
 * <p>Estos tests cubren esa brecha. Comparan el documento contra tres fuentes
 * independientes de las anotaciones:
 *
 * <ol>
 *   <li>las rutas que Spring tiene registradas, en los dos sentidos;</li>
 *   <li>los códigos de estado que el adaptador realmente puede devolver, que
 *       son los que ejercita {@code ReservationControllerTest};</li>
 *   <li>el cuerpo de una respuesta de error real, propiedad por propiedad.</li>
 * </ol>
 */
@WebMvcTest
@Import({ReservationRestMapper.class, OpenApiConfiguration.class})
@ImportAutoConfiguration({
        SpringDocConfiguration.class,
        SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class
})
@DisplayName("Contrato OpenAPI generado")
class OpenApiContractTest {

    private static final String API_DOCS = "/v3/api-docs";

    private static final String RESERVATIONS = "/v1/reservations";
    private static final String RESERVATION = "/v1/reservations/{reservationId}";

    /**
     * Códigos que cada operación puede devolver.
     *
     * <p>No se deducen del documento —sería circular—: son los que
     * {@code ReservationControllerTest} ejercita contra el controller real.
     */
    private static final Map<String, Set<String>> EXPECTED_STATUS_CODES = Map.of(
            "post " + RESERVATIONS, Set.of("200", "201", "400", "409"),
            "get " + RESERVATIONS, Set.of("200", "400"),
            "get " + RESERVATION, Set.of("200", "304", "400", "404"),
            "put " + RESERVATION, Set.of("200", "400", "404", "409"),
            "delete " + RESERVATION, Set.of("200", "400", "404", "409"));

    private static JsonNode document;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CreateReservationUseCase createReservation;

    @MockitoBean
    private GetReservationUseCase getReservation;

    @MockitoBean
    private ListReservationsUseCase listReservations;

    @MockitoBean
    private ModifyReservationUseCase modifyReservation;

    @MockitoBean
    private CancelReservationUseCase cancelReservation;

    /**
     * El cache de versiones es una dependencia del controller. Acá alcanza con
     * un mock: lo que se verifica es el documento, no el comportamiento del
     * cache, y un mock devuelve siempre un miss, o sea el camino al origen.
     */
    @MockitoBean
    private ReservationVersionCache versionCache;

    @BeforeAll
    static void resetCache() {
        // El documento se arma una vez por test: springdoc lo cachea por grupo
        // y el contexto se comparte entre los métodos de la clase.
        document = null;
    }

    // ------------------------------------------------------------------
    // El documento contra las rutas reales
    // ------------------------------------------------------------------

    @Test
    @DisplayName("toda operación documentada existe, y toda ruta que existe está documentada")
    void documentsExactlyTheRoutesTheApplicationExposes() throws Exception {
        Set<String> documented = operationsInDocument();

        // Si el documento saliera vacío, comparar dos conjuntos vacíos no
        // probaría nada.
        assertThat(documented).hasSize(5);

        assertThat(documented)
                .as("operaciones del documento generado frente a rutas registradas")
                .isEqualTo(operationsInRuntime());
    }

    @Test
    @DisplayName("cada operación declara todos los códigos de estado que puede devolver")
    void documentsEveryStatusCodeTheApiCanReturn() throws Exception {
        JsonNode paths = document().get("paths");

        EXPECTED_STATUS_CODES.forEach((operation, expected) -> {
            String[] parts = operation.split(" ", 2);
            JsonNode responses = paths.get(parts[1]).get(parts[0]).get("responses");

            assertThat(fieldNames(responses))
                    .as("códigos declarados para %s", operation)
                    .containsExactlyInAnyOrderElementsOf(expected);
        });
    }

    @Test
    @DisplayName("los errores se documentan como problem+json y no como JSON común")
    void documentsErrorsAsProblemJson() throws Exception {
        JsonNode paths = document().get("paths");

        EXPECTED_STATUS_CODES.forEach((operation, codes) -> {
            String[] parts = operation.split(" ", 2);
            JsonNode responses = paths.get(parts[1]).get(parts[0]).get("responses");

            codes.stream().filter(code -> code.startsWith("4")).forEach(code -> {
                JsonNode content = responses.get(code).get("content");
                assertThat(fieldNames(content))
                        .as("tipo de medio del error %s en %s", code, operation)
                        .containsExactly(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                assertThat(content.get(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                        .get("schema").get("$ref").asText())
                        .as("esquema del error %s en %s", code, operation)
                        .endsWith("/Problem");
            });
        });
    }

    // ------------------------------------------------------------------
    // Los dos detalles del diseño tienen que estar documentados
    // ------------------------------------------------------------------

    @Test
    @DisplayName("documenta uno por uno los parámetros de consulta del listado")
    void documentsEveryQueryParameterOfTheListing() throws Exception {
        // El listado recibe un objeto y no parámetros sueltos. Si el generador
        // no lo expande, el documento declara un único parámetro con forma de
        // objeto y nadie puede deducir cómo se llama al endpoint: pasa el test
        // de rutas y de códigos, y la documentación igual es inservible.
        JsonNode parameters = document().get("paths").get(RESERVATIONS).get("get").get("parameters");

        Set<String> names = new TreeSet<>();
        parameters.forEach(parameter -> names.add(parameter.get("name").asText()));

        assertThat(names).containsExactlyInAnyOrder(
                "userId", "status", "departureFrom", "departureTo", "page", "size", "sort");
        parameters.forEach(parameter -> assertThat(parameter.get("in").asText())
                .as("ubicación del parámetro %s", parameter.get("name").asText())
                .isEqualTo("query"));
    }

    @Test
    @DisplayName("las colecciones obligatorias del pedido se documentan como tales")
    void documentsMinimumSizeOfRequiredCollections() throws Exception {
        // La API rechaza con 400 una reserva sin pasajeros o sin tramos. Si el
        // documento dice minItems: 0, está autorizando algo que no existe: un
        // cliente generado a partir de él construiría pedidos que siempre fallan.
        assertThat(schema("CreateReservationRequest").get("properties").get("passengers").get("minItems").asInt())
                .as("mínimo de pasajeros")
                .isEqualTo(1);
        assertThat(schema("ItineraryRequest").get("properties").get("segments").get("minItems").asInt())
                .as("mínimo de tramos")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("cada operación tiene un operationId propio: es el nombre del método en los clientes generados")
    void everyOperationHasItsOwnOperationId() throws Exception {
        JsonNode paths = document().get("paths");
        Set<String> operationIds = new TreeSet<>();

        paths.forEach(path -> path.forEach(operation ->
                operationIds.add(operation.get("operationId").asText())));

        assertThat(operationIds).containsExactlyInAnyOrder(
                "createReservation", "getReservation", "listReservations",
                "updateReservation", "cancelReservation");
    }

    @Test
    @DisplayName("documenta el header de idempotencia como obligatorio en el alta")
    void documentsTheIdempotencyKeyHeader() throws Exception {
        JsonNode header = parameterOf(RESERVATIONS, "post", ReservationController.IDEMPOTENCY_KEY_HEADER);

        assertThat(header).as("parámetro Idempotency-Key en el POST").isNotNull();
        assertThat(header.get("in").asText()).isEqualTo("header");
        assertThat(header.get("required").asBoolean()).isTrue();
        assertThat(header.get("schema").get("format").asText()).isEqualTo("uuid");
    }

    @Test
    @DisplayName("documenta If-Match como obligatorio en las operaciones que escriben")
    void documentsTheIfMatchHeader() throws Exception {
        for (String method : List.of("put", "delete")) {
            JsonNode header = parameterOf(RESERVATION, method, "If-Match");

            assertThat(header).as("parámetro If-Match en el %s", method.toUpperCase(Locale.ROOT)).isNotNull();
            assertThat(header.get("in").asText()).isEqualTo("header");
            assertThat(header.get("required").asBoolean()).isTrue();
        }
    }

    @Test
    @DisplayName("documenta el ETag de las respuestas, que es lo que el cliente necesita para escribir")
    void documentsTheETagResponseHeader() throws Exception {
        JsonNode paths = document().get("paths");

        assertThat(paths.get(RESERVATIONS).get("post").get("responses").get("201")
                .get("headers").has("ETag")).isTrue();
        assertThat(paths.get(RESERVATIONS).get("post").get("responses").get("201")
                .get("headers").has("Location")).isTrue();
        assertThat(paths.get(RESERVATION).get("get").get("responses").get("200")
                .get("headers").has("ETag")).isTrue();
        assertThat(paths.get(RESERVATION).get("put").get("responses").get("200")
                .get("headers").has("ETag")).isTrue();
        assertThat(paths.get(RESERVATION).get("delete").get("responses").get("200")
                .get("headers").has("ETag")).isTrue();
    }

    @Test
    @DisplayName("documenta If-None-Match y el 304, que es lo que hace usable la lectura condicional")
    void documentsTheConditionalRead() throws Exception {
        // Un 304 que la API devuelve y el documento no menciona convierte al
        // cliente generado en uno que trata la respuesta como un error.
        JsonNode header = parameterOf(RESERVATION, "get", "If-None-Match");

        assertThat(header).as("parámetro If-None-Match en el GET").isNotNull();
        assertThat(header.get("in").asText()).isEqualTo("header");
        assertThat(header.get("required").asBoolean())
                .as("If-None-Match es opcional, a diferencia de If-Match")
                .isFalse();

        JsonNode notModified = document().get("paths").get(RESERVATION).get("get").get("responses").get("304");
        assertThat(notModified).as("respuesta 304 declarada").isNotNull();
        assertThat(notModified.get("headers").has("ETag")).isTrue();
    }

    @Test
    @DisplayName("la representación de la reserva no expone la versión ni la clave de idempotencia")
    void reservationSchemaHidesInternalDetails() throws Exception {
        Set<String> properties = fieldNames(schema("Reservation").get("properties"));

        assertThat(properties).containsExactlyInAnyOrder(
                "id", "status", "userId", "itinerary", "passengers",
                "createdAt", "updatedAt", "cancelledAt");
    }

    // ------------------------------------------------------------------
    // El esquema de error contra un error de verdad
    // ------------------------------------------------------------------

    @Test
    @DisplayName("el esquema de error describe el cuerpo que la aplicación realmente devuelve")
    void errorSchemaMatchesARealErrorBody() throws Exception {
        when(getReservation.getById(any()))
                .thenThrow(new ReservationNotFoundException(ReservationId.of(999L)));

        JsonNode realBody = objectMapper.readTree(mockMvc.perform(get("/v1/reservations/999"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString());

        assertThat(fieldNames(realBody))
                .as("propiedades de un 404 real, todas declaradas en el esquema Problem")
                .isSubsetOf(fieldNames(schema("Problem").get("properties")));
        assertThat(realBody.has("code")).isTrue();
    }

    @Test
    @DisplayName("el detalle campo por campo de la validación también está declarado")
    void validationErrorsAreDocumented() throws Exception {
        JsonNode realBody = objectMapper.readTree(mockMvc.perform(post(RESERVATIONS)
                        .header(ReservationController.IDEMPOTENCY_KEY_HEADER,
                                "3f1a9c7e-0f6e-4a39-9d2c-8b5f0c1e7a44")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": 1}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString());

        assertThat(realBody.has("errors")).isTrue();
        assertThat(fieldNames(realBody)).isSubsetOf(fieldNames(schema("Problem").get("properties")));
        assertThat(fieldNames(realBody.get("errors").get(0)))
                .as("campos de un error de validación real, contra el esquema FieldError")
                .isSubsetOf(fieldNames(schema("FieldError").get("properties")));
    }

    // ------------------------------------------------------------------
    // Metadatos
    // ------------------------------------------------------------------

    @Test
    @DisplayName("el documento se identifica")
    void describesTheApiItself() throws Exception {
        JsonNode info = document().get("info");

        assertThat(info.get("title").asText()).isEqualTo("API de Reservas de Vuelos");
        assertThat(info.get("version").asText()).isEqualTo("1.0.0");
        assertThat(document().get("openapi").asText()).startsWith("3.1");
    }

    @Test
    @DisplayName("el servidor es el host desde el que se pidió el documento, no un entorno fijo")
    void serverPointsAtWhoeverAskedForTheDocument() throws Exception {
        JsonNode servers = document().get("servers");

        assertThat(servers).isNotEmpty();
        servers.forEach(server -> {
            String url = server.get("url").asText();

            // Swagger UI ejecuta contra el primer servidor del documento. Con una
            // lista fija encabezada por producción, probar desde el entorno local
            // dispararía pedidos reales contra producción.
            assertThat(url)
                    .as("servidor declarado en el documento")
                    .startsWith("http://localhost");

            // Las rutas ya llevan /v1: si el servidor también lo trajera, quedaría
            // duplicado en cada pedido.
            assertThat(url).doesNotContain("/v1");
        });
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private Set<String> operationsInDocument() throws Exception {
        Set<String> operations = new TreeSet<>();
        JsonNode paths = document().get("paths");

        paths.fieldNames().forEachRemaining(path ->
                paths.get(path).fieldNames().forEachRemaining(method ->
                        operations.add("%s %s".formatted(method.toUpperCase(Locale.ROOT), path))));

        return operations;
    }

    private Set<String> operationsInRuntime() {
        Set<String> operations = new TreeSet<>();

        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (!isOwnAdapter(handler)) {
                return;
            }
            for (String pattern : patternsOf(info)) {
                info.getMethodsCondition().getMethods()
                        .forEach(method -> operations.add("%s %s".formatted(method.name(), pattern)));
            }
        });

        return operations;
    }

    /** Deja afuera lo que no es parte de la API: springdoc, actuator, páginas de error. */
    private static boolean isOwnAdapter(HandlerMethod handler) {
        return handler.getBeanType().getPackageName()
                .startsWith("com.edteam.reservations.infrastructure.adapter.in.rest");
    }

    private static List<String> patternsOf(RequestMappingInfo info) {
        PathPatternsRequestCondition patterns = info.getPathPatternsCondition();
        if (patterns == null) {
            return List.copyOf(info.getPatternValues());
        }
        return patterns.getPatterns().stream().map(Object::toString).toList();
    }

    private JsonNode parameterOf(String path, String method, String name) throws Exception {
        JsonNode parameters = document().get("paths").get(path).get(method).get("parameters");
        if (parameters == null) {
            return null;
        }
        for (JsonNode parameter : parameters) {
            if (name.equals(parameter.get("name").asText())) {
                return parameter;
            }
        }
        return null;
    }

    private JsonNode schema(String name) throws Exception {
        JsonNode schema = document().get("components").get("schemas").get(name);
        assertThat(schema).as("esquema %s en el documento", name).isNotNull();
        return schema;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        if (node != null) {
            Iterator<String> iterator = node.fieldNames();
            while (iterator.hasNext()) {
                names.add(iterator.next());
            }
        }
        return names;
    }

    /** El documento generado, leído una sola vez por clase. */
    private JsonNode document() throws Exception {
        if (document == null) {
            String json = mockMvc.perform(get(API_DOCS))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            document = objectMapper.readTree(json);
        }
        return document;
    }
}
