package com.edteam.reservations.application.service;

import com.edteam.reservations.application.exception.ConcurrentUpdateException;
import com.edteam.reservations.application.exception.ReservationNotFoundException;
import com.edteam.reservations.application.port.in.CancelReservationCommand;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.domain.event.DomainEvent;
import com.edteam.reservations.domain.event.ReservationCancelled;
import com.edteam.reservations.domain.exception.ReservationAlreadyCancelledException;
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
@DisplayName("CancelReservationService")
class CancelReservationServiceTest {

    @Mock
    private ReservationRepositoryPort reservationRepository;

    @Mock
    private EventOutboxPort eventOutbox;

    private CancelReservationService service;

    @BeforeEach
    void setUp() {
        service = new CancelReservationService(reservationRepository, eventOutbox, TestFixtures.fixedClock());
        lenient().when(reservationRepository.save(any(Reservation.class)))
                .thenAnswer(invocation -> invocation.<Reservation>getArgument(0).withVersion(1L));
    }

    private CancelReservationCommand command(long expectedVersion) {
        return new CancelReservationCommand(TestFixtures.RESERVATION_ID.value(), expectedVersion);
    }

    @Test
    @DisplayName("cancela de forma lógica: persiste la reserva en estado CANCELLED")
    void cancelsReservation() {
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID))
                .thenReturn(Optional.of(TestFixtures.storedReservation(0L, ReservationStatus.CONFIRMED)));

        Reservation cancelled = service.cancel(command(0L));

        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(saved.getValue().updatedAt()).isEqualTo(TestFixtures.NOW);
        // Se conserva el registro: itinerario y pasajeros siguen ahí.
        assertThat(saved.getValue().itinerary()).isNotNull();
        assertThat(saved.getValue().passengers()).isNotEmpty();
        assertThat(cancelled.version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("encola el evento de cancelación")
    void enqueuesCancelledEvent() {
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID))
                .thenReturn(Optional.of(TestFixtures.storedReservation(0L)));

        service.cancel(command(0L));

        ArgumentCaptor<Collection<DomainEvent>> events = ArgumentCaptor.captor();
        verify(eventOutbox).enqueue(events.capture());
        assertThat(events.getValue())
                .singleElement()
                .isInstanceOfSatisfying(ReservationCancelled.class, event -> {
                    assertThat(event.reservationId()).isEqualTo(TestFixtures.RESERVATION_ID);
                    assertThat(event.userId()).isEqualTo(TestFixtures.USER_ID);
                    assertThat(event.eventType()).isEqualTo(ReservationCancelled.TYPE);
                });
    }

    @Test
    @DisplayName("falla si la reserva no existe")
    void failsWhenNotFound() {
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(command(0L)))
                .isInstanceOf(ReservationNotFoundException.class);

        verify(reservationRepository, never()).save(any());
        verifyNoInteractions(eventOutbox);
    }

    @Test
    @DisplayName("falla si otro proceso modificó la reserva")
    void failsOnVersionConflict() {
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID))
                .thenReturn(Optional.of(TestFixtures.storedReservation(2L)));

        assertThatThrownBy(() -> service.cancel(command(1L)))
                .isInstanceOf(ConcurrentUpdateException.class);

        verify(reservationRepository, never()).save(any());
        verify(eventOutbox, never()).enqueue(anyCollection());
    }

    @Test
    @DisplayName("no vuelve a cancelar una reserva ya cancelada ni notifica de nuevo")
    void failsWhenAlreadyCancelled() {
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID))
                .thenReturn(Optional.of(TestFixtures.storedReservation(0L, ReservationStatus.CANCELLED)));

        assertThatThrownBy(() -> service.cancel(command(0L)))
                .isInstanceOf(ReservationAlreadyCancelledException.class);

        verify(reservationRepository, never()).save(any());
        verify(eventOutbox, never()).enqueue(anyCollection());
    }

    @Test
    @DisplayName("rechaza un comando nulo")
    void rejectsNullCommand() {
        assertThatThrownBy(() -> service.cancel(null)).isInstanceOf(NullPointerException.class);
    }
}
