package com.edteam.reservations;

import com.edteam.reservations.infrastructure.cache.CacheStore;
import com.edteam.reservations.infrastructure.cache.MeteredCacheStore;
import com.edteam.reservations.support.AbstractPostgresIT;
import com.edteam.reservations.support.SecurityTestSupport;
import com.edteam.reservations.support.TestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El cache, enchufado: contexto real, PostgreSQL real, HTTP real y
 * <strong>sin Redis</strong>.
 *
 * <p>Esa última parte es el punto. Los unitarios prueban cada decorador contra
 * un almacén de mentira; lo que falta verificar es que la aplicación entera
 * arranque y funcione con el cache degradado al fallback en memoria, porque es
 * una restricción explícita del diseño: activar Redis tiene que ser un cambio
 * de configuración, no un requisito para levantar.
 *
 * <p>Y de paso cierra el caso que el diseño del cache tiene que descartar: que
 * una escritura deje servir un {@code 304} con la versión vieja y el cliente
 * termine comiéndose un {@code 409} evitable.
 */
@AutoConfigureMockMvc
@DisplayName("Cache de la API (PostgreSQL, sin Redis)")
class CacheIT extends AbstractPostgresIT {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MeterRegistry meterRegistry;

    private Instant departure;

    @BeforeEach
    void prepare() {
        departure = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    @DisplayName("arranca sin Redis: los tres caches quedan en memoria y medidos")
    void startsWithoutRedis() {
        assertThat(context.getBeanNamesForType(CacheStore.class))
                .containsExactlyInAnyOrder(
                        "cityCatalogCacheStore", "reservationCountCacheStore", "reservationVersionCacheStore");

        for (String name : context.getBeanNamesForType(CacheStore.class)) {
            assertThat(context.getBean(name, CacheStore.class))
                    .as("el almacén '%s' está instrumentado", name)
                    .isInstanceOf(MeteredCacheStore.class);
            assertThat(context.getBean(name, CacheStore.class).estimatedSize())
                    .as("el almacén '%s' es el fallback en memoria, que sí informa su tamaño", name)
                    .isPresent();
        }
    }

    @Test
    @DisplayName("las métricas del cache salen por Actuator, etiquetadas por cache")
    void publishesCacheMetrics() throws Exception {
        String id = createReservation();

        mockMvc.perform(get("/v1/reservations/{id}", id).with(asUser())).andExpect(status().isOk());
        mockMvc.perform(get("/v1/reservations/{id}", id).with(asUser()).header(HttpHeaders.IF_NONE_MATCH, "\"0\""))
                .andExpect(status().isNotModified());

        assertThat(meterRegistry.get(MeteredCacheStore.GETS)
                .tags(Tags.of("cache", "reservation-version", "result", "hit"))
                .counter().count())
                .as("hits del cache de versiones")
                .isPositive();
        assertThat(meterRegistry.get(MeteredCacheStore.SIZE)
                .tags(Tags.of("cache", "reservation-version")).gauge().value())
                .as("entradas vivas")
                .isPositive();

        mockMvc.perform(get("/actuator/metrics/" + MeteredCacheStore.GETS).with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableTags[?(@.tag == 'cache')]").exists());
    }

    @Test
    @DisplayName("una lectura condicional responde 304 sin cuerpo")
    void servesNotModified() throws Exception {
        String id = createReservation();

        mockMvc.perform(get("/v1/reservations/{id}", id).with(asUser()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")));

        mockMvc.perform(get("/v1/reservations/{id}", id).with(asUser()).header(HttpHeaders.IF_NONE_MATCH, "\"0\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("después de modificar, el ETag viejo ya no da 304 y el PUT siguiente no come un 409")
    void aWriteNeverLeavesAStale304() throws Exception {
        String id = createReservation();

        // El cliente lee y queda con la versión 0 cacheada del lado del servidor.
        mockMvc.perform(get("/v1/reservations/{id}", id).with(asUser()).header(HttpHeaders.IF_NONE_MATCH, "\"0\""))
                .andExpect(status().isNotModified());

        mockMvc.perform(put("/v1/reservations/{id}", id).with(asUser())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(departure)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""));

        // Si esto devolviera 304, el cliente seguiría creyendo que va por la
        // versión 0 y su próximo If-Match daría 409 sin que nada hubiera
        // cambiado de verdad. Ese es el 409 evitable que el diseño descarta.
        mockMvc.perform(get("/v1/reservations/{id}", id).with(asUser()).header(HttpHeaders.IF_NONE_MATCH, "\"0\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""));

        mockMvc.perform(put("/v1/reservations/{id}", id).with(asUser())
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(departure.plus(Duration.ofDays(1)))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("el total del listado se cachea, pero una reserva nueva aparece igual en la página")
    void theCachedCountNeverHidesRows() throws Exception {
        createReservation();

        mockMvc.perform(get("/v1/reservations").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(1)));

        createReservation();

        // Lo que se verifica es la consecuencia práctica de no invalidar el
        // total: 'page.totalElements' puede venir del cache y estar
        // desactualizado —es una pista para la interfaz, no un invariante—,
        // pero los ítems se consultan siempre, así que la reserva nueva se ve
        // en el acto. Deliberadamente no se afirma nada sobre el total: este
        // contexto se comparte entre tests y la base se trunca por detrás del
        // cache, que es la versión extrema del mismo desfase.
        mockMvc.perform(get("/v1/reservations").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    @DisplayName("en el cache no hay un solo dato de pasajero")
    void nothingSensitiveEndsUpInTheCache() throws Exception {
        String id = createReservation();
        mockMvc.perform(get("/v1/reservations/{id}", id).with(asUser())).andExpect(status().isOk());
        mockMvc.perform(get("/v1/reservations").with(asUser())).andExpect(status().isOk());

        // Lo guardado es escalar por construcción: la versión es un entero y el
        // total, un long. Se verifica desde afuera igual, porque es la
        // restricción que no puede romperse sin que nadie se entere.
        assertThat(context.getBean("reservationVersionCacheStore", CacheStore.class)
                .get("rsv:ver:" + id))
                .hasValueSatisfying(value -> assertThat(value).matches("\\d+"));
    }

    private String createReservation() throws Exception {
        MvcResult created = mockMvc.perform(post("/v1/reservations").with(asUser())
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(departure)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
    }

    /**
     * Token del titular de las reservas de este test.
     *
     * <p>Los endpoints de este test —incluido Actuator— exigen autenticación:
     * el contrato de cache que se verifica acá sólo existe del otro lado de la
     * cadena de seguridad.
     */
    private static RequestPostProcessor asUser() {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION,
                    SecurityTestSupport.bearer(TestFixtures.owner()));
            return request;
        };
    }

    private static String createBody(Instant departure) {
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
                      "firstName": "Ana",
                      "lastName": "Pérez",
                      "birthDate": "1990-05-20",
                      "documentNumber": "30123456"
                    }
                  ]
                }
                """.formatted(departure);
    }

    private static String updateBody(Instant departure) {
        return """
                {
                  "itinerary": {
                    "price": "1980.00",
                    "currency": "USD",
                    "segments": [
                      {
                        "originAirportCode": "EZE",
                        "destinationAirportCode": "SCL",
                        "airline": "AEROLINEAS ARGENTINAS",
                        "departureAt": "%s"
                      }
                    ]
                  }
                }
                """.formatted(departure);
    }
}
