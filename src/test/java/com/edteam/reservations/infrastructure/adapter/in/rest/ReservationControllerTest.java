package com.edteam.reservations.infrastructure.adapter.in.rest;

import com.edteam.reservations.application.exception.ConcurrentUpdateException;
import com.edteam.reservations.application.exception.DuplicateReservationException;
import com.edteam.reservations.application.exception.ReservationNotFoundException;
import com.edteam.reservations.application.exception.UnknownAirportException;
import com.edteam.reservations.application.exception.UnknownUserException;
import com.edteam.reservations.application.port.in.CancelReservationCommand;
import com.edteam.reservations.application.port.in.CancelReservationUseCase;
import com.edteam.reservations.application.port.in.CreateReservationCommand;
import com.edteam.reservations.application.port.in.CreateReservationResult;
import com.edteam.reservations.application.port.in.CreateReservationUseCase;
import com.edteam.reservations.application.port.in.GetReservationUseCase;
import com.edteam.reservations.application.port.in.ListReservationsUseCase;
import com.edteam.reservations.application.port.in.ModifyReservationCommand;
import com.edteam.reservations.application.port.in.ModifyReservationUseCase;
import com.edteam.reservations.application.query.ReservationSearchCriteria;
import com.edteam.reservations.application.query.ReservationSortBy;
import com.edteam.reservations.application.query.ResultPage;
import com.edteam.reservations.application.query.SortDirection;
import com.edteam.reservations.domain.exception.InvalidItineraryException;
import com.edteam.reservations.domain.exception.ItineraryAlreadyDepartedException;
import com.edteam.reservations.domain.exception.ReservationAlreadyCancelledException;
import com.edteam.reservations.domain.exception.ReservationNotModifiableException;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationId;
import com.edteam.reservations.domain.model.ReservationStatus;
import com.edteam.reservations.infrastructure.adapter.in.rest.mapper.ReservationRestMapper;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

/**
 * Slice del adaptador REST: sólo la capa web, con los puertos de entrada
 * mockeados.
 *
 * <p>Es el test que verifica el contrato HTTP y nada más: qué código de estado
 * sale, qué headers, qué forma tiene el JSON y qué comando recibe el caso de
 * uso. Que el caso de uso haga lo correcto ya lo prueban sus propios tests, y
 * que todo encaje contra una base real lo prueba {@code ReservationApiIT}.
 */
@WebMvcTest(ReservationController.class)
@Import(ReservationRestMapper.class)
@DisplayName("API de reservas")
class ReservationControllerTest {

    private static final String IDEMPOTENCY_KEY = "3f1a9c7e-0f6e-4a39-9d2c-8b5f0c1e7a44";

