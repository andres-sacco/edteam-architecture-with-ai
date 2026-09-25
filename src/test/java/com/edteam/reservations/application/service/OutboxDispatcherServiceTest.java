package com.edteam.reservations.application.service;

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

import com.edteam.reservations.application.exception.EventPublishException;
import com.edteam.reservations.application.exception.EventPublisherUnavailableException;
import com.edteam.reservations.application.outbox.OutboxDispatchResult;
import com.edteam.reservations.application.outbox.OutboxFailure;
import com.edteam.reservations.application.outbox.OutboxMessage;
import com.edteam.reservations.application.outbox.OutboxStatus;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.application.port.out.EventPublisherPort;
import com.edteam.reservations.support.TestFixtures;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

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
        return new OutboxMessage(
                id,
                type,
                1,
                subject,
                sequence,
                "{}",
                "corr-1",
                TestFixtures.NOW,
                TestFixtures.NOW,
                0,
                OutboxStatus.IN_FLIGHT);
    }

    // =================================================================
    // El id de la corrida del relay (hallazgo 10 de la auditoría)
    // =================================================================

    @Test
    @DisplayName("al terminar, el MDC vuelve al id de la corrida en lugar de quedar vacío")
    void restoresTheRunIdInsteadOfClearingIt() {
        // Era el hallazgo 10: el despachador pisaba el correlationId con el del
        // mensaje y lo BORRABA al terminar. Con el id de la corrida del relay
        // puesto por el decorador del scheduler, eso hacía que —después del
        // primer mensaje— el resto de la vuelta saliera sin ningún id: las dos
        // líneas de cierre del despachador y, sobre todo, la línea INFO del
        // tick, que es la que el §1.4 del diseño muestra como ejemplo
        // llevándolo.
        MDC.put("correlationId", "job-outbox-relay-3f2a91c4");
        try {
            when(eventOutbox.pollPending(anyInt()))
                    .thenReturn(List.of(message("m-1", "reservation.created", "10241", 1)));

            service.dispatchPending(10);

            assertThat(MDC.get("correlationId"))
                    .withFailMessage("El relay dejó el MDC vacío: el resto de la vuelta sale sin id")
                    .isEqualTo("job-outbox-relay-3f2a91c4");
        } finally {
            MDC.clear();
        }
    }

    @Test
    @DisplayName("durante la publicación, el MDC lleva el id del pedido que originó el hecho")
    void usesTheOriginatingRequestIdWhilePublishing() {
        // Es el salto mejor resuelto del sistema y no se toca: la traza cruza
        // el borde de lo sincrónico a lo asincrónico porque el envelope lleva
        // el id del pedido que originó el hecho.
        MDC.put("correlationId", "job-outbox-relay-3f2a91c4");
        List<String> seenWhilePublishing = new ArrayList<>();
        try {
            when(eventOutbox.pollPending(anyInt()))
                    .thenReturn(List.of(message("m-1", "reservation.created", "10241", 1)));
            doAnswer(invocation -> {
                        seenWhilePublishing.add(MDC.get("correlationId"));
                        return null;
                    })
                    .when(eventPublisher)
                    .publish(any(OutboxMessage.class));

            service.dispatchPending(10);

            assertThat(seenWhilePublishing).containsExactly("corr-1");
        } finally {
            MDC.clear();
        }
    }

    @Test
    @DisplayName("sin id previo, el MDC queda limpio y no explota")
    void withoutAPreviousIdTheMdcIsLeftClean() {
        // `MDC.setContextMap(null)` tira IllegalArgumentException, y «no había
        // nada» es el caso normal en un test y en un reprocesamiento manual.
        MDC.clear();
        when(eventOutbox.pollPending(anyInt())).thenReturn(List.of(message("m-1", "reservation.created", "10241", 1)));

        service.dispatchPending(10);

        assertThat(MDC.get("correlationId")).isNull();
    }

    // =================================================================
    // El circuito del broker visto desde el relay
    // =================================================================

    @Test
    @DisplayName("si el publicador avisa que el destino esta caido, el mensaje NO gasta su intento")
    void anUnavailablePublisherDoesNotSpendTheAttempt() {
        // Es todo el valor del circuito sobre el outbox. Sin esta distincion,
        // una caida larga del broker consume el tope de reintentos de mensajes
        // perfectamente recuperables y los manda a la dead letter, donde
        // alguien tiene que drenarlos a mano: es la diferencia entre "la
        // notificacion llego tarde" y "la notificacion se perdio".
        when(eventOutbox.pollPending(10))
                .thenReturn(List.of(
                        message("m1", "reservation.created", "10", 1L),
                        message("m2", "reservation.created", "11", 2L)));
        doThrow(new EventPublisherUnavailableException("circuito abierto"))
                .when(eventPublisher)
                .publish(any(OutboxMessage.class));

        OutboxDispatchResult result = service.dispatchPending(10);

        assertThat(result.dispatched()).isZero();
        assertThat(result.failed()).as("no fallaron: nunca se intentaron").isZero();
        verify(eventOutbox, never()).markFailed(anyString(), anyString(), any(OutboxFailure.class));
        verify(eventOutbox).release(List.of("m1", "m2"));
    }

    @Test
    @DisplayName("con el destino caido corta el lote entero en el primer aviso")
    void stopsTheWholeBatchOnTheFirstUnavailableAnswer() {
        when(eventOutbox.pollPending(10))
                .thenReturn(List.of(
                        message("m1", "reservation.created", "10", 1L),
                        message("m2", "reservation.created", "11", 2L),
                        message("m3", "reservation.created", "12", 3L)));
        doThrow(new EventPublisherUnavailableException("circuito abierto"))
                .when(eventPublisher)
                .publish(any(OutboxMessage.class));

        service.dispatchPending(10);

        // Una sola llamada al publicador: los que faltaban iban a recibir la
        // misma respuesta, y preguntar tres veces lo mismo es trabajo tirado.
        verify(eventPublisher, org.mockito.Mockito.times(1)).publish(any(OutboxMessage.class));
    }

    @Test
    @DisplayName("una sonda que no prospera libera el mensaje sin contarle el intento")
    void aFailedProbeReleasesTheMessage() {
        // La sonda del circuito semiabierto se dispara una y otra vez durante
        // toda la caida. Cobrarle el intento al mensaje que tuvo la mala
        // suerte de ser elegido lo acerca a la dead letter por un problema que
        // no es suyo: con cinco mensajes pendientes una madrugada, cada uno
        // agotaba su tope en menos de una hora y moria igual, que es
        // exactamente lo que el circuito venia a evitar.
        when(eventOutbox.pollProbe()).thenReturn(List.of(message("m1", "reservation.created", "10", 1L)));
        doThrow(new EventPublishException("el broker no confirmo"))
                .when(eventPublisher)
                .publish(any(OutboxMessage.class));

        OutboxDispatchResult result = service.dispatchProbe();

        assertThat(result.dispatched()).isZero();
        verify(eventOutbox, never()).markFailed(anyString(), anyString(), any(OutboxFailure.class));
        verify(eventOutbox).release(List.of("m1"));
    }

    @Test
    @DisplayName("una sonda que prospera SI despacha el mensaje: no es una llamada de mentira")
    void aSuccessfulProbeActuallyDelivers() {
        when(eventOutbox.pollProbe()).thenReturn(List.of(message("m1", "reservation.created", "10", 1L)));

        OutboxDispatchResult result = service.dispatchProbe();

        assertThat(result.dispatched()).isEqualTo(1);
        verify(eventOutbox).markDispatched("m1");
    }

    @Test
    @DisplayName("un fallo de publicacion comun SI gasta el intento: el circuito no cambia eso")
    void anOrdinaryFailureStillSpendsTheAttempt() {
        when(eventOutbox.pollPending(10)).thenReturn(List.of(message("m1", "reservation.created", "10", 1L)));
        doThrow(new EventPublishException("el broker no confirmo"))
                .when(eventPublisher)
                .publish(any(OutboxMessage.class));

        OutboxDispatchResult result = service.dispatchPending(10);

        assertThat(result.failed()).isEqualTo(1);
        verify(eventOutbox).markFailed(eq("m1"), anyString(), eq(OutboxFailure.TRANSIENT));
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
        when(eventOutbox.pollPending(10))
                .thenReturn(List.of(
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
        OutboxMessage claimed =
                message("0f7a6f2e-6b77-4a3a-9a5f-3c4a6b2f10d1", "reservation.confirmed", "8421", 10_688L);
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
        when(eventOutbox.pollPending(10))
                .thenReturn(List.of(failing, message("m2", "reservation.cancelled", "99", 2L)));
        doThrow(new EventPublishException("el broker no confirmó"))
                .when(eventPublisher)
                .publish(failing);

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
        doThrow(new EventPublishException("503 del broker"))
                .when(eventPublisher)
                .publish(transitory);
        // El payload guardado no es JSON: insistir da el mismo resultado.
        doThrow(new IllegalStateException("payload inválido"))
                .when(eventPublisher)
                .publish(poison);

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
                })
                .when(eventPublisher)
                .publish(any());

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
        when(eventOutbox.pollPending(10))
                .thenReturn(List.of(
                        message("m1", "reservation.created", "8421", 10L),
                        message("m2", "reservation.confirmed", "8421", 11L)));
        doAnswer(invocation -> sequences.add(
                        invocation.getArgument(0, OutboxMessage.class).sequence()))
                .when(eventPublisher)
                .publish(any());

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
