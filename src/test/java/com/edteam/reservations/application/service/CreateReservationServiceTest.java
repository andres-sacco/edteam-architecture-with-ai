package com.edteam.reservations.application.service;

import com.edteam.reservations.application.exception.DuplicateReservationException;
import com.edteam.reservations.application.exception.UnknownAirportException;
import com.edteam.reservations.application.port.in.CreateReservationCommand;
import com.edteam.reservations.application.port.in.ItineraryData;
import com.edteam.reservations.application.port.in.PassengerData;
import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.domain.event.DomainEvent;
import com.edteam.reservations.domain.event.ReservationCreated;
import com.edteam.reservations.domain.exception.InvalidReservationException;
import com.edteam.reservations.domain.exception.ItineraryAlreadyDepartedException;
import com.edteam.reservations.domain.model.AirportCode;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationId;
import com.edteam.reservations.domain.model.ReservationStatus;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreateReservationService")
class CreateReservationServiceTest {

    @Mock
    private ReservationRepositoryPort reservationRepository;

    @Mock
    private AirportCatalogPort airportCatalog;

    @Mock
    private EventOutboxPort eventOutbox;

    private CreateReservationService service;

    @BeforeEach
    void setUp() {
        service = new CreateReservationService(
                reservationRepository,
                new ItineraryAssembler(),
                new AirportExistenceValidator(airportCatalog),
                eventOutbox,
                TestFixtures.fixedClock());

        // Camino feliz por defecto; cada test lo sobreescribe si necesita otro.
        // Se declara lenient porque varios tests fallan antes de llegar a usarlo,
        // que es justamente lo que verifican.
        lenient().when(airportCatalog.exists(any(AirportCode.class))).thenReturn(true);
        lenient().when(reservationRepository.findByIdempotencyKey(TestFixtures.IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        lenient().when(reservationRepository.save(any(Reservation.class)))
                .thenAnswer(invocation -> invocation.<Reservation>getArgument(0)
                        .withId(TestFixtures.RESERVATION_ID));
    }

    @Test
    @DisplayName("crea la reserva pendiente a partir del comando")
    void createsPendingReservation() {
        Reservation created = service.create(TestFixtures.createCommand());

        assertThat(created.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(created.requireId()).isEqualTo(TestFixtures.RESERVATION_ID);
        assertThat(created.userId()).isEqualTo(TestFixtures.USER_ID);
        assertThat(created.idempotencyKey()).isEqualTo(TestFixtures.IDEMPOTENCY_KEY);
        assertThat(created.itinerary().origin()).isEqualTo(TestFixtures.EZE);
        assertThat(created.itinerary().destination()).isEqualTo(TestFixtures.SCL);
        assertThat(created.passengers()).hasSize(1);
        // El instante viene del Clock inyectado, no de Instant.now().
        assertThat(created.createdAt()).isEqualTo(TestFixtures.NOW);
    }

    @Test
    @DisplayName("persiste la reserva sin id y encola el evento con el id ya asignado")
    void persistsAndEnqueuesEvent() {
        service.create(TestFixtures.createCommand());

        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(saved.capture());
        assertThat(saved.getValue().id()).isEmpty();
        assertThat(saved.getValue().version()).isZero();

        ArgumentCaptor<Collection<DomainEvent>> events = ArgumentCaptor.captor();
        verify(eventOutbox).enqueue(events.capture());
        assertThat(events.getValue())
                .singleElement()
                .isInstanceOfSatisfying(ReservationCreated.class, event -> {
                    assertThat(event.eventType()).isEqualTo(ReservationCreated.TYPE);
                    assertThat(event.reservationId()).isEqualTo(TestFixtures.RESERVATION_ID);
                    assertThat(event.userId()).isEqualTo(TestFixtures.USER_ID);
                    assertThat(event.passengerCount()).isEqualTo(1);
                    assertThat(event.itinerary().origin()).isEqualTo(TestFixtures.EZE);
                    assertThat(event.occurredAt()).isEqualTo(TestFixtures.NOW);
                });
    }

    @Test
    @DisplayName("ante un reintento con la misma clave devuelve la reserva existente y no vuelve a notificar")
    void isIdempotentOnRetry() {
        Reservation existente = TestFixtures.storedReservation(2L);
        when(reservationRepository.findByIdempotencyKey(TestFixtures.IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existente));

        Reservation result = service.create(TestFixtures.createCommand());

        assertThat(result).isSameAs(existente);
        verify(reservationRepository, never()).save(any());
        verify(eventOutbox, never()).enqueue(anyCollection());
    }

    @Test
    @DisplayName("si pierde la carrera por la clave de idempotencia, propaga el duplicado sin notificar")
    void propagatesDuplicateWhenLosingTheRace() {
        when(reservationRepository.save(any(Reservation.class)))
                .thenThrow(new DuplicateReservationException(TestFixtures.IDEMPOTENCY_KEY, new RuntimeException()));

        assertThatThrownBy(() -> service.create(TestFixtures.createCommand()))
                .isInstanceOf(DuplicateReservationException.class);

        verify(eventOutbox, never()).enqueue(anyCollection());
    }

    @Test
    @DisplayName("valida contra el maestro todos los aeropuertos del itinerario")
    void validatesEveryAirport() {
        CreateReservationCommand conEscala = new CreateReservationCommand(
                TestFixtures.USER_ID.value(), TestFixtures.IDEMPOTENCY_KEY.value().toString(),
                TestFixtures.connectingItineraryData(), TestFixtures.passengerData());

        service.create(conEscala);

        verify(airportCatalog).exists(TestFixtures.EZE);
        verify(airportCatalog).exists(TestFixtures.SCL);
        verify(airportCatalog).exists(TestFixtures.MAD);
    }

    @Test
    @DisplayName("si un aeropuerto no existe, no persiste ni notifica")
    void rejectsUnknownAirport() {
        when(airportCatalog.exists(TestFixtures.SCL)).thenReturn(false);

        assertThatThrownBy(() -> service.create(TestFixtures.createCommand()))
                .isInstanceOf(UnknownAirportException.class)
                .hasMessageContaining("SCL");

        verify(reservationRepository, never()).save(any());
        verifyNoInteractions(eventOutbox);
    }

    @Test
    @DisplayName("rechaza un itinerario que ya salió, sin escribir")
    void rejectsDepartedItinerary() {
        ItineraryData pasado = new ItineraryData(new BigDecimal("100.00"), "USD",
                List.of(TestFixtures.segmentData(TestFixtures.EZE, TestFixtures.SCL,
                        TestFixtures.NOW.minus(Duration.ofDays(1)))));
        CreateReservationCommand command = new CreateReservationCommand(
                TestFixtures.USER_ID.value(), TestFixtures.IDEMPOTENCY_KEY.value().toString(),
                pasado, TestFixtures.passengerData());

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(ItineraryAlreadyDepartedException.class);

        verify(reservationRepository, never()).save(any());
        verify(eventOutbox, never()).enqueue(anyCollection());
    }

    @Test
    @DisplayName("rechaza una reserva sin pasajeros")
    void rejectsReservationWithoutPassengers() {
        CreateReservationCommand command = new CreateReservationCommand(
                TestFixtures.USER_ID.value(), TestFixtures.IDEMPOTENCY_KEY.value().toString(),
                TestFixtures.itineraryData(), List.of());

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(InvalidReservationException.class)
                .hasMessageContaining("al menos un pasajero");

        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("rechaza el mismo pasajero dos veces en la reserva")
    void rejectsDuplicatePassengers() {
        CreateReservationCommand command = new CreateReservationCommand(
                TestFixtures.USER_ID.value(), TestFixtures.IDEMPOTENCY_KEY.value().toString(),
                TestFixtures.itineraryData(),
                List.of(new PassengerData("Ana", "Pérez", LocalDate.of(1990, 5, 20), "30123456"),
                        new PassengerData("Ana", "Pérez", LocalDate.of(1990, 5, 20), "30123456")));

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(InvalidReservationException.class)
                .hasMessageContaining("más de una vez");
    }

    @Test
    @DisplayName("rechaza una clave de idempotencia que no es un UUID")
    void rejectsMalformedIdempotencyKey() {
        CreateReservationCommand command = new CreateReservationCommand(
                TestFixtures.USER_ID.value(), "no-es-un-uuid",
                TestFixtures.itineraryData(), TestFixtures.passengerData());

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no es un UUID válido");

        verifyNoInteractions(reservationRepository, eventOutbox);
    }

    @Test
    @DisplayName("rechaza un comando nulo")
    void rejectsNullCommand() {
        assertThatThrownBy(() -> service.create(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("exige todas sus dependencias")
    void requiresDependencies() {
        assertThatThrownBy(() -> new CreateReservationService(
                null, new ItineraryAssembler(), new AirportExistenceValidator(airportCatalog),
                eventOutbox, TestFixtures.fixedClock()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CreateReservationService(
                reservationRepository, new ItineraryAssembler(),
                new AirportExistenceValidator(airportCatalog), eventOutbox, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("la reserva creada arranca sin ReservationId hasta que la persistencia lo asigna")
    void idComesFromPersistence() {
        when(reservationRepository.save(any(Reservation.class)))
                .thenAnswer(invocation -> invocation.<Reservation>getArgument(0).withId(ReservationId.of(99L)));

        assertThat(service.create(TestFixtures.createCommand()).requireId().value()).isEqualTo(99L);
    }
}
