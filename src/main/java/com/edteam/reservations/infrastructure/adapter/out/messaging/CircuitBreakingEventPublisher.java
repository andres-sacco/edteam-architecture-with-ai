package com.edteam.reservations.infrastructure.adapter.out.messaging;

import com.edteam.reservations.application.exception.EventPublisherUnavailableException;
import com.edteam.reservations.application.outbox.OutboxMessage;
import com.edteam.reservations.application.port.out.EventPublisherPort;
import com.edteam.reservations.infrastructure.resilience.Circuit;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.util.Objects;

/**
 * El circuito del broker.
 *
 * <p>Este circuito <strong>no protege latencia</strong>: nadie está esperando
 * una publicación, el usuario ya recibió su reserva. Protege el
 * <em>presupuesto de reintentos</em> del mensaje, que es lo que separa «la
 * notificación llegó tarde» de «la notificación hay que reenviarla a mano».
 *
 * <p>Sin circuito, con el broker caído cada tick del relay reclama un lote,
 * cada mensaje paga el {@code connection-timeout} más el
 * {@code confirm-timeout} y <strong>gasta uno de sus intentos</strong>. Una
 * caída de veinte minutos consume el tope de mensajes cuyo fallo era 100 %
 * recuperable y los manda a la dead letter del productor, donde alguien tiene
 * que drenarlos a mano.
 *
 * <p>Con el circuito, ese intento no se gasta: el rechazo sale como
 * {@link EventPublisherUnavailableException}, que el despachador distingue de
 * un fallo de publicación y trata con {@code release} en lugar de
 * {@code markFailed}. El mensaje queda exactamente donde estaba.
 *
 * <p>Un mensaje devuelto por falta de binding ({@code EventRoutingException})
 * pasa por acá sin contar: es un error de topología nuestro, y abrir el
 * circuito por una routing key huérfana frenaría la entrega de todos los demás
 * eventos. Esa decisión vive en el clasificador de fallos, no en esta clase.
 */
public class CircuitBreakingEventPublisher implements EventPublisherPort {

    private final EventPublisherPort delegate;
    private final Circuit circuit;

    public CircuitBreakingEventPublisher(EventPublisherPort delegate, Circuit circuit) {
        this.delegate = Objects.requireNonNull(delegate, "El delegado es obligatorio");
        this.circuit = Objects.requireNonNull(circuit, "El circuito es obligatorio");
    }

    @Override
    public void publish(OutboxMessage message) {
        try {
            circuit.execute(() -> {
                delegate.publish(message);
                return null;
            });
        } catch (CallNotPermittedException e) {
            throw new EventPublisherUnavailableException(
                    "El circuito del broker está abierto: no se intentó publicar %s".formatted(message.id()), e);
        }
    }
}
