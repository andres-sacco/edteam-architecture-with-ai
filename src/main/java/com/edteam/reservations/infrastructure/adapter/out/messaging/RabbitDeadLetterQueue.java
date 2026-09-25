package com.edteam.reservations.infrastructure.adapter.out.messaging;

import com.edteam.reservations.infrastructure.logging.LogFields;
import com.edteam.reservations.infrastructure.logging.Throwables;
import com.edteam.reservations.infrastructure.security.OpsActor;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.GetResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Dead letter del consumidor sobre RabbitMQ.
 *
 * <p>Trabaja con el canal crudo y no con los helpers del {@code RabbitTemplate}
 * por una razón concreta: cada helper toma su propio canal, así que una
 * secuencia de «saco» y «pongo» hecha con ellos <b>no es atómica</b> y un fallo
 * en el medio vacía la dead letter. Todo lo de acá corre en un único canal
 * transaccionado.
 *
 * <h2>{@code peek} no consume, y va por un canal SIN transacción</h2>
 * {@code basicGet} sin auto-ack y después {@code basicNack} con
 * {@code requeue=true}: el mensaje vuelve a la cola, en su lugar, y sin haber
 * sido republicado. RabbitMQ no tiene un «mirar sin sacar» de verdad; esto es
 * lo más cerca que se llega sin alterar la cola.
 *
 * <p>Va por un canal sin transacción a propósito: en RabbitMQ
 * {@code basic.nack} <b>no</b> es transaccional, así que dentro de un
 * {@code tx.commit} el reencolado se descarta y mirar la dead letter la
 * vaciaría —el peor error posible en esta herramienta—. No hace falta
 * transacción: son dos operaciones sin publicación, y si el proceso muere entre
 * las dos, el cierre del canal devuelve a la cola todo lo no confirmado.
 *
 * <h2>{@code replay} mueve, no copia</h2>
 * {@code basicGet} + {@code basicPublish} a la cola principal + {@code ack},
 * todo en la misma transacción de canal: o se movió o sigue en la DLQ, nunca en
 * las dos. El {@code messageId} se conserva, así que lo que ya se hubiera
 * aplicado se deduplica del otro lado y reprocesar es seguro en lugar de ser
 * una apuesta.
 */
public class RabbitDeadLetterQueue implements DeadLetterQueue {

    private static final Logger log = LoggerFactory.getLogger(RabbitDeadLetterQueue.class);

    /** Publicación directa a una cola: exchange vacío, routing key = nombre de la cola. */
    private static final String DEFAULT_EXCHANGE = "";

    private final RabbitTemplate transactedTemplate;
    private final RabbitTemplate inspectionTemplate;
    private final RabbitAdmin admin;

    public RabbitDeadLetterQueue(
            RabbitTemplate transactedTemplate, RabbitTemplate inspectionTemplate, RabbitAdmin admin) {
        this.transactedTemplate = Objects.requireNonNull(transactedTemplate);
        this.inspectionTemplate = Objects.requireNonNull(inspectionTemplate);
        this.admin = Objects.requireNonNull(admin);
    }

    @Override
    public long depth() {
        try {
            Properties queue = admin.getQueueProperties(MessagingTopology.DLQ);
            if (queue == null) {
                return -1L;
            }
            Object count = queue.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
            return count == null ? 0L : ((Number) count).longValue();
        } catch (AmqpException e) {
            // Sin broker no se sabe. Un cero sería una afirmación falsa, y en
            // un tablero con alerta en '> 0' una afirmación falsa tranquiliza.
            log.atDebug()
                    .addKeyValue(LogFields.EVENT, "messaging.dlq_unreadable")
                    .addKeyValue(LogFields.EXCEPTION_CLASS, Throwables.classOf(e))
                    .addKeyValue(LogFields.REASON, Throwables.reasonOf(e))
                    .log("No se pudo leer la profundidad de la DLQ: el gauge devuelve el centinela");
            return -1L;
        }
    }

