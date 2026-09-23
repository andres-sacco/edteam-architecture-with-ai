package com.edteam.reservations.application.service;

import com.edteam.reservations.application.exception.ConcurrentUpdateException;
import com.edteam.reservations.application.exception.ReservationNotFoundException;
import com.edteam.reservations.application.port.in.ConfirmReservationCommand;
import com.edteam.reservations.application.port.out.AuditTrailPort;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.domain.event.DomainEvent;
import com.edteam.reservations.domain.event.ReservationConfirmed;
import com.edteam.reservations.domain.exception.ReservationNotModifiableException;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConfirmReservationService")
class ConfirmReservationServiceTest {

    @Mock
    private ReservationRepositoryPort reservationRepository;

    @Mock
    private EventOutboxPort eventOutbox;

    @Mock
    private AuditTrailPort auditTrail;

    private ConfirmReservationService service;

    @BeforeEach
    void setUp() {
        service = new ConfirmReservationService(reservationRepository, eventOutbox, auditTrail,
                TestFixtures.fixedClock());
        lenient().when(reservationRepository.save(any(Reservation.class)))
                .thenAnswer(invocation -> invocation.<Reservation>getArgument(0).withVersion(1L));
    }

    private ConfirmReservationCommand command(long expectedVersion) {
        return new ConfirmReservationCommand(TestFixtures.RESERVATION_ID.value(), expectedVersion,
                TestFixtures.owner());
    }

    @Test
    @DisplayName("confirma una reserva pendiente y encola el evento")
    void confirmsPendingReservation() {
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID))
                .thenReturn(Optional.of(TestFixtures.storedReservation(0L)));

        Reservation confirmed = service.confirm(command(0L));

        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(confirmed.version()).isEqualTo(1L);

        ArgumentCaptor<Collection<DomainEvent>> events = ArgumentCaptor.captor();
        verify(eventOutbox).enqueue(events.capture());
        assertThat(events.getValue())
                .singleElement()
                .isInstanceOfSatisfying(ReservationConfirmed.class, event ->
                        assertThat(event.eventType()).isEqualTo(ReservationConfirmed.TYPE));
    }

    @Test
    @DisplayName("falla si la reserva no existe")
    void failsWhenNotFound() {
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(command(0L)))
                .isInstanceOf(ReservationNotFoundException.class);

        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("falla si otro proceso modificó la reserva")
    void failsOnVersionConflict() {
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID))
                .thenReturn(Optional.of(TestFixtures.storedReservation(3L)));

        assertThatThrownBy(() -> service.confirm(command(2L)))
                .isInstanceOf(ConcurrentUpdateException.class);

        verify(reservationRepository, never()).save(any());
        verify(eventOutbox, never()).enqueue(anyCollection());
    }

    @Test
    @DisplayName("no confirma dos veces")
    void rejectsAlreadyConfirmedReservation() {
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID))
                .thenReturn(Optional.of(TestFixtures.storedReservation(0L, ReservationStatus.CONFIRMED)));

        assertThatThrownBy(() -> service.confirm(command(0L)))
                .isInstanceOf(ReservationNotModifiableException.class);

        verify(reservationRepository, never()).save(any());
        verify(eventOutbox, never()).enqueue(anyCollection());
    }

    @Test
    @DisplayName("rechaza un comando nulo")
    void rejectsNullCommand() {
        assertThatThrownBy(() -> service.confirm(null)).isInstanceOf(NullPointerException.class);
    }
}
