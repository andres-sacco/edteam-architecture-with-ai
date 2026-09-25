package com.edteam.reservations.infrastructure.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/**
 * El hallazgo 14: el correlation id no salía del proceso.
 */
@DisplayName("Propagación del correlation id hacia el catálogo")
class CorrelationIdPropagationTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("la llamada saliente lleva el X-Correlation-Id del pedido")
    void theOutgoingCallCarriesTheHeader() throws IOException {
        MDC.put(LogFields.CORRELATION_ID, "audit-0000-0001");
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("/city/EZE"));

        CorrelationIdPropagation.interceptor()
                .intercept(request, new byte[0], (req, body) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK));

        assertThat(request.getHeaders().getFirst(CorrelationIdPropagation.HEADER))
                .isEqualTo("audit-0000-0001");
    }

    @Test
    @DisplayName("sin id en el MDC no se inventa uno")
    void withoutAnIdNoHeaderIsForged() throws IOException {
        // Un header con un valor fabricado acá apuntaría a un pedido que no
        // existe, y una traza que miente es peor que una que se corta.
        MDC.clear();
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("/city/EZE"));

        CorrelationIdPropagation.interceptor()
                .intercept(request, new byte[0], (req, body) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK));

        assertThat(request.getHeaders().getFirst(CorrelationIdPropagation.HEADER))
                .isNull();
    }

    @Test
    @DisplayName("el header que sale es el mismo que entró: el id es uno solo en todo el camino")
    void theHeaderNameMatchesTheInboundOne() {
        // Si los nombres divergieran, el sistema de al lado guardaría el id en
        // un campo distinto y el pivote entre los dos logs dejaría de ser
        // directo — que es todo lo que esta propagación compra.
        assertThat(CorrelationIdPropagation.HEADER)
                .isEqualTo(com.edteam.reservations.infrastructure.security.CorrelationIdFilter.HEADER);
    }
}
