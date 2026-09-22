package com.edteam.reservations.infrastructure.adapter.out.outbox;

import com.edteam.reservations.application.outbox.OutboxMessage;
import com.edteam.reservations.application.outbox.OutboxStatus;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.domain.event.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox en memoria.
 *
 * <p><strong>Es un stub deliberado</strong> (no hay base de datos en este
 * esqueleto) y tiene una limitación importante que conviene tener presente: al
 * vivir en el heap, los mensajes pendientes se pierden si el proceso se cae, y
 * con varias instancias cada una tendría su propio outbox. La implementación
 * real es una tabla {@code outbox_message} escrita en la misma transacción que
 * la reserva, y el {@code pollPending} pasa a ser un
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} para que varias instancias puedan
 * despachar en paralelo sin duplicar envíos.
 *
 * <p>Los mensajes se ordenan por número de secuencia para respetar el orden en
 * que ocurrieron los hechos (un "modificada" no debería notificarse antes que
 * su "creada").
 */
public class InMemoryEventOutbox implements EventOutboxPort {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventOutbox.class);

    private final Map<String, StoredMessage> messages = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Clock clock;
    private final int maxAttempts;

    public InMemoryEventOutbox(Clock clock, int maxAttempts) {
        this.clock = Objects.requireNonNull(clock, "El clock es obligatorio");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts debe ser al menos 1");
        }
        this.maxAttempts = maxAttempts;
    }

    @Override
    public void enqueue(Collection<DomainEvent> events) {
        Objects.requireNonNull(events, "Los eventos son obligatorios");
        for (DomainEvent event : events) {
            String id = UUID.randomUUID().toString();
            messages.put(id, new StoredMessage(
                    id, sequence.incrementAndGet(), event, 0, OutboxStatus.PENDING, clock.instant()));
        }
    }

    @Override
    public List<OutboxMessage> pollPending(int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages debe ser positivo");
        }
        List<OutboxMessage> pending = new ArrayList<>();
        messages.values().stream()
                .filter(message -> message.status() == OutboxStatus.PENDING)
                .sorted(Comparator.comparingLong(StoredMessage::sequence))
                .limit(maxMessages)
                .forEach(message -> pending.add(message.toOutboxMessage()));
        return List.copyOf(pending);
    }

    @Override
    public void markDispatched(String messageId) {
        messages.computeIfPresent(messageId,
                (id, message) -> message.withStatus(OutboxStatus.DISPATCHED, message.attempts() + 1));
    }

    @Override
    public void markFailed(String messageId, String error) {
        messages.computeIfPresent(messageId, (id, message) -> {
            int attempts = message.attempts() + 1;
            if (attempts >= maxAttempts) {
                log.error("El evento {} (mensaje {}) agotó los {} intentos y queda en FAILED. Último error: {}",
                        message.event().eventType(), id, maxAttempts, error);
                return message.withStatus(OutboxStatus.FAILED, attempts);
            }
            return message.withStatus(OutboxStatus.PENDING, attempts);
        });
    }

    /**
     * Vacía el outbox.
     *
     * <p>Sólo para los tests de integración: el contexto de Spring —y con él
     * este bean— se comparte entre los tests de una clase, así que sin esto los
     * eventos de un test se cuentan en el siguiente. Que el estado viva en
     * memoria es justamente lo que hace falta reiniciar, igual que se trunca la
     * base antes de cada test.
     *
     * <p>Desaparece junto con este stub cuando el outbox pase a ser una tabla.
     */
    public void clear() {
        messages.clear();
    }

    /** Mensajes en el estado indicado; sólo para inspección en tests y diagnóstico. */
    public List<OutboxMessage> findByStatus(OutboxStatus status) {
        return messages.values().stream()
                .filter(message -> message.status() == status)
                .sorted(Comparator.comparingLong(StoredMessage::sequence))
                .map(StoredMessage::toOutboxMessage)
                .toList();
    }

    private record StoredMessage(String id,
                                 long sequence,
                                 DomainEvent event,
                                 int attempts,
                                 OutboxStatus status,
                                 java.time.Instant enqueuedAt) {

        StoredMessage withStatus(OutboxStatus newStatus, int newAttempts) {
            return new StoredMessage(id, sequence, event, newAttempts, newStatus, enqueuedAt);
        }

        OutboxMessage toOutboxMessage() {
            return new OutboxMessage(id, event, attempts, status, enqueuedAt);
        }
    }
}
