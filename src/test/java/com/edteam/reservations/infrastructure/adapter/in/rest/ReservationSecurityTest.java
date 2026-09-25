package com.edteam.reservations.infrastructure.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edteam.reservations.application.exception.ReservationNotFoundException;
import com.edteam.reservations.application.port.in.CancelReservationUseCase;
import com.edteam.reservations.application.port.in.CreateReservationUseCase;
import com.edteam.reservations.application.port.in.GetReservationUseCase;
import com.edteam.reservations.application.port.in.ListReservationsUseCase;
import com.edteam.reservations.application.port.in.ModifyReservationUseCase;
import com.edteam.reservations.application.query.ResultPage;
import com.edteam.reservations.domain.access.ActorRole;
import com.edteam.reservations.domain.access.ReservationAccessDeniedException;
import com.edteam.reservations.domain.model.ReservationId;
import com.edteam.reservations.infrastructure.adapter.in.rest.mapper.ReservationRestMapper;
import com.edteam.reservations.infrastructure.security.SecurityConfiguration;
import com.edteam.reservations.support.TestFixtures;
import com.edteam.reservations.support.WebSliceConfiguration;
import com.edteam.reservations.support.WithMockActor;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Lo que tiene que <b>fallar</b>.
 *
 * <p>{@code ReservationControllerTest} prueba el contrato con el camino
 * autorizado; éste prueba el otro: sin token, con el token de otro, y por
 * encima de la cuota. Es la mitad que faltaba, y es la que importa después de
 * una remediación de seguridad — que el camino feliz siga andando no dice nada
 * sobre si el acceso indebido está cerrado.
 *
 * <p>La cuota se configura ridículamente baja para poder agotarla en tres
 * pedidos sin esperar.
 */
// La cuota se apaga acá y se prueba en ReservationQuotaTest, con su propio
// contexto: el contador vive en el filtro, que es un bean del contexto, así
// que compartirlo entre los tests de esta clase haría que el resultado
// dependa del orden de ejecución.
@WebMvcTest(value = ReservationController.class, properties = "reservations.security.rate-limit.enabled=false")
@Import({
    ReservationRestMapper.class,
    SecurityConfiguration.class,
    WebSliceConfiguration.class,
    TestVersionCacheConfiguration.class
})
@DisplayName("Seguridad del borde HTTP")
class ReservationSecurityTest {

    private static final String BODY = """
            {
              "itinerary": {
                "price": "1250.50",
                "currency": "USD",
                "segments": [
                  {
                    "originAirportCode": "EZE",
                    "destinationAirportCode": "SCL",
                    "airline": "AEROLINEAS ARGENTINAS",
                    "departureAt": "2026-10-21T12:00:00Z"
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
            """;

    @Autowired
    private MockMvc mockMvc;

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

    // ------------------------------------------------------------------
    // Sin credencial
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Sin token")
    @WithAnonymousUser
    class Anonymous {

