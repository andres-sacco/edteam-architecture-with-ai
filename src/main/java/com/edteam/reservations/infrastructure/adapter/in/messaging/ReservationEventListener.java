package com.edteam.reservations.infrastructure.adapter.in.messaging;

import com.edteam.reservations.application.exception.UnprocessableEventException;
import com.edteam.reservations.application.port.in.EventProcessingOutcome;
import com.edteam.reservations.application.port.in.InboundEvent;
import com.edteam.reservations.application.port.in.ProcessReservationEventUseCase;
import com.edteam.reservations.infrastructure.adapter.out.messaging.MessagingTopology;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Objects;

/**
 * Adaptador de entrada: consume la cola de trabajo y delega en el caso de uso.
 *
 * <p><b>No tiene lógica de negocio.</b> Parsea el envelope, llama a
 * {@link ProcessReservationEventUseCase} y traduce el resultado a una decisión
 * de transporte: confirmar, mandar a esperar o mandar a la dead letter. Qué
 * significa cada resultado lo decide la aplicación; cómo se le dice eso al
 * broker, este adaptador. Es el mismo reparto que hay entre el controller REST
 * y sus casos de uso.
 *
 * <h2>El reintento es una sola operación, y por eso es atómico</h2>
 * El contenedor corre con <b>canal transaccionado</b>: la publicación a la cola
 * de espera y el {@code ack} del original se confirman juntas en el broker.
 * Republicar y después confirmar —dos operaciones— tiene una ventana: una caída
 * en el medio deja el mensaje en la cola de espera <em>y</em> sin confirmar, así
 * que la cola lo vuelve a entregar y el mensaje se <b>multiplica</b> en cada
 * vuelta. Con la transacción de canal esa ventana no existe.
 *
 * <p>Y aun si existiera, la deduplicación por {@code messageId} la contiene,
 * porque corre <b>antes</b> de la decisión de reintentar: es lo primero que
 * hace el caso de uso.
 *
 * <h2>El backoff no duerme el handler</h2>
 * La espera vive en la TTL de la cola de espera, no en un {@code sleep} acá
 * dentro: dormir en el handler ocupa el canal y frena los mensajes sanos que
 * venían detrás del venenoso.
 *
 * <h2>Transitorio y permanente se tratan distinto</h2>
 * {@link UnprocessableEventException} va directo a la dead letter: un payload
 * que no cumple el esquema o un tipo desconocido no se arreglan insistiendo.
 * Cualquier otro fallo se trata como transitorio y se reintenta con backoff
 * hasta el tope de vueltas.
 */
public class ReservationEventListener {

    private static final Logger log = LoggerFactory.getLogger(ReservationEventListener.class);

    public static final String CONSUMED = "reservations.messaging.consumed";
    public static final String OUT_OF_ORDER = "reservations.messaging.out-of-order";
    public static final String DEAD_LETTERED = "reservations.messaging.dead-lettered";

    private static final String MDC_CORRELATION_ID = "correlationId";

    private final ProcessReservationEventUseCase processEvent;
    private final InboundEnvelopeParser parser;
    private final RabbitTemplate rabbitTemplate;
    private final int maxAttempts;
    private final MeterRegistry registry;

