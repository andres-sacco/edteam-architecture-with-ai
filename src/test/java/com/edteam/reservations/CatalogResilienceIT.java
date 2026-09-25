package com.edteam.reservations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edteam.reservations.infrastructure.adapter.in.rest.DegradationHeaderFilter;
import com.edteam.reservations.infrastructure.adapter.out.airport.CachingAirportCatalog;
import com.edteam.reservations.infrastructure.config.ResilienceConfiguration;
import com.edteam.reservations.infrastructure.resilience.Circuit;
import com.edteam.reservations.infrastructure.resilience.DegradationRecorder;
import com.edteam.reservations.support.AbstractPostgresIT;
import com.edteam.reservations.support.SecurityTestSupport;
import com.edteam.reservations.support.TestFixtures;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * El camino del pedido con el catálogo caído, medido de punta a punta.
 *
 * <p>Es el test del hallazgo más caro de la auditoría: con el cache poblado y
 * el catálogo colgado, un {@code POST} de diez tramos devolvía {@code 201}
 * <strong>después de unos noventa segundos</strong> y disparaba más de treinta
 * pedidos contra un proveedor que ya estaba sufriendo. El fallback que existía
 * para que la caída fuera invisible era justamente lo que producía el peor
 * pedido del sistema.
 *
 * <p>El catálogo falso es un {@link HttpServer} del JDK y no un
 * {@code MockRestServiceServer} porque lo que hay que simular —«acepta la
 * conexión y no contesta nunca»— es precisamente lo que un mock de cliente no
 * puede hacer: ahí está el modo de falla que importa.
 */
@AutoConfigureMockMvc
@DisplayName("Resiliencia del catálogo de ciudades (PostgreSQL + catálogo falso)")
class CatalogResilienceIT extends AbstractPostgresIT {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** Diez tramos encadenados: once ciudades distintas, el máximo del contrato. */
    private static final List<String> ROUTE =
            List.of("EZE", "SCL", "LIM", "BOG", "MEX", "MIA", "NYC", "MAD", "BCN", "PAR", "LON");

    /** El techo declarado del {@code POST}, con el desglose en {@code LatencyBudgetTest}. */
    private static final Duration POST_CEILING = Duration.ofMillis(4_200);

    private static final HungCatalog CATALOG = new HungCatalog();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private Circuit catalogCircuit;

    @DynamicPropertySource
    static void catalogProperties(DynamicPropertyRegistry registry) {
        CATALOG.start();
        registry.add("reservations.airport-catalog.base-url", CATALOG::baseUrl);
        // Umbrales chicos para que el circuito decida dentro de un test en
        // lugar de dentro de un incidente: la lógica es la misma, los números
        // son los de producción en application.yml.
        registry.add("reservations.airport-catalog.circuit-breaker.sliding-window-size", () -> 20);
        registry.add("reservations.airport-catalog.circuit-breaker.minimum-number-of-calls", () -> 5);
        registry.add("reservations.airport-catalog.circuit-breaker.wait-duration-in-open-state", () -> "500ms");
        registry.add("reservations.airport-catalog.circuit-breaker.permitted-calls-in-half-open-state", () -> 2);
        // TTL de frescura al mínimo y ventana de gracia intacta: así cada
        // pedido vuelve a preguntarle al origen —que es el camino que este
        // test mide— pero el último valor conocido sigue disponible para el
        // fallback. Con los 30 m de produccion, el segundo POST se resolveria
        // entero desde el cache y no habria nada que medir.
        registry.add("reservations.airport-catalog.cache-ttl", () -> "1ms");
        registry.add("reservations.airport-catalog.negative-cache-ttl", () -> "1ms");
        registry.add("reservations.airport-catalog.stale-while-error", () -> "2h");
    }

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("cityCatalogCacheStore")
    private com.edteam.reservations.infrastructure.cache.CacheStore cityCache;

    @BeforeEach
    void resetCatalog() {
        CATALOG.healthy();
        catalogCircuit.breaker().reset();
        // El contexto se comparte entre los métodos de la clase: sin vaciar el
        // cache, el "cache frío" de un test sería el cache caliente del
        // anterior y la aserción no probaría nada.
        ROUTE.forEach(
                code -> cityCache.evict(com.edteam.reservations.infrastructure.cache.CacheKeys.CITY_PREFIX + code));
    }

