package com.edteam.reservations.application.service;

import com.edteam.reservations.application.exception.EventPublishException;
import com.edteam.reservations.application.outbox.OutboxDispatchResult;
import com.edteam.reservations.application.outbox.OutboxFailure;
import com.edteam.reservations.application.outbox.OutboxMessage;
import com.edteam.reservations.application.outbox.OutboxStatus;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.application.port.out.EventPublisherPort;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxDispatcherService (relay del outbox)")
class OutboxDispatcherServiceTest {

    @Mock
    private EventOutboxPort eventOutbox;

    @Mock
    private EventPublisherPort eventPublisher;

    private OutboxDispatcherService service;

    @BeforeEach
    void setUp() {
        service = new OutboxDispatcherService(eventOutbox, eventPublisher);
    }

    private static OutboxMessage message(String id, String type, String subject, long sequence) {
        return new OutboxMessage(id, type, 1, subject, sequence, "{}", "corr-1",
                TestFixtures.NOW, TestFixtures.NOW, 0, OutboxStatus.IN_FLIGHT);
    }

    @Test
    @DisplayName("no hace nada si no hay mensajes elegibles")
    void doesNothingWhenEmpty() {
        when(eventOutbox.pollPending(anyInt())).thenReturn(List.of());

        assertThat(service.dispatchPending(10)).isEqualTo(OutboxDispatchResult.EMPTY);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("publica cada mensaje reclamado y lo marca como despachado")
    void dispatchesClaimedMessages() {
        when(eventOutbox.pollPending(10)).thenReturn(List.of(
                message("m1", "reservation.created", "10", 1L),
                message("m2", "reservation.cancelled", "11", 2L)));

        OutboxDispatchResult result = service.dispatchPending(10);

        assertThat(result).isEqualTo(new OutboxDispatchResult(2, 0, 0));
        verify(eventOutbox).markDispatched("m1");
        verify(eventOutbox).markDispatched("m2");
        verify(eventOutbox, never()).markFailed(anyString(), anyString(), any());
    }

    /**
     * H3 — el {@code messageId} y el {@code sequence} tienen que llegar al
     * publicador.
     *
     * <p>Antes el despachador pasaba {@code message.event()}: la clave de
     * idempotencia que el contrato le pide propagar al consumidor no existía
     * del otro lado del puerto, así que <b>no había dónde asertar la clave</b>.
     * Ésa era la prueba. Ahora cruza el mensaje completo, y este test lo fija.
     */
    @Test
    @DisplayName("H3: el mensaje completo cruza el puerto, con su clave de idempotencia y su orden")
    void passesTheIdempotencyKeyAndOrderToThePublisher() {
        OutboxMessage claimed = message("0f7a6f2e-6b77-4a3a-9a5f-3c4a6b2f10d1",
                "reservation.confirmed", "8421", 10_688L);
        when(eventOutbox.pollPending(10)).thenReturn(List.of(claimed));

        service.dispatchPending(10);

        ArgumentCaptor<OutboxMessage> published = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue().id()).isEqualTo("0f7a6f2e-6b77-4a3a-9a5f-3c4a6b2f10d1");
        assertThat(published.getValue().sequence()).isEqualTo(10_688L);
        assertThat(published.getValue().subject()).isEqualTo("8421");
        assertThat(published.getValue().correlationId()).isEqualTo("corr-1");
    }

    @Test
    @DisplayName("un fallo en un mensaje no interrumpe el resto del lote")
    void isolatesFailures() {
        OutboxMessage failing = message("m1", "reservation.created", "10", 1L);
        when(eventOutbox.pollPending(10)).thenReturn(List.of(
                failing,
                message("m2", "reservation.cancelled", "99", 2L)));
        doThrow(new EventPublishException("el broker no confirmó")).when(eventPublisher).publish(failing);

        OutboxDispatchResult result = service.dispatchPending(10);

        assertThat(result).isEqualTo(new OutboxDispatchResult(1, 1, 0));
        verify(eventOutbox).markFailed(eq("m1"), anyString(), eq(OutboxFailure.TRANSIENT));
        verify(eventOutbox).markDispatched("m2");
        verify(eventOutbox, never()).markDispatched("m1");
    }

