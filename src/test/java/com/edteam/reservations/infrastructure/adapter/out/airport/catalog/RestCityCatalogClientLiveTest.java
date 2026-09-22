package com.edteam.reservations.infrastructure.adapter.out.airport.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato contra el catálogo de verdad, el de {@code compose.yaml}.
 *
 * <p>Apagado por defecto: el build no puede depender de un servicio externo.
 * Se corre a mano cuando hace falta confirmar que lo que asumimos del
 * proveedor sigue siendo cierto:
 *
 * <pre>{@code
 * docker compose up -d api-catalog
 * ./mvnw test -Dtest=RestCityCatalogClientLiveTest -Dcatalog.live=true
 * }</pre>
 *
 * <p>Existe por un motivo concreto: los tests con servidor simulado prueban lo
 * que <em>creemos</em> que devuelve la API, y acá se descubrió que no coincide
 * con el contrato publicado —un código desconocido devuelve 200 vacío, no
 * 404—. Un mock jamás habría avisado.
 */
@EnabledIfSystemProperty(named = "catalog.live", matches = "true")
@DisplayName("RestCityCatalogClient contra el catálogo real")
class RestCityCatalogClientLiveTest {

    private static final String BASE_URL = "http://localhost:6070/api/flights/catalog";

    private final CityCatalogClient client =
            new RestCityCatalogClient(RestClient.builder().baseUrl(BASE_URL).build());

    @Test
    @DisplayName("una ciudad que existe se resuelve")
    void resolvesKnownCity() {
        assertThat(client.findByCode("BUE")).hasValueSatisfying(city -> {
            assertThat(city.code()).isEqualTo("BUE");
            assertThat(city.name()).isEqualTo("Buenos Aires");
        });
    }

    @Test
    @DisplayName("un código que el catálogo no conoce no existe")
    void doesNotResolveUnknownCode() {
        assertThat(client.findByCode("ZZZ")).isEmpty();
    }

    @Test
    @DisplayName("un código de aeropuerto no es una ciudad: el catálogo no lo conoce")
    void doesNotResolveAirportCode() {
        assertThat(client.findByCode("EZE")).isEmpty();
    }

    /**
     * Centinela del incumplimiento: hoy el servicio contesta 200 sin cuerpo a
     * un código desconocido, y de ahí sale el "no existe" de los dos tests de
     * arriba. El día que devuelva el 404 que promete el contrato, este test
     * falla: es el aviso para volver a la lectura estricta —vacío = error— sin
     * perder el "no existe".
     */
    @Test
    @DisplayName("centinela: el 'no existe' todavía llega como 200 vacío, no como 404")
    void unknownCodeStillAnswersEmpty200() {
        ResponseEntity<String> response = RestClient.create()
                .get().uri(BASE_URL + "/city/ZZZ")
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNull();
    }
}
