package com.edteam.reservations.infrastructure.security;

import com.edteam.reservations.support.MutableClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * La cuota de pedidos del borde.
 *
 * <p>Se prueba contra el filtro directamente y no por MockMvc: lo que hay que
 * verificar es el conteo, la ventana y la clave, y para eso hace falta
 * controlar el reloj.
 */
@DisplayName("Cuota de pedidos")
class RateLimitFilterTest {

    private static final Instant START = Instant.parse("2026-10-01T12:00:00Z");

    private MutableClock clock;
    private FilterChain chain;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        clock = MutableClock.at(START);
        chain = mock(FilterChain.class);
        filter = new RateLimitFilter(
                new SecurityProperties.RateLimit(true, Duration.ofMinutes(1), 3, 1),
                new ObjectMapper(), clock);
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse perform(String method, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/v1/reservations");
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("deja pasar hasta la cuota y rechaza el siguiente con 429")
    void rejectsBeyondTheQuota() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(perform("GET", "10.0.0.1").getStatus()).isEqualTo(HttpStatus.OK.value());
        }

        MockHttpServletResponse rejected = perform("GET", "10.0.0.1");

        assertThat(rejected.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(rejected.getContentType()).isEqualTo("application/problem+json");
        assertThat(rejected.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
        assertThat(rejected.getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
        verify(chain, times(3)).doFilter(any(), any());
    }

    @Test
    @DisplayName("las escrituras tienen su propia cuota, más chica: un alta cuesta mucho más que una lectura")
    void writesHaveTheirOwnQuota() throws Exception {
        assertThat(perform("POST", "10.0.0.1").getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(perform("POST", "10.0.0.1").getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        // Y no se comieron la cuota de lectura: son contadores distintos.
        assertThat(perform("GET", "10.0.0.1").getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("la ventana se reinicia: la cuota acota el abuso sostenido, no una ráfaga puntual")
    void theWindowResets() throws Exception {
        for (int i = 0; i < 4; i++) {
            perform("GET", "10.0.0.1");
        }

        clock.advance(Duration.ofMinutes(1));

        assertThat(perform("GET", "10.0.0.1").getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("la cuota es por cliente: un abusador no deja sin servicio a los demás")
    void quotasArePerClient() throws Exception {
        for (int i = 0; i < 4; i++) {
            perform("GET", "10.0.0.1");
        }

        assertThat(perform("GET", "10.0.0.2").getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("con identidad, la cuota la sigue la identidad y no la IP")
    void countsByIdentityWhenAuthenticated() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new ActorAuthenticationToken(com.edteam.reservations.support.TestFixtures.owner(), null));

        // Misma identidad desde IPs distintas: una flota de IPs es barata, una
        // flota de identidades emitidas por el IdP no.
        perform("GET", "10.0.0.1");
        perform("GET", "10.0.0.2");
        perform("GET", "10.0.0.3");

        assertThat(perform("GET", "10.0.0.4").getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("informa la cuota restante para que el cliente pueda espaciarse solo")
    void publishesTheRemainingQuota() throws Exception {
        assertThat(perform("GET", "10.0.0.1").getHeader("X-RateLimit-Remaining")).isEqualTo("2");
        assertThat(perform("GET", "10.0.0.1").getHeader("X-RateLimit-Remaining")).isEqualTo("1");
    }

    @Test
    @DisplayName("apagada no cuenta nada: el gateway ya lo hace y duplicarlo sólo gasta memoria")
    void canBeTurnedOff() throws Exception {
        RateLimitFilter disabled = new RateLimitFilter(
                new SecurityProperties.RateLimit(false, Duration.ofMinutes(1), 1, 1),
                new ObjectMapper(), clock);

        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/reservations");
            disabled.doFilter(request, new MockHttpServletResponse(), chain);
        }

        verify(chain, times(10)).doFilter(any(), any());
        verify(chain, never()).doFilter(null, null);
    }
}
