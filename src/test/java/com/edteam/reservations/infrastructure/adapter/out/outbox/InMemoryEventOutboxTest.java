package com.edteam.reservations.infrastructure.adapter.out.outbox;

import com.edteam.reservations.application.outbox.OutboxMessage;
import com.edteam.reservations.application.outbox.OutboxStatus;
import com.edteam.reservations.domain.event.DomainEvent;
import com.edteam.reservations.domain.event.ReservationCancelled;
import com.edteam.reservations.domain.event.ReservationCreated;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InMemoryEventOutbox")
class InMemoryEventOutboxTest {

    private static final int MAX_ATTEMPTS = 3;

    private InMemoryEventOutbox outbox;

    @BeforeEach
    void setUp() {
        outbox = new InMemoryEventOutbox(TestFixtures.fixedClock(), MAX_ATTEMPTS);
    }

    private static DomainEvent createdEvent() {
        return ReservationCreated.of(TestFixtures.storedReservation(0L));
    }

    private static DomainEvent cancelledEvent() {
        return ReservationCancelled.of(TestFixtures.storedReservation(0L).cancel(TestFixtures.NOW));
    }

    @Test
    @DisplayName("encola los eventos como pendientes")
    void enqueuesEventsAsPending() {
        outbox.enqueue(List.of(createdEvent()));

        assertThat(outbox.pollPending(10))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.status()).isEqualTo(OutboxStatus.PENDING);
                    assertThat(message.attempts()).isZero();
                    assertThat(message.enqueuedAt()).isEqualTo(TestFixtures.NOW);
                    assertThat(message.id()).isNotBlank();
                });
    }

    @Test
    @DisplayName("respeta el orden en que ocurrieron los hechos")
    void preservesEventOrder() {
        outbox.enqueue(List.of(createdEvent()));
        outbox.enqueue(List.of(cancelledEvent()));

        assertThat(outbox.pollPending(10))
                .extracting(message -> message.event().eventType())
                .containsExactly("reservation.created", "reservation.cancelled");
    }

    @Test
    @DisplayName("no toma más mensajes que el lote pedido")
    void respectsBatchSize() {
        outbox.enqueue(List.of(createdEvent(), cancelledEvent()));

        assertThat(outbox.pollPending(1)).hasSize(1);
    }

    @Test
    @DisplayName("encolar una colección vacía no agrega nada")
    void enqueuingNothingDoesNothing() {
        outbox.enqueue(List.of());

        assertThat(outbox.pollPending(10)).isEmpty();
    }

    @Test
    @DisplayName("un mensaje despachado sale de la cola de pendientes")
    void dispatchedMessagesLeaveTheQueue() {
        outbox.enqueue(List.of(createdEvent()));
        OutboxMessage message = outbox.pollPending(1).getFirst();

        outbox.markDispatched(message.id());

        assertThat(outbox.pollPending(10)).isEmpty();
        assertThat(outbox.findByStatus(OutboxStatus.DISPATCHED))
                .singleElement()
                .satisfies(dispatched -> assertThat(dispatched.attempts()).isEqualTo(1));
    }

    @Test
    @DisplayName("un fallo deja el mensaje pendiente para reintentar y cuenta el intento")
    void failedMessagesStayPendingForRetry() {
        outbox.enqueue(List.of(createdEvent()));
        OutboxMessage message = outbox.pollPending(1).getFirst();

        outbox.markFailed(message.id(), "timeout");

        assertThat(outbox.pollPending(10))
                .singleElement()
                .satisfies(retried -> {
                    assertThat(retried.status()).isEqualTo(OutboxStatus.PENDING);
                    assertThat(retried.attempts()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("al agotar los intentos el mensaje queda en FAILED y no se reintenta más")
    void stopsRetryingAfterMaxAttempts() {
        outbox.enqueue(List.of(createdEvent()));
        String messageId = outbox.pollPending(1).getFirst().id();

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            outbox.markFailed(messageId, "timeout");
        }

        assertThat(outbox.pollPending(10)).isEmpty();
        assertThat(outbox.findByStatus(OutboxStatus.FAILED))
                .singleElement()
                .satisfies(failed -> assertThat(failed.attempts()).isEqualTo(MAX_ATTEMPTS));
    }

    @Test
    @DisplayName("marcar un mensaje inexistente no falla")
    void ignoresUnknownMessageIds() {
        outbox.markDispatched("no-existe");
        outbox.markFailed("no-existe", "error");

        assertThat(outbox.pollPending(10)).isEmpty();
    }

    @Test
    @DisplayName("valida los argumentos")
    void validatesArguments() {
        assertThatThrownBy(() -> new InMemoryEventOutbox(null, MAX_ATTEMPTS))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new InMemoryEventOutbox(TestFixtures.fixedClock(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbox.enqueue(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> outbox.pollPending(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
