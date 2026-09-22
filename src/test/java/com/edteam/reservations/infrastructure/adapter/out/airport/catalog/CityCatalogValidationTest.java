package com.edteam.reservations.infrastructure.adapter.out.airport.catalog;

import com.edteam.reservations.application.exception.AirportCatalogIntegrationException;
import com.edteam.reservations.application.exception.AirportCatalogUnavailableException;
import com.edteam.reservations.application.exception.UnknownAirportException;
import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.application.service.AirportExistenceValidator;
import com.edteam.reservations.domain.model.AirportCode;
import com.edteam.reservations.domain.model.Itinerary;
import com.edteam.reservations.infrastructure.adapter.out.airport.CachingAirportCatalog;
import com.edteam.reservations.infrastructure.cache.InMemoryCacheStore;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * La validación que corre en cada POST y PUT, con la cadena real armada:
 * validador → cache → adaptador del puerto → reintentos → cliente HTTP.
 *
 * <p>Los tests de {@code RestCityCatalogClient} prueban la traducción de cada
 * respuesta HTTP. Lo que se prueba acá es la consecuencia para el pedido, que
 * es lo que le importa a quien reserva: qué se rechaza, qué se propaga y
 * cuántas veces se sale a la red. Sin esto, la diferencia entre "esa ciudad no
 * existe" y "el catálogo está caído" podría perderse en cualquier punto de la
 * cadena sin que nada falle.
 */
@DisplayName("Validación de ciudades del itinerario")
class CityCatalogValidationTest {

    private static final String BASE_URL = "http://catalog.test/api/flights/catalog";

    private static final AirportCode BUE = AirportCode.of("BUE");
    private static final AirportCode SCL = AirportCode.of("SCL");
    private static final AirportCode MIA = AirportCode.of("MIA");

    private static final RetryingCityCatalogClient.Retry RETRY =
            new RetryingCityCatalogClient.Retry(3, Duration.ofMillis(10), Duration.ofMillis(40));

    private MockRestServiceServer server;
    private AirportExistenceValidator validator;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        // Sin orden fijo: el validador recorre las ciudades del itinerario y el
        // orden de esa consulta no es parte del contrato.
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();

        // El sleeper no duerme: lo que se prueba acá es la cadena, no el reloj.
        // La política de backoff tiene sus propios tests.
        CityCatalogClient client = new RetryingCityCatalogClient(
                new RestCityCatalogClient(builder.build()), RETRY, duration -> true);