    public ReservationEventListener(ProcessReservationEventUseCase processEvent,
                                    InboundEnvelopeParser parser,
                                    RabbitTemplate rabbitTemplate,
                                    int maxAttempts,
                                    MeterRegistry registry) {
        this.processEvent = Objects.requireNonNull(processEvent);
        this.parser = Objects.requireNonNull(parser);
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate);
        this.registry = Objects.requireNonNull(registry);
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts debe ser al menos 1");
        }
        this.maxAttempts = maxAttempts;
    }

    /**
     * Devolver normalmente es confirmar. Nunca se propaga una excepción: el
     * destino del mensaje se decide acá de forma explícita, y dejar que
     * burbujee delegaría esa decisión en la configuración del contenedor, donde
     * es invisible.
     */
    @RabbitListener(queues = MessagingTopology.CONSUMER_QUEUE, id = "reservation-events")
    public void onMessage(Message message) {
        int attempt = attemptOf(message);
        InboundEvent event;
        try {
            event = parser.parse(message);
        } catch (UnprocessableEventException e) {
            // No se puede ni identificar: no hay messageId con el que
            // deduplicar ni reintentar con sentido.
            log.error("[consumidor] mensaje ilegible, va a la DLQ: {}", e.getMessage());
            toDeadLetter(message, attempt, "ilegible: " + e.getMessage());
            return;
        }

        boolean correlated = event.correlationId() != null;
        if (correlated) {
            MDC.put(MDC_CORRELATION_ID, event.correlationId());
        }
        try {
            EventProcessingOutcome outcome = processEvent.process(event);
            count(CONSUMED, event.type(), outcome.name().toLowerCase(java.util.Locale.ROOT));
            if (outcome == EventProcessingOutcome.APPLIED_OUT_OF_ORDER) {
                count(OUT_OF_ORDER, event.type(), "applied");
            }
        } catch (UnprocessableEventException e) {
            log.error("[consumidor] type={} subject={} messageId={} no es procesable, va a la DLQ: {}",
                    event.type(), event.subject(), event.messageId(), e.getMessage());
            toDeadLetter(message, attempt, e.getMessage());
        } catch (RuntimeException e) {
            // El mensaje del error puede traer datos del payload: va sin el
            // cuerpo y con los identificadores, que es lo que hace falta.
            log.warn("[consumidor] fallo transitorio en type={} subject={} messageId={} (vuelta {}/{}): {}",
                    event.type(), event.subject(), event.messageId(), attempt + 1, maxAttempts, e.toString());
            if (attempt + 1 >= maxAttempts) {
                toDeadLetter(message, attempt, "agotó las %d vueltas de reintento: %s".formatted(maxAttempts, e));
            } else {
                toRetry(message, attempt);
            }
        } finally {
            if (correlated) {
                MDC.remove(MDC_CORRELATION_ID);
            }
        }
    }

    /**
     * A la cola de espera. Al vencer su TTL sale por su DLX hacia el exchange
     * de reinyección, que está atado a la cola principal.
     */
    private void toRetry(Message message, int attempt) {
        MessageProperties properties = message.getMessageProperties();
        properties.setHeader(MessagingTopology.ATTEMPT_HEADER, attempt + 1);
        // 'x-death' se saca antes de reinyectar: si queda, el
        // x-delivery-limit de la cola quorum cuenta las entregas de vueltas
        // anteriores y el mensaje muere antes de agotar sus reintentos.
        properties.getHeaders().remove("x-death");
        rabbitTemplate.send(MessagingTopology.RETRY_EXCHANGE, "", message);
    }

    private void toDeadLetter(Message message, int attempt, String reason) {
        MessageProperties properties = message.getMessageProperties();
        properties.setHeader(MessagingTopology.ATTEMPT_HEADER, attempt);
        properties.setHeader(MessagingTopology.DEAD_LETTER_REASON_HEADER, truncate(reason));
        properties.getHeaders().remove("x-death");
        rabbitTemplate.send(MessagingTopology.DLQ_EXCHANGE, "", message);
        count(DEAD_LETTERED, properties.getType() == null ? "desconocido" : properties.getType(), "consumer");
    }

    private static int attemptOf(Message message) {
        Object header = message.getMessageProperties().getHeaders().get(MessagingTopology.ATTEMPT_HEADER);
        return header instanceof Number number ? number.intValue() : 0;
    }

    private void count(String metric, String type, String result) {
        Counter.builder(metric).tags(Tags.of("type", type, "result", result)).register(registry).increment();
    }

    /** El header no es el lugar para un stack trace. */
    private static String truncate(String reason) {
        if (reason == null) {
            return "sin motivo";
        }
        return reason.length() <= 300 ? reason : reason.substring(0, 297) + "...";
    }
}
