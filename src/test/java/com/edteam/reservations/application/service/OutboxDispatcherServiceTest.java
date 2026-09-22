package com.edteam.reservations.application.service;

import com.edteam.reservations.application.exception.NotificationDeliveryException;
import com.edteam.reservations.application.outbox.OutboxDispatchResult;
import com.edteam.reservations.application.outbox.OutboxMessage;
import com.edteam.reservations.application.outbox.OutboxStatus;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.application.port.out.NotificationPort;
import com.edteam.reservations.domain.event.DomainEvent;
import com.edteam.reservations.domain.event.ReservationCancelled;
import com.edteam.reservations.domain.event.ReservationCreated;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxDispatcherService")
class OutboxDispatcherServiceTest {

    @Mock
    private EventOutboxPort eventOutbox;

    @Mock
    private NotificationPort notificationPort;

    private OutboxDispatcherService service;

    @BeforeEach
    void setUp() {
        service = new OutboxDispatcherService(eventOutbox, notificationPort);
    }

    private static OutboxMessage message(String id, DomainEvent event) {
        return new OutboxMessage(id, event, 0, OutboxStatus.PENDING, TestFixtures.NOW);
    }

    private static ReservationCreated createdEvent() {
        return ReservationCreated.of(TestFixtures.storedReservation(0L));
    }

    private static ReservationCancelled cancelledEvent() {
        return ReservationCancelled.of(TestFixtures.storedReservation(0L).cancel(TestFixtures.NOW));
    }

    @Test
    @DisplayName("no hace nada si no hay mensajes pendientes")
    void doesNothingWhenEmpty() {
        when(eventOutbox.pollPending(anyInt())).thenReturn(List.of());

        assertThat(service.dispatchPending(10)).isEqualTo(OutboxDispatchResult.EMPTY);

        verifyNoInteractions(notificationPort);
    }

    @Test
    @DisplayName("notifica cada evento pendiente y lo marca como despachado")
    void dispatchesPendingMessages() {
        when(eventOutbox.pollPending(10)).thenReturn(List.of(
                message("m1", createdEvent()),
                message("m2", cancelledEvent())));

        OutboxDispatchResult result = service.dispatchPending(10);

        assertThat(result).isEqualTo(new OutboxDispatchResult(2, 0));
        assertThat(result.total()).isEqualTo(2);
        verify(notificationPort, org.mockito.Mockito.times(2)).notify(any(DomainEvent.class));
        verify(eventOutbox).markDispatched("m1");
        verify(eventOutbox).markDispatched("m2");
        verify(eventOutbox, never()).markFailed(anyString(), anyString());
    }

    @Test
    @DisplayName("un fallo en un mensaje no interrumpe el resto del lote")
    void isolatesFailures() {
        ReservationCreated failing = createdEvent();
        when(eventOutbox.pollPending(10)).thenReturn(List.of(
                message("m1", failing),
                message("m2", cancelledEvent())));
        doThrow(new NotificationDeliveryException("servicio de notificaciones no disponible"))
                .when(notificationPort).notify(failing);

        OutboxDispatchResult result = service.dispatchPending(10);

        assertThat(result).isEqualTo(new OutboxDispatchResult(1, 1));
        verify(eventOutbox).markFailed(eq("m1"), anyString());
        verify(eventOutbox).markDispatched("m2");
        verify(eventOutbox, never()).markDispatched("m1");
    }

    @Test
    @DisplayName("captura cualquier RuntimeException del adaptador, no sólo las esperadas")
    void handlesUnexpectedRuntimeExceptions() {
        when(eventOutbox.pollPending(5)).thenReturn(List.of(message("m1", createdEvent())));
        doThrow(new IllegalStateException("cliente HTTP mal configurado"))
                .when(notificationPort).notify(any(DomainEvent.class));

        assertThat(service.dispatchPending(5)).isEqualTo(new OutboxDispatchResult(0, 1));

        verify(eventOutbox).markFailed(eq("m1"), anyString());
    }

    @Test
    @DisplayName("rechaza un tamaño de lote no positivo")
    void rejectsNonPositiveBatchSize() {
        assertThatThrownBy(() -> service.dispatchPending(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.dispatchPending(-1)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(eventOutbox, notificationPort);
    }

    @Test
    @DisplayName("exige los dos puertos")
    void requiresPorts() {
        assertThatThrownBy(() -> new OutboxDispatcherService(null, notificationPort))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutboxDispatcherService(eventOutbox, null))
                .isInstanceOf(NullPointerException.class);
    }
}
