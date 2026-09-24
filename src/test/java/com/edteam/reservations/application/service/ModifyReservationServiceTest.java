package com.edteam.reservations.application.service;

import com.edteam.reservations.application.exception.ConcurrentUpdateException;
import com.edteam.reservations.application.exception.ReservationNotFoundException;
import com.edteam.reservations.application.exception.UnknownAirportException;
import com.edteam.reservations.application.port.in.ModifyReservationCommand;
import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.application.port.out.AuditTrailPort;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.domain.event.DomainEvent;
import com.edteam.reservations.domain.event.ReservationModified;
import com.edteam.reservations.domain.exception.ReservationNotModifiableException;
import com.edteam.reservations.domain.model.AirportCode;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationStatus;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ModifyReservationService")
class ModifyReservationServiceTest {

    @Mock
    private ReservationRepositoryPort reservationRepository;

    @Mock
    private AirportCatalogPort airportCatalog;

    @Mock
    private EventOutboxPort eventOutbox;

    @Mock
    private AuditTrailPort auditTrail;

    private ModifyReservationService service;

    @BeforeEach
    void setUp() {
        // Igual que en el alta: el caso de uso verifica precondiciones y llama
        // al catálogo fuera de la transacción; el colaborador transaccional
        // vuelve a leer, vuelve a verificar la versión —que es la verificación
        // que cuenta— y escribe.
        service = new ModifyReservationService(
                reservationRepository,
                new ItineraryAssembler(),
                new AirportExistenceValidator(airportCatalog),
                new ModifyReservationTransaction(reservationRepository, eventOutbox, auditTrail),
                auditTrail,
                TestFixtures.fixedClock());
        lenient().when(airportCatalog.unknown(anyCollection())).thenReturn(Set.of());
        lenient().when(reservationRepository.save(any(Reservation.class)))
                .thenAnswer(invocation -> invocation.<Reservation>getArgument(0).withVersion(3L));
    }

    private ModifyReservationCommand command(long expectedVersion) {
        return new ModifyReservationCommand(
                TestFixtures.RESERVATION_ID.value(), expectedVersion,
                TestFixtures.connectingItineraryData(), TestFixtures.owner());
    }

    @Test
    @DisplayName("cambia el itinerario y devuelve la reserva con la versión persistida")
    void modifiesItinerary() {
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID))
                .thenReturn(Optional.of(TestFixtures.storedReservation(2L, ReservationStatus.CONFIRMED)));

        Reservation modified = service.modify(command(2L));

        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(saved.capture());
        assertThat(saved.getValue().itinerary().destination()).isEqualTo(TestFixtures.MAD);
        assertThat(saved.getValue().itinerary().segments()).hasSize(2);
        assertThat(saved.getValue().updatedAt()).isEqualTo(TestFixtures.NOW);
        assertThat(saved.getValue().status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(modified.version()).isEqualTo(3L);
    }

    @Test
    @DisplayName("encola el evento con el itinerario anterior y el nuevo")
    void enqueuesModifiedEventWithBothItineraries() {
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID))
                .thenReturn(Optional.of(TestFixtures.storedReservation(0L)));

        service.modify(command(0L));

        ArgumentCaptor<Collection<DomainEvent>> events = ArgumentCaptor.captor();
        verify(eventOutbox).enqueue(events.capture());
        assertThat(events.getValue())
                .singleElement()
                .isInstanceOfSatisfying(ReservationModified.class, event -> {
                    assertThat(event.reservationId()).isEqualTo(TestFixtures.RESERVATION_ID);
                    assertThat(event.previousItinerary().destination()).isEqualTo(TestFixtures.SCL);
                    assertThat(event.itinerary().destination()).isEqualTo(TestFixtures.MAD);
                    assertThat(event.itinerary().segmentCount()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("falla si la reserva no existe")
    void failsWhenNotFound() {
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.modify(command(0L)))
                .isInstanceOf(ReservationNotFoundException.class)
                .hasMessageContaining("10");

        verify(reservationRepository, never()).save(any());
        verifyNoInteractions(eventOutbox);
    }

    @Test
    @DisplayName("detecta el conflicto de versión antes de validar aeropuertos o escribir")
    void detectsVersionConflictEarly() {
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID))
                .thenReturn(Optional.of(TestFixtures.storedReservation(5L)));

        assertThatThrownBy(() -> service.modify(command(4L)))
                .isInstanceOf(ConcurrentUpdateException.class)
                .hasMessageContaining("versión esperada 4, actual 5");

        verifyNoInteractions(airportCatalog);
        verify(reservationRepository, never()).save(any());
        verify(eventOutbox, never()).enqueue(anyCollection());
    }

    @Test
    @DisplayName("si un aeropuerto del nuevo itinerario no existe, no escribe")
    void rejectsUnknownAirport() {
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID))
                .thenReturn(Optional.of(TestFixtures.storedReservation(0L)));
        when(airportCatalog.unknown(anyCollection())).thenReturn(Set.of(TestFixtures.MAD));

        assertThatThrownBy(() -> service.modify(command(0L)))
                .isInstanceOf(UnknownAirportException.class)
                .hasMessageContaining("MAD");

        verify(reservationRepository, never()).save(any());
        verify(eventOutbox, never()).enqueue(anyCollection());
    }

    @Test
    @DisplayName("propaga la regla del dominio cuando la reserva está cancelada")
    void propagatesDomainRule() {
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID))
                .thenReturn(Optional.of(TestFixtures.storedReservation(0L, ReservationStatus.CANCELLED)));

        assertThatThrownBy(() -> service.modify(command(0L)))
                .isInstanceOf(ReservationNotModifiableException.class);

        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("rechaza un comando nulo")
    void rejectsNullCommand() {
        assertThatThrownBy(() -> service.modify(null)).isInstanceOf(NullPointerException.class);
    }
}
