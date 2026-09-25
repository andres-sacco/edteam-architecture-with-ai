package com.edteam.reservations.infrastructure.adapter.out.outbox;

import com.edteam.reservations.application.outbox.OutboxFailure;
import com.edteam.reservations.application.outbox.OutboxMessage;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.domain.event.DomainEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Decorador que publica el ritmo del outbox.
 *
 * <p>Un outbox que nadie mide es peor que un cache que nadie mide: acá no se
 * pierde velocidad, se pierden notificaciones. Sin estas series no se puede
 * responder ninguna de las preguntas operativas —cuántos hay pendientes, hace
 * cuánto está trabado el más viejo, cuántos murieron— y por eso mismo un
 * reintento en caliente y una dead letter muda son <em>invisibles</em>: el
 * sistema se ve igual de sano con la cola vacía y con la cola trabada.
 *
 * <p>Series que publica:
 * <ul>
 *   <li>{@code reservations.outbox.enqueued} — hechos encolados;</li>
 *   <li>{@code reservations.outbox.claimed} — reclamados por el relay;</li>
 *   <li>{@code reservations.outbox.dispatched} — publicados con éxito;</li>
 *   <li>{@code reservations.outbox.failed} con {@code failure=transient|permanent}
 *       — el ritmo de fallo y, sobre todo, su naturaleza: un pico de
 *       {@code permanent} es un problema del payload y uno de
 *       {@code transient} es un problema del broker. Se resuelven en lugares
 *       distintos;</li>
 *   <li>{@code reservations.outbox.deferred} — mensajes postergados para no
 *       adelantar el orden de su reserva.</li>
 * </ul>
 *
 * <p>Los <em>gauges</em> —pendientes, lag, dead letter— no están acá: se leen
 * de la tabla en {@link OutboxMetrics}, porque su valor es el estado
 * compartido por todas las instancias y no lo que hizo ésta.
 *
 * <p>Es un decorador y no instrumentación dentro del adaptador por la misma
 * razón que en el cache: la decisión queda visible en el cableado y cada pieza
 * se puede testear sin la otra.
 */
public final class MeteredEventOutbox implements EventOutboxPort {

    public static final String ENQUEUED = "reservations.outbox.enqueued";
    public static final String CLAIMED = "reservations.outbox.claimed";
    public static final String DISPATCHED = "reservations.outbox.dispatched";
    public static final String FAILED = "reservations.outbox.failed";
    public static final String DEFERRED = "reservations.outbox.deferred";

    private final EventOutboxPort delegate;
    private final Counter enqueued;
    private final Counter claimed;
    private final Counter dispatched;
    private final Counter transientFailures;
    private final Counter permanentFailures;
    private final Counter deferred;

    public MeteredEventOutbox(EventOutboxPort delegate, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "El delegado es obligatorio");
        Objects.requireNonNull(registry, "El registro de métricas es obligatorio");

        this.enqueued = Counter.builder(ENQUEUED)
                .description("Hechos encolados en el outbox, en la transacción del caso de uso")
                .register(registry);
        this.claimed = Counter.builder(CLAIMED)
                .description("Mensajes reclamados por el relay para publicar")
                .register(registry);
        this.dispatched = Counter.builder(DISPATCHED)
                .description("Mensajes publicados con confirmación del destino")
                .register(registry);
        this.transientFailures = Counter.builder(FAILED)
                .tags(Tags.of("failure", "transient"))
                .description("Fallos de publicación reintentables")
                .register(registry);
        this.permanentFailures = Counter.builder(FAILED)
                .tags(Tags.of("failure", "permanent"))
                .description("Fallos de publicación que van a la dead letter sin reintentos")
                .register(registry);
        this.deferred = Counter.builder(DEFERRED)
                .description("Mensajes postergados para no adelantar el orden de su reserva")
                .register(registry);
    }

    @Override
    public void enqueue(Collection<DomainEvent> events) {
        delegate.enqueue(events);
        enqueued.increment(events.size());
    }

    @Override
    public List<OutboxMessage> pollPending(int maxMessages) {
        List<OutboxMessage> messages = delegate.pollPending(maxMessages);
        claimed.increment(messages.size());
        return messages;
    }

    @Override
    public List<OutboxMessage> pollProbe() {
        List<OutboxMessage> messages = delegate.pollProbe();
        claimed.increment(messages.size());
        return messages;
    }

    @Override
    public void markDispatched(String messageId) {
        delegate.markDispatched(messageId);
        dispatched.increment();
    }

    @Override
    public void markFailed(String messageId, String error, OutboxFailure failure) {
        delegate.markFailed(messageId, error, failure);
        if (failure == OutboxFailure.PERMANENT) {
            permanentFailures.increment();
        } else {
            transientFailures.increment();
        }
    }

    @Override
    public void release(Collection<String> messageIds) {
        delegate.release(messageIds);
        deferred.increment(messageIds.size());
    }
}
