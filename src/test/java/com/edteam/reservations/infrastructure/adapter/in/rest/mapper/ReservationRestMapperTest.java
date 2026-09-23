package com.edteam.reservations.infrastructure.adapter.in.rest.mapper;

import com.edteam.reservations.application.port.in.CreateReservationCommand;
import com.edteam.reservations.application.query.ReservationSearchCriteria;
import com.edteam.reservations.application.query.ReservationSortBy;
import com.edteam.reservations.application.query.ResultPage;
import com.edteam.reservations.application.query.SortDirection;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationStatus;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.CreateReservationRequest;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ItineraryRequest;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ListReservationsParams;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.PassengerRequest;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ReservationPageResponse;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ReservationResponse;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ReservationStatusDto;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.SegmentRequest;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReservationRestMapper")
class ReservationRestMapperTest {

    private static final UUID KEY = UUID.fromString("3f1a9c7e-0f6e-4a39-9d2c-8b5f0c1e7a44");

    private final ReservationRestMapper mapper = new ReservationRestMapper();

    @Test
    @DisplayName("arma el comando con la clave de idempotencia del header, no del cuerpo")
    void buildsCreateCommand() {
        CreateReservationRequest request = new CreateReservationRequest(
                new ItineraryRequest("1250.50", "USD", List.of(
                        new SegmentRequest("EZE", "SCL", "AEROLINEAS ARGENTINAS", TestFixtures.DEPARTURE))),
                List.of(new PassengerRequest("Ana", "Pérez", LocalDate.of(1990, 5, 20), "30123456")));

        CreateReservationCommand command = mapper.toCommand(request, KEY, TestFixtures.owner());

        // El comprador sale del actor y no del cuerpo: el pedido ya no tiene
        // forma de nombrar a nadie.
        assertThat(command.actor().email().value()).isEqualTo(TestFixtures.USER_EMAIL);
        assertThat(command.actor().firstName()).isEqualTo("Ana");
        assertThat(command.actor().lastName()).isEqualTo("Pérez");
        assertThat(command.idempotencyKey()).isEqualTo(KEY.toString());
        assertThat(command.itinerary().price()).isEqualByComparingTo(new BigDecimal("1250.50"));
        assertThat(command.itinerary().currency()).isEqualTo("USD");
        assertThat(command.itinerary().segments())
                .singleElement()
                .satisfies(segment -> {
                    assertThat(segment.originAirportCode()).isEqualTo("EZE");
                    assertThat(segment.destinationAirportCode()).isEqualTo("SCL");
                    assertThat(segment.departureAt()).isEqualTo(TestFixtures.DEPARTURE);
                });
        assertThat(command.passengers()).singleElement()
                .satisfies(passenger -> assertThat(passenger.documentNumber()).isEqualTo("30123456"));
    }

    @Test
    @DisplayName("el precio viaja como string y se convierte sin perder decimales")
    void keepsDecimalPrecision() {
        CreateReservationRequest request = new CreateReservationRequest(
                new ItineraryRequest("0.10", "usd".toUpperCase(java.util.Locale.ROOT), List.of(
                        new SegmentRequest("EZE", "SCL", "AR", TestFixtures.DEPARTURE))),
                List.of(new PassengerRequest("Ana", "Pérez", LocalDate.of(1990, 5, 20), null)));

        assertThat(mapper.toCommand(request, KEY, TestFixtures.owner()).itinerary().price())
                .isEqualByComparingTo(new BigDecimal("0.10"))
                .hasToString("0.10");
    }