        @Test
        @DisplayName("las cinco operaciones responden 401 y ninguna llega al caso de uso")
        void everyOperationRequiresAToken() throws Exception {
            mockMvc.perform(get("/v1/reservations/10")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/v1/reservations")).andExpect(status().isUnauthorized());
            mockMvc.perform(post("/v1/reservations")
                            .header(
                                    ReservationController.IDEMPOTENCY_KEY_HEADER,
                                    UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(put("/v1/reservations/10")
                            .header(HttpHeaders.IF_MATCH, "\"0\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(delete("/v1/reservations/10").header(HttpHeaders.IF_MATCH, "\"0\""))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(
                    createReservation, getReservation, listReservations, modifyReservation, cancelReservation);
        }

        @Test
        @DisplayName("el 401 usa el mismo cuerpo de error que el resto de la API")
        void unauthorizedUsesProblemJson() throws Exception {
            mockMvc.perform(get("/v1/reservations/10"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                    .andExpect(jsonPath("$.instance").value("/v1/reservations/10"))
                    .andExpect(header().string(
                                    HttpHeaders.WWW_AUTHENTICATE, org.hamcrest.Matchers.containsString("Bearer")));
        }

        @Test
        @DisplayName("el detalle no dice por qué falló: sería una guía para quien prueba tokens")
        void doesNotExplainWhy() throws Exception {
            mockMvc.perform(get("/v1/reservations/10").header(HttpHeaders.AUTHORIZATION, "Bearer no-es-un-jwt"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("El pedido requiere un token Bearer válido."));
        }

        @Test
        @DisplayName("una ruta que no existe tampoco se puede explorar sin token")
        void unknownRoutesAreClosedToo() throws Exception {
            mockMvc.perform(get("/v1/internal/whatever")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("el contrato y la UI no exigen token: exigirlo los volvería inusables")
        void theContractStaysReachable() throws Exception {
            // Un 401 acá llegaría antes de que exista la pantalla donde
            // apretar "Authorize", y la UI busca el documento por XHR sin
            // credencial. El control de T-08 es el interruptor que los apaga
            // en producción, no una regla que además los rompe.
            //
            // springdoc no está en este slice, así que el mapping no existe:
            // lo que se verifica es que la cadena permita la ruta —404 y no
            // 401—, igual que con las sondas.
            mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
            mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("la sonda de salud sí responde: la consulta el orquestador y no dice nada")
        void healthProbesStayOpen() throws Exception {
            // Sin actuator en el slice el mapping no existe, pero la cadena no
            // lo rechaza: la respuesta es 404 y no 401, que es lo que se
            // verifica —que la regla permite la ruta—.
            mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
        }
    }

    // ------------------------------------------------------------------
    // Con el token de otro
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Con el token de otro usuario")
    @WithMockActor(email = TestFixtures.OTHER_USER_EMAIL, firstName = "Bruno", lastName = "Díaz")
    class Stranger {

        @Test
        @DisplayName("la reserva ajena responde 404, indistinguible de una inexistente")
        void aForeignReservationLooksMissing() throws Exception {
            // El caso de uso ya decidió: devuelve el mismo error que si no
            // existiera. Lo que se verifica acá es que el adaptador no lo
            // convierta en otra cosa —un 403 acá reabriría la enumeración—.
            when(getReservation.get(any())).thenThrow(new ReservationNotFoundException(ReservationId.of(10L)));

            mockMvc.perform(get("/v1/reservations/10"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        }

        @Test
        @DisplayName("el caso de uso recibe la identidad real, no la del dueño de la reserva")
        void theUseCaseSeesTheRealCaller() throws Exception {
            when(getReservation.get(any())).thenReturn(TestFixtures.storedReservation(0L));

            mockMvc.perform(get("/v1/reservations/10")).andExpect(status().isOk());

            var query =
                    org.mockito.ArgumentCaptor
                            .<com.edteam.reservations.application.port.in.GetReservationQuery>captor();
            verify(getReservation).get(query.capture());
            org.assertj.core.api.Assertions.assertThat(
                            query.getValue().actor().email().value())
                    .isEqualTo(TestFixtures.OTHER_USER_EMAIL);
        }

        @Test
        @DisplayName("pedir el listado de otro usuario responde 403 y no filtra su contenido")
        void listingSomeoneElseIsForbidden() throws Exception {
            when(listReservations.list(any())).thenThrow(new ReservationAccessDeniedException("no corresponde"));

            mockMvc.perform(get("/v1/reservations").param("userId", TestFixtures.USER_EMAIL))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    // El detalle es fijo: no refleja el email del solicitante
                    // ni el pedido.
                    .andExpect(
                            jsonPath("$.detail").value("El solicitante no puede consultar reservas de otro usuario."));
        }
    }

    // ------------------------------------------------------------------
    // Backoffice
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Con rol de backoffice")
    @WithMockActor(
            email = "soporte@edteam.example",
            firstName = "Soporte",
            lastName = "Reservas",
            roles = ActorRole.BACKOFFICE)
    class Backoffice {

        @Test
        @DisplayName("el rol llega al caso de uso: la decisión la toma el dominio, no el filtro")
        void theRoleReachesTheUseCase() throws Exception {
            when(listReservations.list(any())).thenReturn(ResultPage.empty(0, 20));

            mockMvc.perform(get("/v1/reservations").param("userId", TestFixtures.USER_EMAIL))
                    .andExpect(status().isOk());

            var query =
                    org.mockito.ArgumentCaptor
                            .<com.edteam.reservations.application.port.in.ListReservationsQuery>captor();
            verify(listReservations).list(query.capture());
            org.assertj.core.api.Assertions.assertThat(query.getValue().actor().actsOnBehalfOfOthers())
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------
    // Headers de seguridad
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Headers de la respuesta")
    @WithMockActor
    class SecurityHeaders {

        @Test
        @DisplayName("declara HSTS, nosniff y deny de framing")
        void emitsSecurityHeaders() throws Exception {
            when(getReservation.get(any())).thenReturn(TestFixtures.storedReservation(0L));

            mockMvc.perform(get("/v1/reservations/10").secure(true))
                    .andExpect(status().isOk())
                    .andExpect(header().string(
                                    "Strict-Transport-Security",
                                    org.hamcrest.Matchers.containsString("max-age=31536000")))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"));
        }

        @Test
        @DisplayName("la respuesta autenticada sigue siendo no-store")
        void authenticatedResponsesAreNotStored() throws Exception {
            when(getReservation.get(any())).thenReturn(TestFixtures.storedReservation(0L));

            mockMvc.perform(get("/v1/reservations/10"))
                    .andExpect(header().string(
                                    HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")));
        }

        @Test
        @DisplayName("devuelve un correlation id para poder rastrear el pedido")
        void emitsACorrelationId() throws Exception {
            when(getReservation.get(any())).thenReturn(TestFixtures.storedReservation(0L));

            mockMvc.perform(get("/v1/reservations/10")).andExpect(header().exists("X-Correlation-Id"));
        }
    }
}