        AirportCatalogPort catalog = new CachingAirportCatalog(
                new CatalogAirportCatalog(client),
                new InMemoryCacheStore(TestFixtures.fixedClock(), 100),
                new CachingAirportCatalog.Ttl(
                        Duration.ofMinutes(30), Duration.ofMinutes(5), Duration.ofHours(2)),
                TestFixtures.fixedClock());
        validator = new AirportExistenceValidator(catalog);
    }

    @Test
    @DisplayName("todas las ciudades existen: el pedido sigue")
    void acceptsItineraryWithKnownCities() {
        expectCity("BUE", "Buenos Aires");
        expectCity("SCL", "Santiago");
        expectCity("MIA", "Miami");

        assertThatCode(() -> validator.validate(connectingItinerary())).doesNotThrowAnyException();

        server.verify();
    }

    @Test
    @DisplayName("el catálogo no conoce una ciudad: se rechaza el pedido y se nombra cuál")
    void rejectsItineraryWithUnknownCity() {
        expectCity("BUE", "Buenos Aires");
        expectCity("SCL", "Santiago");
        server.expect(requestTo(BASE_URL + "/city/MIA")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> validator.validate(connectingItinerary()))
                .isInstanceOf(UnknownAirportException.class)
                .hasMessageContaining("MIA");
    }

    /**
     * El caso que justifica toda la clasificación de fallos: un catálogo caído
     * no puede traducirse en "ciudad desconocida". Si esto fallara, el sistema
     * rechazaría con 400 reservas perfectamente válidas cada vez que el
     * proveedor se cae, y el cliente correría a corregir un dato que está bien.
     */
    @Test
    @DisplayName("el catálogo está caído: no se rechaza el pedido, se propaga la falla")
    void propagatesCatalogOutageInsteadOfRejecting() {
        server.expect(ExpectedCount.manyTimes(), requestTo(BASE_URL + "/city/BUE"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> validator.validate(directItinerary()))
                .isInstanceOf(AirportCatalogUnavailableException.class);
    }

    /**
     * Así contesta hoy el catálogo real a un código que no conoce: 200 sin
     * cuerpo en vez del 404 que promete el contrato. El pedido tiene que
     * terminar igual que con un 404 —rechazado y nombrando el código— y no en
     * un error interno: para quien reserva, un typo tiene que ser un 400 que
     * le diga qué corregir.
     */
    @Test
    @DisplayName("el catálogo responde 200 vacío: se rechaza el pedido como ciudad inexistente")
    void treatsEmptyResponseAsUnknownCity() {
        expectCity("BUE", "Buenos Aires");
        server.expect(ExpectedCount.manyTimes(), requestTo(BASE_URL + "/city/SCL"))
                .andRespond(withSuccess());

        assertThatThrownBy(() -> validator.validate(directItinerary()))
                .isInstanceOf(UnknownAirportException.class)
                .hasMessageContaining("SCL");
    }

    /**
     * El límite de la tolerancia anterior: si el cuerpo llega pero no cumple
     * el contrato, hubo intención de responder y salió mal. Eso es integración
     * rota, no una ciudad que falta, y no puede terminar en un 400 que culpe
     * al cliente.
     */
    @Test
    @DisplayName("el catálogo responde con un cuerpo sin 'code': falla, no es ciudad inexistente")
    void doesNotTreatMalformedBodyAsUnknownCity() {
        expectCity("BUE", "Buenos Aires");
        server.expect(ExpectedCount.manyTimes(), requestTo(BASE_URL + "/city/SCL"))
                .andRespond(withSuccess("{\"name\":\"Santiago\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> validator.validate(directItinerary()))
                .isInstanceOf(AirportCatalogIntegrationException.class);
    }

    @Test
    @DisplayName("dos pedidos con la misma ciudad: se consulta el catálogo una sola vez")
    void cachesAcrossRequests() {
        expectCity("BUE", "Buenos Aires");
        expectCity("SCL", "Santiago");

        validator.validate(directItinerary());
        validator.validate(directItinerary());

        // Con expectativas de una sola vez, un segundo viaje a la red haría fallar el test.
        server.verify();
    }

    @Test
    @DisplayName("un 503 pasajero se reintenta y el pedido sigue: el usuario no ve la caída")
    void retriesTransientFailures() {
        // Primer intento 503, segundo 200. Es el caso que justifica los
        // reintentos: sin ellos, un hipo del proveedor rechaza una reserva
        // perfectamente válida.
        server.expect(requestTo(BASE_URL + "/city/BUE"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expectCity("BUE", "Buenos Aires");
        expectCity("SCL", "Santiago");

        assertThatCode(() -> validator.validate(directItinerary())).doesNotThrowAnyException();

        server.verify();
    }

    @Test
    @DisplayName("un 429 también se reintenta: nos están limitando, no nos equivocamos")
    void retriesRateLimiting() {
        server.expect(requestTo(BASE_URL + "/city/BUE"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        expectCity("BUE", "Buenos Aires");
        expectCity("SCL", "Santiago");

        assertThatCode(() -> validator.validate(directItinerary())).doesNotThrowAnyException();

        server.verify();
    }

    @Test
    @DisplayName("un 401 no se reintenta: la credencial no se arregla insistiendo")
    void doesNotRetryABrokenIntegration() {
        // Exactamente una llamada: con ExpectedCount.once(), un reintento
        // haría fallar el test.
        server.expect(ExpectedCount.once(), requestTo(BASE_URL + "/city/BUE"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> validator.validate(directItinerary()))
                .isInstanceOf(AirportCatalogIntegrationException.class);

        server.verify();
    }

    private void expectCity(String code, String name) {
        server.expect(requestTo(BASE_URL + "/city/" + code))
                .andRespond(withSuccess("{\"name\":\"%s\",\"code\":\"%s\"}".formatted(name, code),
                        MediaType.APPLICATION_JSON));
    }

    private static Itinerary directItinerary() {
        return Itinerary.newItinerary(TestFixtures.price(),
                List.of(TestFixtures.newSegment(BUE, SCL, TestFixtures.DEPARTURE)));
    }

    private static Itinerary connectingItinerary() {
        return Itinerary.newItinerary(TestFixtures.price(), List.of(
                TestFixtures.newSegment(BUE, SCL, TestFixtures.DEPARTURE),
                TestFixtures.newSegment(SCL, MIA, TestFixtures.CONNECTION_DEPARTURE)));
    }
}