    @Test
    @DisplayName("la respuesta no expone la versión ni la clave de idempotencia")
    void responseHidesConcurrencyAndIdempotencyDetails() {
        ReservationResponse response = mapper.toResponse(TestFixtures.storedReservation(7L));

        assertThat(response.id()).isEqualTo("10");
        // La API identifica al usuario por email: el id de la base no se expone.
        assertThat(response.userId()).isEqualTo(TestFixtures.USER_EMAIL);
        assertThat(response.status()).isEqualTo(ReservationStatusDto.PENDING);
        assertThat(response.cancelledAt()).isNull();
        // Ningún campo del DTO habla de versión ni de clave: si alguien los
        // agregara, este assert sobre la forma del record lo delata.
        assertThat(ReservationResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("id", "status", "userId", "itinerary", "passengers",
                        "createdAt", "updatedAt", "cancelledAt");
    }

    @Test
    @DisplayName("numera los tramos por su posición en el itinerario, empezando en 1")
    void numbersSegments() {
        Reservation reservation = TestFixtures.storedReservation(0L);

        assertThat(mapper.toResponse(reservation).itinerary().segments())
                .extracting("position")
                .containsExactly(1);
    }

    @Test
    @DisplayName("una reserva cancelada informa la fecha de cancelación como su última actualización")
    void derivesCancellationInstant() {
        Reservation cancelled = TestFixtures.storedReservation(2L, ReservationStatus.CANCELLED);

        assertThat(mapper.toResponse(cancelled).cancelledAt()).isEqualTo(cancelled.updatedAt());
    }

    @Test
    @DisplayName("la página lleva los metadatos calculados, no sólo los elementos")
    void mapsPageMetadata() {
        ResultPage<Reservation> page = new ResultPage<>(
                List.of(TestFixtures.storedReservation(0L)), 2, 5, 37L);

        ReservationPageResponse response = mapper.toResponse(page);

        assertThat(response.items()).hasSize(1);
        assertThat(response.page().number()).isEqualTo(2);
        assertThat(response.page().size()).isEqualTo(5);
        assertThat(response.page().totalElements()).isEqualTo(37L);
        assertThat(response.page().totalPages()).isEqualTo(8);
    }

    @Test
    @DisplayName("traduce el orden del contrato al vocabulario de la aplicación")
    void translatesSort() {
        ReservationSearchCriteria byDeparture = mapper.toCriteria(new ListReservationsParams(
                null, null, null, null, null, null, "firstDepartureAt,asc"));

        assertThat(byDeparture.sortBy()).isEqualTo(ReservationSortBy.FIRST_DEPARTURE_AT);
        assertThat(byDeparture.direction()).isEqualTo(SortDirection.ASC);

        ReservationSearchCriteria byDefault = mapper.toCriteria(ListReservationsParams.defaults());

        assertThat(byDefault.sortBy()).isEqualTo(ReservationSortBy.CREATED_AT);
        assertThat(byDefault.direction()).isEqualTo(SortDirection.DESC);
        assertThat(byDefault.page()).isZero();
        assertThat(byDefault.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("los filtros ausentes no restringen la búsqueda")
    void emptyFiltersDoNotRestrict() {
        ReservationSearchCriteria criteria = mapper.toCriteria(ListReservationsParams.defaults());

        assertThat(criteria.userEmail()).isEmpty();
        assertThat(criteria.statuses()).isEmpty();
        assertThat(criteria.departureFrom()).isEmpty();
        assertThat(criteria.departureTo()).isEmpty();
        assertThat(criteria.filtersByStatus()).isFalse();
    }

    @Test
    @DisplayName("traduce los estados del contrato a los del dominio")
    void translatesStatuses() {
        ReservationSearchCriteria criteria = mapper.toCriteria(new ListReservationsParams(
                "ana.perez@example.com", List.of(ReservationStatusDto.PENDING, ReservationStatusDto.CANCELLED),
                null, null, 1, 50, null));

        assertThat(criteria.userEmail()).map(com.edteam.reservations.domain.model.Email::value)
                .contains("ana.perez@example.com");
        assertThat(criteria.statuses())
                .containsExactlyInAnyOrder(ReservationStatus.PENDING, ReservationStatus.CANCELLED);
        assertThat(criteria.offset()).isEqualTo(50);
    }
}