    private static final String CREATE_BODY = """
            {
              "user": {
                "email": "ana.perez@example.com",
                "firstName": "Ana",
                "lastName": "Pérez"
              },
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

    private static final String UPDATE_BODY = """
            {
              "itinerary": {
                "price": "1980.00",
                "currency": "USD",
                "segments": [
                  {
                    "originAirportCode": "EZE",
                    "destinationAirportCode": "SCL",
                    "airline": "AEROLINEAS ARGENTINAS",
                    "departureAt": "2026-10-21T12:00:00Z"
                  },
                  {
                    "originAirportCode": "SCL",
                    "destinationAirportCode": "MAD",
                    "airline": "IBERIA",
                    "departureAt": "2026-10-21T18:00:00Z"
                  }
                ]
              }
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

    @Nested
    @DisplayName("POST /v1/reservations")
    class Create {

        @Test
        @DisplayName("responde 201 con Location y ETag, y traduce el cuerpo al comando")
        void createsReservation() throws Exception {
            Reservation stored = TestFixtures.storedReservation(0L);
            when(createReservation.create(any())).thenReturn(CreateReservationResult.created(stored));

            mockMvc.perform(post("/v1/reservations")
                            .header(ReservationController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/v1/reservations/10"))
                    .andExpect(header().string("ETag", "\"0\""))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.id").value("10"))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.userId").value("ana.perez@example.com"))
                    .andExpect(jsonPath("$.itinerary.origin").value("EZE"))
                    .andExpect(jsonPath("$.itinerary.destination").value("SCL"))
                    .andExpect(jsonPath("$.itinerary.price.amount").value("1250.50"))
                    .andExpect(jsonPath("$.itinerary.price.currency").value("USD"))
                    .andExpect(jsonPath("$.itinerary.segments[0].position").value(1))
                    .andExpect(jsonPath("$.passengers[0].firstName").value("Ana"))
                    .andExpect(jsonPath("$.cancelledAt").doesNotExist());

            ArgumentCaptor<CreateReservationCommand> command = ArgumentCaptor.captor();
            verify(createReservation).create(command.capture());
            assertThat(command.getValue().user().email()).isEqualTo("ana.perez@example.com");
            assertThat(command.getValue().user().firstName()).isEqualTo("Ana");
            assertThat(command.getValue().idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
            assertThat(command.getValue().itinerary().segments()).hasSize(1);
            assertThat(command.getValue().passengers()).hasSize(1);
        }

        @Test
        @DisplayName("el reintento con la misma clave responde 200 y no 201")
        void repliesOkOnRetry() throws Exception {
            when(createReservation.create(any()))
                    .thenReturn(CreateReservationResult.alreadyExisted(TestFixtures.storedReservation(2L)));

            mockMvc.perform(post("/v1/reservations")
                            .header(ReservationController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(header().string("ETag", "\"2\""))
                    .andExpect(jsonPath("$.id").value("10"));
        }

        @Test
        @DisplayName("si pierde la carrera por la clave, reintenta una vez y devuelve la reserva ganadora")
        void retriesOnceWhenLosingTheIdempotencyRace() throws Exception {
            when(createReservation.create(any()))
                    .thenThrow(new DuplicateReservationException(TestFixtures.IDEMPOTENCY_KEY, null))
                    .thenReturn(CreateReservationResult.alreadyExisted(TestFixtures.storedReservation(0L)));

            mockMvc.perform(post("/v1/reservations")
                            .header(ReservationController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("10"));

            verify(createReservation, times(2)).create(any());
        }

        @Test
        @DisplayName("si el reintento también pierde, responde 409 en lugar de insistir")
        void repliesConflictWhenTheRaceCannotBeResolved() throws Exception {
            when(createReservation.create(any()))
                    .thenThrow(new DuplicateReservationException(TestFixtures.IDEMPOTENCY_KEY, null));

            mockMvc.perform(post("/v1/reservations")
                            .header(ReservationController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

            verify(createReservation, times(2)).create(any());
        }

        @Test
        @DisplayName("sin el header de idempotencia responde 400 y no llega al caso de uso")
        void rejectsMissingIdempotencyKey() throws Exception {
            mockMvc.perform(post("/v1/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").exists());

            verifyNoInteractions(createReservation);
        }

        @Test
        @DisplayName("con una clave de idempotencia que no es UUID responde 400")
        void rejectsMalformedIdempotencyKey() throws Exception {
            mockMvc.perform(post("/v1/reservations")
                            .header(ReservationController.IDEMPOTENCY_KEY_HEADER, "no-es-un-uuid")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(createReservation);
        }

        @Test
        @DisplayName("un cuerpo inválido responde 400 con el detalle campo por campo")
        void rejectsInvalidBody() throws Exception {
            String invalid = """
                    {
                      "user": {"email": "no-es-un-email", "firstName": "", "lastName": "Pérez"},
                      "itinerary": {
                        "price": "-5",
                        "currency": "dolares",
                        "segments": []
                      },
                      "passengers": []
                    }
                    """;

            mockMvc.perform(post("/v1/reservations")
                            .header(ReservationController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.instance").value("/v1/reservations"))
                    .andExpect(jsonPath("$.errors[*].field")
                            .value(org.hamcrest.Matchers.hasItems(
                                    "user.email", "user.firstName",
                                    "itinerary.price", "itinerary.currency", "itinerary.segments", "passengers")));

            verifyNoInteractions(createReservation);
        }

        @Test
        @DisplayName("un usuario inexistente es 400 y no 404: lo que está mal es un dato del cuerpo")
        void mapsUnknownUserToBadRequest() throws Exception {
            when(createReservation.create(any()))
                    .thenThrow(new UnknownUserException(TestFixtures.USER_ID, null));

            mockMvc.perform(post("/v1/reservations")
                            .header(ReservationController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        }

        @Test
        @DisplayName("un aeropuerto fuera del catálogo responde 400")
        void mapsUnknownAirportToBadRequest() throws Exception {
            when(createReservation.create(any()))
                    .thenThrow(new UnknownAirportException(List.of(TestFixtures.EZE)));

            mockMvc.perform(post("/v1/reservations")
                            .header(ReservationController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_AIRPORT"));
        }

        @Test
        @DisplayName("una regla del dominio responde 400 con su código")
        void mapsDomainRuleToBadRequest() throws Exception {
            when(createReservation.create(any()))
                    .thenThrow(new ItineraryAlreadyDepartedException("El primer tramo ya salió"));

            mockMvc.perform(post("/v1/reservations")
                            .header(ReservationController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ITINERARY_ALREADY_DEPARTED"));
        }
    }

    @Nested
    @DisplayName("GET /v1/reservations/{id}")
    class GetById {

        @Test
        @DisplayName("responde 200 con la reserva y su ETag")
        void returnsReservation() throws Exception {
            when(getReservation.getById(ReservationId.of(10L))).thenReturn(TestFixtures.storedReservation(3L));

            mockMvc.perform(get("/v1/reservations/10"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("ETag", "\"3\""))
                    .andExpect(jsonPath("$.id").value("10"))
                    .andExpect(jsonPath("$.passengers[0].documentNumber").value("30123456"))
                    .andExpect(jsonPath("$.createdAt").value("2026-10-01T12:00:00Z"));
        }

        @Test
        @DisplayName("una reserva cancelada informa la fecha de cancelación")
        void exposesCancellationInstant() throws Exception {
            when(getReservation.getById(any()))
                    .thenReturn(TestFixtures.storedReservation(4L, ReservationStatus.CANCELLED));

            mockMvc.perform(get("/v1/reservations/10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.cancelledAt").value("2026-10-01T12:00:00Z"));
        }

        @Test
        @DisplayName("si no existe responde 404")
        void returnsNotFound() throws Exception {
            when(getReservation.getById(any()))
                    .thenThrow(new ReservationNotFoundException(ReservationId.of(999L)));

            mockMvc.perform(get("/v1/reservations/999"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"))
                    .andExpect(jsonPath("$.instance").value("/v1/reservations/999"));
        }

        @Test
        @DisplayName("un id que no es numérico responde 400")
        void rejectsNonNumericId() throws Exception {
            mockMvc.perform(get("/v1/reservations/abc"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(getReservation);
        }
    }

    @Nested
    @DisplayName("GET /v1/reservations")
    class ListAll {

        @Test
        @DisplayName("sin parámetros aplica la primera página y el orden por defecto")
        void appliesDefaults() throws Exception {
            when(listReservations.list(any()))
                    .thenReturn(new ResultPage<>(List.of(TestFixtures.storedReservation(0L)), 0, 20, 1L));

            mockMvc.perform(get("/v1/reservations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(1)))
                    .andExpect(jsonPath("$.items[0].id").value("10"))
                    .andExpect(jsonPath("$.page.number").value(0))
                    .andExpect(jsonPath("$.page.size").value(20))
                    .andExpect(jsonPath("$.page.totalElements").value(1))
                    .andExpect(jsonPath("$.page.totalPages").value(1));

            ArgumentCaptor<ReservationSearchCriteria> criteria = ArgumentCaptor.captor();
            verify(listReservations).list(criteria.capture());
            assertThat(criteria.getValue().page()).isZero();
            assertThat(criteria.getValue().size()).isEqualTo(20);
            assertThat(criteria.getValue().sortBy()).isEqualTo(ReservationSortBy.CREATED_AT);
            assertThat(criteria.getValue().direction()).isEqualTo(SortDirection.DESC);
            assertThat(criteria.getValue().userEmail()).isEmpty();
            assertThat(criteria.getValue().statuses()).isEmpty();
        }

        @Test
        @DisplayName("traduce filtros, paginación y orden al criterio de búsqueda")
        void translatesFilters() throws Exception {
            when(listReservations.list(any())).thenReturn(ResultPage.empty(2, 5));

            mockMvc.perform(get("/v1/reservations")
                            .param("userId", "ana.perez@example.com")
                            .param("status", "PENDING", "CONFIRMED")
                            .param("departureFrom", "2026-10-01T00:00:00Z")
                            .param("departureTo", "2026-10-31T23:59:59Z")
                            .param("page", "2")
                            .param("size", "5")
                            .param("sort", "firstDepartureAt,asc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(0)))
                    .andExpect(jsonPath("$.page.totalElements").value(0));

            ArgumentCaptor<ReservationSearchCriteria> criteria = ArgumentCaptor.captor();
            verify(listReservations).list(criteria.capture());
            ReservationSearchCriteria value = criteria.getValue();
            assertThat(value.userEmail()).map(com.edteam.reservations.domain.model.Email::value)
                    .contains("ana.perez@example.com");
            assertThat(value.statuses())
                    .containsExactlyInAnyOrder(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);
            assertThat(value.departureFrom()).isPresent();
            assertThat(value.departureTo()).isPresent();
            assertThat(value.page()).isEqualTo(2);
            assertThat(value.size()).isEqualTo(5);
            assertThat(value.sortBy()).isEqualTo(ReservationSortBy.FIRST_DEPARTURE_AT);
            assertThat(value.direction()).isEqualTo(SortDirection.ASC);
        }

        @Test
        @DisplayName("un tamaño de página fuera de rango responde 400 sin llegar al caso de uso")
        void rejectsOversizedPage() throws Exception {
            mockMvc.perform(get("/v1/reservations").param("size", "500"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.errors[0].field").value("size"));

            verifyNoInteractions(listReservations);
        }

        @Test
        @DisplayName("un rango de fechas invertido responde 400")
        void rejectsInvertedDateRange() throws Exception {
            mockMvc.perform(get("/v1/reservations")
                            .param("departureFrom", "2026-12-01T00:00:00Z")
                            .param("departureTo", "2026-10-01T00:00:00Z"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            verifyNoInteractions(listReservations);
        }

        @Test
        @DisplayName("un criterio de orden desconocido responde 400")
        void rejectsUnknownSort() throws Exception {
            mockMvc.perform(get("/v1/reservations").param("sort", "precio,asc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("sort"));

            verifyNoInteractions(listReservations);
        }

        @Test
        @DisplayName("un estado desconocido responde 400")
        void rejectsUnknownStatus() throws Exception {
            mockMvc.perform(get("/v1/reservations").param("status", "PENDIENTE"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(listReservations);
        }
    }

    @Nested
    @DisplayName("PUT /v1/reservations/{id}")
    class Update {

        @Test
        @DisplayName("responde 200 y pasa la versión del If-Match al comando")
        void updatesItinerary() throws Exception {
            when(modifyReservation.modify(any())).thenReturn(TestFixtures.storedReservation(4L));

            mockMvc.perform(put("/v1/reservations/10")
                            .header("If-Match", "\"3\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(UPDATE_BODY))
                    .andExpect(status().isOk())
                    .andExpect(header().string("ETag", "\"4\""))
                    .andExpect(jsonPath("$.id").value("10"));

            ArgumentCaptor<ModifyReservationCommand> command = ArgumentCaptor.captor();
            verify(modifyReservation).modify(command.capture());
            assertThat(command.getValue().reservationId()).isEqualTo(10L);
            assertThat(command.getValue().expectedVersion()).isEqualTo(3L);
            assertThat(command.getValue().newItinerary().segments()).hasSize(2);
        }

        @Test
        @DisplayName("acepta el ETag en su forma débil, que es la que devuelven algunos proxies")
        void acceptsWeakETag() throws Exception {
            when(modifyReservation.modify(any())).thenReturn(TestFixtures.storedReservation(4L));

            mockMvc.perform(put("/v1/reservations/10")
                            .header("If-Match", "W/\"3\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(UPDATE_BODY))
                    .andExpect(status().isOk());

            ArgumentCaptor<ModifyReservationCommand> command = ArgumentCaptor.captor();
            verify(modifyReservation).modify(command.capture());
            assertThat(command.getValue().expectedVersion()).isEqualTo(3L);
        }

        @Test
        @DisplayName("sin If-Match responde 400: no hay con qué detectar una escritura concurrente")
        void rejectsMissingIfMatch() throws Exception {
            mockMvc.perform(put("/v1/reservations/10")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(UPDATE_BODY))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(modifyReservation);
        }

        @Test
        @DisplayName("con un If-Match que no es un ETag de esta API responde 400")
        void rejectsMalformedIfMatch() throws Exception {
            mockMvc.perform(put("/v1/reservations/10")
                            .header("If-Match", "*")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(UPDATE_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            verifyNoInteractions(modifyReservation);
        }

        @Test
        @DisplayName("con una versión vieja responde 409")
        void mapsConcurrentUpdateToConflict() throws Exception {
            when(modifyReservation.modify(any()))
                    .thenThrow(new ConcurrentUpdateException(ReservationId.of(10L), 3L, 5L));

            mockMvc.perform(put("/v1/reservations/10")
                            .header("If-Match", "\"3\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(UPDATE_BODY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ETag")));
        }

        @Test
        @DisplayName("sobre una reserva cancelada responde 409")
        void mapsNotModifiableToConflict() throws Exception {
            when(modifyReservation.modify(any()))
                    .thenThrow(new ReservationNotModifiableException("10", ReservationStatus.CANCELLED));

            mockMvc.perform(put("/v1/reservations/10")
                            .header("If-Match", "\"3\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(UPDATE_BODY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("RESERVATION_NOT_MODIFIABLE"));
        }

        @Test
        @DisplayName("un itinerario incoherente responde 400")
        void mapsInvalidItineraryToBadRequest() throws Exception {
            when(modifyReservation.modify(any()))
                    .thenThrow(new InvalidItineraryException("Los segmentos no se encadenan"));

            mockMvc.perform(put("/v1/reservations/10")
                            .header("If-Match", "\"3\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(UPDATE_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ITINERARY"));
        }
    }

    @Nested
    @DisplayName("DELETE /v1/reservations/{id}")
    class Cancel {

        @Test
        @DisplayName("responde 200 con la reserva cancelada, porque la baja es lógica")
        void cancelsReservation() throws Exception {
            when(cancelReservation.cancel(any()))
                    .thenReturn(TestFixtures.storedReservation(4L, ReservationStatus.CANCELLED));

            mockMvc.perform(delete("/v1/reservations/10").header("If-Match", "\"3\""))
                    .andExpect(status().isOk())
                    .andExpect(header().string("ETag", "\"4\""))
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.cancelledAt").exists());

            ArgumentCaptor<CancelReservationCommand> command = ArgumentCaptor.captor();
            verify(cancelReservation).cancel(command.capture());
            assertThat(command.getValue().reservationId()).isEqualTo(10L);
            assertThat(command.getValue().expectedVersion()).isEqualTo(3L);
        }

        @Test
        @DisplayName("si ya estaba cancelada responde 409")
        void mapsAlreadyCancelledToConflict() throws Exception {
            when(cancelReservation.cancel(any()))
                    .thenThrow(new ReservationAlreadyCancelledException("10"));

            mockMvc.perform(delete("/v1/reservations/10").header("If-Match", "\"3\""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("RESERVATION_ALREADY_CANCELLED"));
        }

        @Test
        @DisplayName("si no existe responde 404")
        void mapsNotFound() throws Exception {
            when(cancelReservation.cancel(any()))
                    .thenThrow(new ReservationNotFoundException(ReservationId.of(999L)));

            mockMvc.perform(delete("/v1/reservations/999").header("If-Match", "\"0\""))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        }

        @Test
        @DisplayName("sin If-Match responde 400")
        void rejectsMissingIfMatch() throws Exception {
            mockMvc.perform(delete("/v1/reservations/10"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(cancelReservation);
        }
    }

    @Nested
    @DisplayName("Errores del protocolo")
    class ProtocolErrors {

        @Test
        @DisplayName("un método no soportado también sale como ProblemDetail con código")
        void unsupportedMethod() throws Exception {
            mockMvc.perform(put("/v1/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(UPDATE_BODY))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("UNSUPPORTED_REQUEST"));
        }

        @Test
        @DisplayName("un JSON malformado responde 400 sin filtrar el error del parser")
        void malformedJson() throws Exception {
            mockMvc.perform(post("/v1/reservations")
                            .header(ReservationController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"user\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            verify(createReservation, never()).create(any());
        }
    }
}
