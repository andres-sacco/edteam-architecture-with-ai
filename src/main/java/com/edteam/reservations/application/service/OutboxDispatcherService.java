package com.edteam.reservations.application.service;

import com.edteam.reservations.application.outbox.OutboxDispatchResult;
import com.edteam.reservations.application.outbox.OutboxMessage;
import com.edteam.reservations.application.port.in.DispatchPendingNotificationsUseCase;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.application.port.out.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Toma los eventos pendientes del outbox y los envía al sistema externo de
 * notificaciones.
 *
 * <p>Es la pieza que hace asincrónica la integración: corre fuera del hilo que
 * atiende al usuario (lo dispara {@code OutboxDispatchScheduler}), así que si
 * el sistema de notificaciones está lento o caído, las reservas no se ven
 * afectadas — los mensajes simplemente se acumulan y se reintentan.
 *
 * <p>Un fallo aislado no interrumpe el lote: cada mensaje se marca por
 * separado. La entrega es <em>at-least-once</em>, de modo que el consumidor
 * debe ser idempotente.
 */
@Service
public class OutboxDispatcherService implements DispatchPendingNotificationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcherService.class);

    private final EventOutboxPort eventOutbox;
    private final NotificationPort notificationPort;

    public OutboxDispatcherService(EventOutboxPort eventOutbox, NotificationPort notificationPort) {
        this.eventOutbox = Objects.requireNonNull(eventOutbox);
        this.notificationPort = Objects.requireNonNull(notificationPort);
    }

    @Override
    public OutboxDispatchResult dispatchPending(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("El tamaño del lote debe ser positivo");
        }

        List<OutboxMessage> pending = eventOutbox.pollPending(batchSize);
        if (pending.isEmpty()) {
            return OutboxDispatchResult.EMPTY;
        }

        int dispatched = 0;
        int failed = 0;
        for (OutboxMessage message : pending) {
            try {
                notificationPort.notify(message.event());
                eventOutbox.markDispatched(message.id());
                dispatched++;
            } catch (RuntimeException e) {
                // No se corta el lote: el resto de los mensajes se sigue intentando.
                log.warn("Falló la notificación del evento {} (mensaje {}, intento {}): {}",
                        message.event().eventType(), message.id(), message.attempts() + 1, e.getMessage());
                eventOutbox.markFailed(message.id(), e.getMessage());
                failed++;
            }
        }

        log.debug("Despacho de outbox: {} enviados, {} fallidos", dispatched, failed);
        return new OutboxDispatchResult(dispatched, failed);
    }
}
