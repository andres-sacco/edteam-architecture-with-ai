package com.edteam.reservations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edteam.reservations.infrastructure.config.ResilienceConfiguration;
import com.edteam.reservations.support.AbstractPostgresIT;
import com.edteam.reservations.support.SecurityTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Se puede saber, sin entrar al servidor, si un circuito está abierto y hace
 * cuánto.
 *
 * <p>Antes la respuesta era que no. {@code /actuator/metrics} devuelve un
 * medidor por vez en JSON: sirve para mirar, no para alertar, y las alertas
 * con umbral que el diseño justifica no tenían de dónde salir. Lo que se abre
 * con esto es el <em>scrape</em> de métricas, no la superficie de la API: el
 * puerto de gestión sigue sin publicarse hacia afuera, y que acá vaya al
 * mismo puerto es una concesión de MockMvc, que no puede pedir contra dos.
 */
@AutoConfigureMockMvc
// Sin esto, el soporte de test de Spring Boot apaga TODOS los exportadores de
// metricas salvo el simple: el registro de Prometheus no se crearia y este
// test verificaria la ausencia de lo que quiere probar.
@AutoConfigureObservability
@DisplayName("Observabilidad de la resiliencia")
class ResilienceObservabilityIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry registry;

    @Test
    @DisplayName("hay un registro de Prometheus y el endpoint responde")
    void theScrapeEndpointAnswers() throws Exception {
        assertThat(registry)
                .as("el scrape necesita un registro de Prometheus, no el simple de los tests")
                .isInstanceOf(PrometheusMeterRegistry.class);

        mockMvc.perform(get("/actuator/prometheus").with(SecurityTestSupport.asOwner()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("el estado de los tres circuitos se publica con su nombre")
    void thethreeCircuitStatesArePublished() throws Exception {
        String scrape = mockMvc.perform(get("/actuator/prometheus").with(SecurityTestSupport.asOwner()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(scrape).contains("resilience4j_circuitbreaker_state");
        for (String circuit : new String[] {
            ResilienceConfiguration.CATALOG_CIRCUIT,
            ResilienceConfiguration.REDIS_CIRCUIT,
            ResilienceConfiguration.BROKER_CIRCUIT
        }) {
            assertThat(scrape).as("estado del circuito '%s'", circuit).contains("name=\"" + circuit + "\"");
        }
    }

    @Test
    @DisplayName("se publican las llamadas que el circuito NO permitió: es lo que mide cuánto ahorró")
    void theCallsTheCircuitRefusedArePublished() throws Exception {
        String scrape = mockMvc.perform(get("/actuator/prometheus").with(SecurityTestSupport.asOwner()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(scrape).contains("resilience4j_circuitbreaker_calls");
        assertThat(scrape).contains("resilience4j_bulkhead_available_concurrent_calls");
    }

    @Test
    @DisplayName("los medidores de la política propia existen: reintentos, degradación y presupuesto")
    void ourOwnResilienceMetersExist() {
        // «Revisar la métrica de reintentos bajo carga con la dependencia
        // caída» era imposible: sólo había dos log.warn, y tampoco había forma
        // de saber cuántas respuestas salieron por el fallback ni cuán viejo
        // era el dato.
        // El decorador de reintentos sólo se cablea con un catálogo remoto y
        // el relay está apagado en estos tests: sus métricas se verifican en
        // RetryingCityCatalogClientTest y en CatalogResilienceIT. Acá van las
        // que existen siempre.
        assertThat(registry.find("reservations.cache.gets").counters())
                .as("el medidor va POR FUERA del circuito: con el circuito abierto el panel "
                        + "tiene que mostrar degradación, no silencio")
                .isNotEmpty();
        assertThat(registry.find("resilience4j.circuitbreaker.state").gauges())
                .as("estado de los circuitos")
                .isNotEmpty();
        assertThat(registry.find("reservations.outbox.pending").gauge())
                .as("el lag del outbox explica la causa cuando el circuito del broker está abierto")
                .isNotNull();
    }
}