    @Override
    public List<DeadLetter> peek(int limit) {
        int capped = Math.clamp(limit, 1, 200);
        List<DeadLetter> found = inspectionTemplate.execute(channel -> {
            List<DeadLetter> messages = new ArrayList<>();
            List<Long> tags = new ArrayList<>();
            for (int i = 0; i < capped; i++) {
                GetResponse response = channel.basicGet(MessagingTopology.DLQ, false);
                if (response == null) {
                    break;
                }
                messages.add(describe(response));
                tags.add(response.getEnvelope().getDeliveryTag());
            }
            for (Long tag : tags) {
                // requeue=true: vuelven a la cola sin republicarse.
                channel.basicNack(tag, false, true);
            }
            return messages;
        });
        return found == null ? List.of() : List.copyOf(found);
    }

    @Override
    public int replay(int max) {
        int capped = Math.clamp(max, 1, 5000);
        Integer moved = transactedTemplate.execute(channel -> {
            int count = 0;
            for (int i = 0; i < capped; i++) {
                GetResponse response = channel.basicGet(MessagingTopology.DLQ, false);
                if (response == null) {
                    break;
                }
                // La vuelta de reintento se reinicia: el replay ocurre después
                // de arreglar la causa, y arrancar con las vueltas agotadas
                // devolvería el mensaje a la DLQ en el primer tropiezo.
                AMQP.BasicProperties properties = withAttemptReset(response.getProps());
                // Publicación directa a la cola y no al exchange: el replay
                // reinyecta a este consumidor y no vuelve a hacer fan-out a
                // otros, que quizá ya procesaron el mensaje sin problemas.
                channel.basicPublish(
                        DEFAULT_EXCHANGE, MessagingTopology.CONSUMER_QUEUE, properties, response.getBody());
                channel.basicAck(response.getEnvelope().getDeliveryTag(), false);
                count++;
            }
            return count;
        });
        int total = moved == null ? 0 : moved;
        log.atInfo()
                .addKeyValue(LogFields.EVENT, LogFields.OUTBOX_REQUEUED)
                .addKeyValue("queue", MessagingTopology.CONSUMER_QUEUE)
                .addKeyValue(LogFields.COUNT, total)
                .addKeyValue(LogFields.ACTOR, OpsActor.current())
                .log("Mensajes reencolados desde la dead letter del consumidor");
        return total;
    }

    private static AMQP.BasicProperties withAttemptReset(AMQP.BasicProperties original) {
        Map<String, Object> headers =
                original.getHeaders() == null ? new HashMap<>() : new HashMap<>(original.getHeaders());
        headers.put(MessagingTopology.ATTEMPT_HEADER, 0);
        headers.remove(MessagingTopology.DEAD_LETTER_REASON_HEADER);
        // 'x-death' se saca: si queda, el x-delivery-limit de la cola quorum
        // cuenta las entregas viejas y el mensaje vuelve a la DLQ enseguida.
        headers.remove("x-death");
        return original.builder().headers(headers).build();
    }

    private static DeadLetter describe(GetResponse response) {
        AMQP.BasicProperties properties = response.getProps();
        Map<String, Object> headers = properties.getHeaders() == null ? Map.of() : properties.getHeaders();
        return new DeadLetter(
                properties.getMessageId(),
                properties.getType(),
                header(headers, MessagingTopology.SUBJECT_HEADER),
                headers.get(MessagingTopology.SEQUENCE_HEADER) instanceof Number n ? n.longValue() : -1L,
                headers.get(MessagingTopology.ATTEMPT_HEADER) instanceof Number n ? n.intValue() : 0,
                headers.containsKey(MessagingTopology.DEAD_LETTER_REASON_HEADER)
                        ? String.valueOf(headers.get(MessagingTopology.DEAD_LETTER_REASON_HEADER))
                        : "sin motivo registrado");
    }

    private static String header(Map<String, Object> headers, String name) {
        Object value = headers.get(name);
        return value == null ? "" : String.valueOf(value);
    }
}
