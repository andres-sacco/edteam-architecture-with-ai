package com.edteam.reservations.infrastructure.adapter.in.ops;

import com.edteam.reservations.infrastructure.adapter.out.messaging.DeadLetterQueue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;

/**
 * Endpoint {@code messaging-dlq} del puerto de gestión: la dead letter del
 * <b>consumidor</b>.
 *
 * <p>Va aparte del endpoint {@code outbox} porque son dos fallas distintas:
 * «no pudimos publicar» y «no pudieron procesar». Juntarlas en un endpoint
 * daría un número que no se sabe a quién derivar.
 *
 * <p>Un {@code depth} de {@code -1} significa «no se sabe» —no hay broker— y no
 * «está vacía». La diferencia importa en un tablero con una alerta en
 * {@code > 0}: un cero falso tranquiliza.
 */
@Endpoint(id = "messaging-dlq")
public class DeadLetterEndpoint {

    private final DeadLetterQueue deadLetterQueue;

    public DeadLetterEndpoint(DeadLetterQueue deadLetterQueue) {
        this.deadLetterQueue = Objects.requireNonNull(deadLetterQueue);
    }

    /** {@code GET /actuator/messaging-dlq} — profundidad y muestra, sin consumir. */
    @ReadOperation
    public Map<String, Object> read() {
        Map<String, Object> body = new LinkedHashMap<>();
        long depth = deadLetterQueue.depth();
        body.put("depth", depth);
        body.put("known", depth >= 0);
        body.put("messages", deadLetterQueue.peek(20));
        return body;
    }

    /**
     * {@code POST /actuator/messaging-dlq} — reencola la DLQ hacia la cola
     * principal, para después de arreglar la causa.
     *
     * <p>Mueve, no copia, y conserva el {@code messageId}: lo que ya se hubiera
     * aplicado se deduplica y no produce un segundo efecto.
     */
    @WriteOperation
    public Map<String, Object> replay(@Nullable Integer max) {
        int limit = max == null || max <= 0 ? 100 : max;
        return Map.of("requeued", deadLetterQueue.replay(limit), "max", limit);
    }
}
