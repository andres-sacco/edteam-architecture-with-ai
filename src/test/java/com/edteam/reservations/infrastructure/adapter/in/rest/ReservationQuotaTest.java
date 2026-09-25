package com.edteam.reservations.infrastructure.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edteam.reservations.application.port.in.CancelReservationUseCase;
import com.edteam.reservations.application.port.in.CreateReservationUseCase;
import com.edteam.reservations.application.port.in.GetReservationUseCase;
import com.edteam.reservations.application.port.in.ListReservationsUseCase;
import com.edteam.reservations.application.port.in.ModifyReservationUseCase;
import com.edteam.reservations.infrastructure.adapter.in.rest.mapper.ReservationRestMapper;
import com.edteam.reservations.infrastructure.security.SecurityConfiguration;
import com.edteam.reservations.support.TestFixtures;
import com.edteam.reservations.support.WebSliceConfiguration;
import com.edteam.reservations.support.WithMockActor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * La cuota de pedidos, enchufada en la cadena real.
 *
 * <p>El comportamiento del contador ya lo prueba {@code RateLimitFilterTest}
 * con el reloj en la mano. Lo que falta verificar acá es lo que sólo existe
 * cuando el filtro está en la cadena: que el rechazo salga con el mismo cuerpo
 * de error que el resto de la API y que ocurra <b>antes</b> del caso de uso, o
 * sea sin gastar la consulta y la transacción que se estaban tratando de
 * proteger.
 *
 * <p>Clase aparte y con su propio contexto: el contador es estado del bean, y
 * compartirlo con los demás tests de seguridad haría que el resultado dependa
 * del orden de ejecución.
 */
@WebMvcTest(
        value = ReservationController.class,
        properties = {
            "reservations.security.rate-limit.enabled=true",
            "reservations.security.rate-limit.reads=3",
            // Una hora: el test tiene que poder agotar la cuota sin que la ventana
            // se dé vuelta en el medio.
            "reservations.security.rate-limit.window=1h"
        })
@Import({
    ReservationRestMapper.class,
    SecurityConfiguration.class,
    WebSliceConfiguration.class,
    TestVersionCacheConfiguration.class
})
@WithMockActor
@DisplayName("Cuota de pedidos en el borde")
class ReservationQuotaTest {

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

    @Test
    @DisplayName("superada la cuota se responde 429 sin llegar al caso de uso")
    void rejectsTooManyReads() throws Exception {
        when(getReservation.get(any())).thenReturn(TestFixtures.storedReservation(0L));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/v1/reservations/10")).andExpect(status().isOk());
        }

        mockMvc.perform(get("/v1/reservations/10"))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.instance").value("/v1/reservations/10"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));

        // Exactamente tres: el cuarto se cortó en el filtro, antes de gastar
        // una consulta, una conexión del pool y una transacción.
        verify(getReservation, times(3)).get(any());
    }
}
