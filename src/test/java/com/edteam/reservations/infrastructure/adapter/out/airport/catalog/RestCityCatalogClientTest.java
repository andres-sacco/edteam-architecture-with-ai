package com.edteam.reservations.infrastructure.adapter.out.airport.catalog;

import com.edteam.reservations.application.exception.AirportCatalogIntegrationException;
import com.edteam.reservations.application.exception.AirportCatalogUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * El valor de estos tests está en la tabla de clasificación: cada caso fija
 * qué respuesta de la API es "no existe", cuál es transitoria y cuál es un
 * defecto. Es lo que evita que un refactor convierta un 500 del proveedor en
 * una reserva rechazada.
 */
@DisplayName("RestCityCatalogClient")
class RestCityCatalogClientTest {

    private static final String BASE_URL = "http://catalog.test/api/flights/catalog";
    private static final String CITY_URL = BASE_URL + "/city/BUE";

    private MockRestServiceServer server;
    private RestCityCatalogClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("X-API-Key", "secreta");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestCityCatalogClient(builder.build());
    }

    @Test
    @DisplayName("200: devuelve la ciudad y manda la credencial")
    void returnsCityOn200() {
        server.expect(requestTo(CITY_URL))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("X-API-Key", "secreta"))
                .andRespond(withSuccess("""
                        {"name":"Buenos Aires","code":"BUE"}""", MediaType.APPLICATION_JSON));

        assertThat(client.findByCode("BUE")).contains(new CatalogCity("BUE", "Buenos Aires"));
        server.verify();
    }

    @Test
    @DisplayName("200 con campos de más: los ignora en lugar de fallar")
    void ignoresUnknownFields() {
        server.expect(requestTo(CITY_URL)).andRespond(withSuccess("""
                {"name":"Buenos Aires","code":"BUE","timeZone":"America/Argentina/Buenos_Aires",
                 "country":{"name":"Argentina","code":"AR"}}""", MediaType.APPLICATION_JSON));

        assertThat(client.findByCode("BUE")).contains(new CatalogCity("BUE", "Buenos Aires"));
    }

    /**
     * Es como contesta hoy el servicio real a un código desconocido, aunque el
     * contrato publicado prometa un 404. Se lee como "no existe"; un cuerpo
     * que llega pero no cumple el contrato es otra cosa y sigue fallando (ver
     * los dos tests siguientes).
     */
    @Test
    @DisplayName("200 con cuerpo vacío: el catálogo no la conoce")
    void returnsEmptyOnEmpty200() {
        server.expect(requestTo(CITY_URL)).andRespond(withSuccess());

        assertThat(client.findByCode("BUE")).isEmpty();
    }

    @Test
    @DisplayName("404: no es un error, es 'no lo conozco'")
    void returnsEmptyOn404() {
        server.expect(requestTo(CITY_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.findByCode("BUE")).isEmpty();
    }

    @Test
    @DisplayName("500: falla transitoria")
    void failsAsUnavailableOn5xx() {
        server.expect(requestTo(CITY_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom"));

        assertThatThrownBy(() -> client.findByCode("BUE"))
                .isInstanceOf(AirportCatalogUnavailableException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("429: es 4xx pero se trata como transitoria")
    void failsAsUnavailableOn429() {
        server.expect(requestTo(CITY_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.findByCode("BUE"))
                .isInstanceOf(AirportCatalogUnavailableException.class);
    }

    @Test
    @DisplayName("401: credencial mal, es un defecto nuestro")
    void failsAsIntegrationOn401() {
        server.expect(requestTo(CITY_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.findByCode("BUE"))
                .isInstanceOf(AirportCatalogIntegrationException.class)
                .hasMessageContaining("401");
    }

    @Test
    @DisplayName("400: pedido mal armado, es un defecto nuestro")
    void failsAsIntegrationOn400() {
        server.expect(requestTo(CITY_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.findByCode("BUE"))
                .isInstanceOf(AirportCatalogIntegrationException.class);
    }

    @Test
    @DisplayName("200 con cuerpo ilegible: no se toma como ciudad válida")
    void failsAsIntegrationOnUnreadableBody() {
        server.expect(requestTo(CITY_URL)).andRespond(withSuccess("no-json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.findByCode("BUE"))
                .isInstanceOf(AirportCatalogIntegrationException.class);
    }

    @Test
    @DisplayName("200 sin 'code': rompe el contrato publicado")
    void failsAsIntegrationOnBodyWithoutCode() {
        server.expect(requestTo(CITY_URL))
                .andRespond(withSuccess("{\"name\":\"Buenos Aires\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.findByCode("BUE"))
                .isInstanceOf(AirportCatalogIntegrationException.class)
                .hasMessageContaining("code");
    }

    @Test
    @DisplayName("conexión rechazada: falla transitoria")
    void failsAsUnavailableOnConnectionError() {
        server.expect(requestTo(CITY_URL)).andRespond(withException(new ConnectException("connection refused")));

        assertThatThrownBy(() -> client.findByCode("BUE"))
                .isInstanceOf(AirportCatalogUnavailableException.class)
                .hasMessageContaining("No se pudo contactar");
    }

    /**
     * Hoy no hay timeout configurado, así que este caso sólo puede darse por
     * corte del sistema operativo. Igual queda cubierto: si mañana se define
     * uno, tiene que seguir clasificando como transitorio y no como defecto.
     */
    @Test
    @DisplayName("socket cortado: falla transitoria")
    void failsAsUnavailableOnSocketTimeout() {
        server.expect(requestTo(CITY_URL)).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> client.findByCode("BUE"))
                .isInstanceOf(AirportCatalogUnavailableException.class);
    }

    @Test
    @DisplayName("código vacío: ni siquiera sale a la red")
    void rejectsBlankCode() {
        assertThatThrownBy(() -> client.findByCode("  "))
                .isInstanceOf(IllegalArgumentException.class);

        server.verify();
    }
}
