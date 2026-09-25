package com.edteam.reservations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.support.AbstractPostgresIT;
import com.edteam.reservations.support.SecurityTestSupport;
import com.edteam.reservations.support.TestFixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * H7 — la llamada al maestro de aeropuertos ya no ocurre dentro de la
 * transacción.
 *
 * <p>El hallazgo: la validación es HTTP con hasta tres intentos y ~6,5 s de
 * peor caso <em>por ciudad</em>. Adentro de {@code @Transactional}, un
 * itinerario de tres tramos contra un catálogo degradado retenía una conexión
 * del pool ~26 s; con {@code maximum-pool-size: 20}, veinte pedidos así
 * agotaban el pool y <b>la API entera</b> devolvía error, incluidos los
 * {@code GET} que no tocan el catálogo ni escriben nada.
 *
 * <p>La regla estructural la fija {@code HexagonalArchitectureTest}: ninguna
 * clase con un método transaccional puede alcanzar el catálogo. Esto lo prueba
 * desde el comportamiento, que es lo que le pasaba al usuario.
 */
@AutoConfigureMockMvc
@DisplayName("H7: el catálogo lento no agota el pool de conexiones")
class CatalogOutsideTransactionIT extends AbstractPostgresIT {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** Más de lo que tarda el {@code connection-timeout} del pool (3 s). */
    private static final Duration CATALOG_DELAY = Duration.ofMillis(1_500);

    /** Más que las conexiones del pool: es lo que antes lo agotaba. */
    private static final int CONCURRENT_WRITES = 24;

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private AirportCatalogPort airportCatalog;

    @Test
    @DisplayName("veinticuatro altas contra un catálogo lento no impiden leer una reserva")
    void aSlowCatalogDoesNotBlockReads() throws Exception {
        // Una reserva existente para leer, creada con el catálogo todavía rápido.
        String existing = createReservation();

        // Ahora el catálogo se degrada: cada consulta tarda 1,5 s.
        doAnswer(invocation -> {
                    Thread.sleep(CATALOG_DELAY.toMillis());
                    return java.util.Set.of();
                })
                .when(airportCatalog)
                .unknown(org.mockito.ArgumentMatchers.anyCollection());

        CountDownLatch start = new CountDownLatch(1);
        AtomicLong slowestRead = new AtomicLong();

        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_WRITES + 1)) {
            List<Future<?>> writes = new java.util.ArrayList<>();
            for (int i = 0; i < CONCURRENT_WRITES; i++) {
                writes.add(pool.submit(() -> {
                    start.await();
                    createReservation();
                    return null;
                }));
            }

            Future<?> reads = pool.submit(() -> {
                start.await();
                // Mientras las escrituras esperan al catálogo, se lee.
                for (int i = 0; i < 20; i++) {
                    long startedAt = System.nanoTime();
                    mockMvc.perform(get("/v1/reservations/{id}", existing).with(SecurityTestSupport.asOwner()))
                            .andExpect(status().isOk());
                    slowestRead.accumulateAndGet((System.nanoTime() - startedAt) / 1_000_000, Math::max);
                }
                return null;
            });

            start.countDown();
            reads.get(60, TimeUnit.SECONDS);
            for (Future<?> write : writes) {
                write.get(60, TimeUnit.SECONDS);
            }
        }

        // Antes esto fallaba con el timeout del pool: las veinte conexiones
        // estaban retenidas por transacciones esperando HTTP. Ahora las
        // escrituras esperan al catálogo SIN una conexión tomada, así que la
        // lectura pasa por al lado.
        assertThat(slowestRead.get())
                .as("el GET más lento mientras %d escrituras esperan al catálogo", CONCURRENT_WRITES)
                .isLessThan(CATALOG_DELAY.toMillis());
        assertThat(countRows("reserva")).isEqualTo(CONCURRENT_WRITES + 1L);
    }

    private String createReservation() throws Exception {
        Instant departure = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        String body = """
                {"itinerary":{"price":1250.50,"currency":"USD","segments":[
                   {"originAirportCode":"EZE","destinationAirportCode":"SCL",
                    "airline":"%s","departureAt":"%s"}]},
                 "passengers":[{"firstName":"Ana","lastName":"Pérez",
                    "birthDate":"1990-05-20","documentNumber":"30123456"}]}
                """.formatted(TestFixtures.AIRLINE, departure);

        String location = mockMvc.perform(post("/v1/reservations")
                        .with(SecurityTestSupport.asOwner())
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.LOCATION);
        return location == null ? "" : location.substring(location.lastIndexOf('/') + 1);
    }
}