    /**
     * H9 — transitorio y permanente no recorren el mismo camino.
     *
     * <p>Antes los dos consumían los cinco intentos y terminaban en
     * {@code FAILED}: un mensaje venenoso quemaba el despachador por nada y un
     * broker caído diez minutos mandaba a la dead letter mensajes cuyo fallo
     * era 100 % recuperable. El test no distinguía porque el código tampoco.
     */
    @Test
    @DisplayName("H9: la excepción de publicación es transitoria y cualquier otra es permanente")
    void classifiesFailures() {
        OutboxMessage transitory = message("m1", "reservation.created", "10", 1L);
        OutboxMessage poison = message("m2", "reservation.created", "20", 2L);
        when(eventOutbox.pollPending(10)).thenReturn(List.of(transitory, poison));
        doThrow(new EventPublishException("503 del broker")).when(eventPublisher).publish(transitory);
        // El payload guardado no es JSON: insistir da el mismo resultado.
        doThrow(new IllegalStateException("payload inválido")).when(eventPublisher).publish(poison);

        assertThat(service.dispatchPending(10)).isEqualTo(new OutboxDispatchResult(0, 2, 0));

        verify(eventOutbox).markFailed(eq("m1"), anyString(), eq(OutboxFailure.TRANSIENT));
        verify(eventOutbox).markFailed(eq("m2"), anyString(), eq(OutboxFailure.PERMANENT));
    }

    /**
     * H6 — bloqueo por reserva.
     *
     * <p>Sin esto, un alta que falla una vez y una cancelación que sale bien
     * producen «se canceló tu reserva» antes de «registramos tu reserva».
     */
    @Test
    @DisplayName("H6: si un mensaje de una reserva falla, los siguientes de ESA reserva no se publican")
    void blocksBySubjectWhenAMessageFails() {
        OutboxMessage created = message("m1", "reservation.created", "8421", 10L);
        OutboxMessage cancelled = message("m2", "reservation.cancelled", "8421", 11L);
        OutboxMessage otherReservation = message("m3", "reservation.created", "9999", 12L);
        when(eventOutbox.pollPending(10)).thenReturn(List.of(created, cancelled, otherReservation));

        // Se registra lo que el publicador ve, en el orden en que lo ve: es la
        // única forma de asertar que la cancelación NO llegó a intentarse.
        List<String> publishedOrder = new ArrayList<>();
        doAnswer(invocation -> {
            OutboxMessage message = invocation.getArgument(0, OutboxMessage.class);
            if (message.id().equals("m1")) {
                throw new EventPublishException("el broker no confirmó");
            }
            publishedOrder.add(message.id());
            return null;
        }).when(eventPublisher).publish(any());

        OutboxDispatchResult result = service.dispatchPending(10);

        // La cancelación NO sale: su alta todavía no salió.
        assertThat(publishedOrder).containsExactly("m3");
        assertThat(result).isEqualTo(new OutboxDispatchResult(1, 1, 1));
        // Y no se le gasta un intento: no falló, sólo esperó.
        verify(eventOutbox).release(List.of("m2"));
        verify(eventOutbox, never()).markFailed(eq("m2"), anyString(), any());
        // El resto del lote sigue: el bloqueo es por reserva, no por lote.
        verify(eventOutbox).markDispatched("m3");
    }

    @Test
    @DisplayName("el orden dentro de una reserva es el del sequence")
    void publishesInSequenceOrderWithinAReservation() {
        List<Long> sequences = new ArrayList<>();
        when(eventOutbox.pollPending(10)).thenReturn(List.of(
                message("m1", "reservation.created", "8421", 10L),
                message("m2", "reservation.confirmed", "8421", 11L)));
        doAnswer(invocation -> sequences.add(
                invocation.getArgument(0, OutboxMessage.class).sequence())).when(eventPublisher).publish(any());

        service.dispatchPending(10);

        assertThat(sequences).containsExactly(10L, 11L);
    }

    @Test
    @DisplayName("no se libera nada si no hubo mensajes postergados")
    void doesNotReleaseWhenNothingWasDeferred() {
        when(eventOutbox.pollPending(10)).thenReturn(List.of(message("m1", "reservation.created", "10", 1L)));

        service.dispatchPending(10);

        verify(eventOutbox, never()).release(anyCollection());
    }

    @Test
    @DisplayName("rechaza un tamaño de lote no positivo")
    void rejectsNonPositiveBatchSize() {
        assertThatThrownBy(() -> service.dispatchPending(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.dispatchPending(-1)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(eventOutbox, eventPublisher);
    }

    @Test
    @DisplayName("exige los dos puertos")
    void requiresPorts() {
        assertThatThrownBy(() -> new OutboxDispatcherService(null, eventPublisher))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutboxDispatcherService(eventOutbox, null))
                .isInstanceOf(NullPointerException.class);
    }
}