    @Test
    @DisplayName("hallazgos 1 y 12: con el cache poblado y el catálogo colgado, el POST entra en su techo")
    void aHungCatalogWithAWarmCacheStaysInsideTheBudget() throws Exception {
        // Cache caliente: este POST resuelve las once ciudades contra un
        // catálogo sano.
        createReservation().andExpect(status().isCreated());

        CATALOG.hang();
        int before = CATALOG.requests();

        long startedAt = System.nanoTime();
        MvcResult result = createReservation().andExpect(status().isCreated()).andReturn();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed)
                .as("antes eran ~90 s: once ciudades × tres intentos × (connect + read), en serie")
                .isLessThan(POST_CEILING);
        assertThat(result.getResponse().getHeader(DegradationHeaderFilter.HEADER))
                .as("la reserva se creó con datos viejos, y la respuesta lo dice")
                .isEqualTo(CachingAirportCatalog.DEPENDENCY);
        assertThat(CATALOG.requests() - before)
                .as("el fan-out en paralelo hace como mucho un intento por ciudad, no tres en serie")
                .isLessThanOrEqualTo(ROUTE.size());
        assertThat(degradedResponses()).as("y queda contado, con su motivo").isPositive();
    }

    @Test
    @DisplayName("sin nada guardado y el catálogo caído: 503 con Retry-After, nunca un 400 que mienta")
    void aColdCacheAgainstADeadCatalogAnswers503() throws Exception {
        CATALOG.hang();

        long startedAt = System.nanoTime();
        MvcResult result =
                createReservation().andExpect(status().isServiceUnavailable()).andReturn();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isLessThan(POST_CEILING);
        assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER))
                .as("reintentable y honesto: el aeropuerto puede existir, no pudimos averiguarlo")
                .isNotNull();
        assertThat(result.getResponse().getContentAsString()).contains("AIRPORT_CATALOG_UNAVAILABLE");
    }

    @Test
    @DisplayName("el circuito abre con el catálogo caído y deja de pagar el viaje")
    void theCircuitOpensAndStopsPayingForTheTrip() throws Exception {
        CATALOG.hang();

        // Unos pocos pedidos alcanzan para juntar el mínimo de llamadas.
        for (int i = 0; i < 3; i++) {
            createReservation().andReturn();
        }

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> catalogCircuit.state() == CircuitBreaker.State.OPEN);

        int before = CATALOG.requests();
        createReservation().andReturn();

        assertThat(CATALOG.requests())
                .as("con el circuito abierto, el proveedor caído no recibe ni un pedido más")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("el circuito se cierra solo cuando el catálogo vuelve: sin reinicio ni intervención")
    void theCircuitClosesByItselfWhenTheCatalogComesBack() throws Exception {
        CATALOG.hang();
        for (int i = 0; i < 3; i++) {
            createReservation().andReturn();
        }
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> catalogCircuit.state() == CircuitBreaker.State.OPEN);

        CATALOG.healthy();

        // Transición automática a semiabierto: no depende de que llegue
        // tráfico, que es la restricción de «un circuito abierto no puede ser
        // permanente».
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> catalogCircuit.state() == CircuitBreaker.State.HALF_OPEN);

        Awaitility.await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            createReservation().andReturn();
            assertThat(catalogCircuit.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        });
    }

    @Test
    @DisplayName("un 404 del catálogo no abre el circuito: es una respuesta, no una falla")
    void aNotFoundNeverOpensTheCircuit() throws Exception {
        CATALOG.unknownCities();

        for (int i = 0; i < 5; i++) {
            createReservation().andExpect(status().isBadRequest());
        }

        assertThat(catalogCircuit.state())
                .as("una ráfaga de códigos mal tipeados no puede frenar el tráfico sano")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("el estado del circuito se publica como métrica")
    void theCircuitStateIsPublished() {
        assertThat(registry.find("resilience4j.circuitbreaker.state")
                        .tag("name", ResilienceConfiguration.CATALOG_CIRCUIT)
                        .gauges())
                .as("sin esta serie no hay forma de enterarse de que el sistema está degradado")
                .isNotEmpty();
    }

    // -----------------------------------------------------------------

    private double degradedResponses() {
        return registry
                .find(DegradationRecorder.SERVED)
                .tag("dependency", CachingAirportCatalog.DEPENDENCY)
                .counters()
                .stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    private org.springframework.test.web.servlet.ResultActions createReservation() throws Exception {
        Instant departure = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        List<String> segments = new ArrayList<>();
        for (int i = 0; i < ROUTE.size() - 1; i++) {
            segments.add("""
                    {"originAirportCode":"%s","destinationAirportCode":"%s",
                     "airline":"%s","departureAt":"%s"}""".formatted(
                    ROUTE.get(i), ROUTE.get(i + 1), TestFixtures.AIRLINE, departure.plus(Duration.ofHours(i * 6L))));
        }
        String body = """
                {"itinerary":{"price":1250.50,"currency":"USD","segments":[%s]},
                 "passengers":[{"firstName":"Ana","lastName":"Pérez",
                    "birthDate":"1990-05-20","documentNumber":"30123456"}]}
                """.formatted(String.join(",", segments));

        return mockMvc.perform(post("/v1/reservations")
                .with(SecurityTestSupport.asOwner())
                .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /**
     * Catálogo falso con tres modos: sano, «no conozco esa ciudad» y colgado
     * —acepta la conexión y no contesta nunca—. El tercero es el que importa y
     * el que no se puede simular sin un socket de verdad.
     */
    private static final class HungCatalog {

        private final AtomicBoolean hung = new AtomicBoolean();
        private final AtomicBoolean unknown = new AtomicBoolean();
        private final AtomicInteger requests = new AtomicInteger();
        private HttpServer server;

        synchronized void start() {
            if (server != null) {
                return;
            }
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo levantar el catálogo falso", e);
            }
            server.createContext("/api/flights/catalog/city/", exchange -> {
                requests.incrementAndGet();
                try (InputStream body = exchange.getRequestBody()) {
                    body.readAllBytes();
                }
                if (hung.get()) {
                    // Ni responde ni cierra: el pedido queda esperando hasta
                    // que corte NUESTRO read timeout, que es el punto.
                    return;
                }
                String path = exchange.getRequestURI().getPath();
                String code = path.substring(path.lastIndexOf('/') + 1);
                if (unknown.get()) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                byte[] payload =
                        ("{\"code\":\"" + code + "\",\"name\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            });
            // Hilos virtuales: con el modo colgado, cada pedido retiene un
            // hilo del servidor falso hasta que su timeout corte.
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/flights/catalog";
        }

        void hang() {
            hung.set(true);
            unknown.set(false);
        }

        void unknownCities() {
            hung.set(false);
            unknown.set(true);
        }

        void healthy() {
            hung.set(false);
            unknown.set(false);
        }

        int requests() {
            return requests.get();
        }
    }
}
