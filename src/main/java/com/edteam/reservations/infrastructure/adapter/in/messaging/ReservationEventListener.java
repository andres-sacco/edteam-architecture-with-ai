package com.edteam.reservations.infrastructure.adapter.in.messaging;

import com.edteam.reservations.application.exception.UnprocessableEventException;
import com.edteam.reservations.application.port.in.EventProcessingOutcome;
import com.edteam.reservations.application.port.in.InboundEvent;
import com.edteam.reservations.application.port.in.ProcessReservationEventUseCase;
import com.edteam.reservations.infrastructure.adapter.out.messaging.MessagingTopology;
import com.edteam.reservations.infrastructure.logging.LogFields;
import com.edteam.reservations.infrastructure.logging.LogSanitizer;
import com.edteam.reservations.infrastructure.logging.MdcTaskDecorator;
import com.edteam.reservations.infrastructure.logging.Throwables;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

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

    /**
     * Los tipos que este consumidor conoce, más el centinela.
     *
     * <p>Es la cota de cardinalidad de {@code type}. Sin esto, la etiqueta era
     * el header {@code type} crudo de AMQP —o sea, un valor que elige quien
     * publica— y 200 mensajes con tipos al azar producían 200 series en
     * Prometheus. Una bomba de cardinalidad disparable por un tercero, que
     * además explota justo cuando llega la avalancha y el monitoreo es lo
     * único que queda en pie.
     *
     * <p>Se duplica acá el conjunto que valida
     * {@code ProcessReservationEventService} en lugar de compartirlo: ese
     * vocabulario es de la aplicación y esta clase es el borde. Lo que impide
     * que se desalineen es el test de cardinalidad, no un import.
     */
    private static final Set<String> KNOWN_TYPES = Set.of(
            "reservation.created", "reservation.confirmed",
            "reservation.modified", "reservation.cancelled");

    /** Valor de la etiqueta para cualquier tipo fuera del vocabulario. */
    private static final String OTHER_TYPE = "other";

    /** El mismo formato que {@code CorrelationIdFilter} acepta del cliente. */
    private static final Pattern ACCEPTED_CORRELATION_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final ProcessReservationEventUseCase processEvent;
    private final InboundEnvelopeParser parser;
    private final RabbitTemplate rabbitTemplate;
    private final int maxAttempts;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final MeterRegistry registry;

    public ReservationEventListener(
            ProcessReservationEventUseCase processEvent,
            InboundEnvelopeParser parser,
            RabbitTemplate rabbitTemplate,
            int maxAttempts,
            Duration initialDelay,
            Duration maxDelay,
            MeterRegistry registry) {
        this.processEvent = Objects.requireNonNull(processEvent);
        this.parser = Objects.requireNonNull(parser);
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate);
        this.initialDelay = Objects.requireNonNull(initialDelay);
        this.maxDelay = Objects.requireNonNull(maxDelay);
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
        // El MDC se pone ANTES de parsear, desde las propiedades AMQP. El
        // hallazgo 12 de la auditoría: el registro más severo del consumidor
        // —el ERROR del mensaje ilegible— no podía llevar correlationId por
        // construcción, porque se escribía antes de tocar el MDC y el envelope
        // no se había podido parsear. La salida ya estaba ahí:
        // `message.getMessageProperties()` tiene el correlationId de AMQP
        // aunque el cuerpo sea basura, igual que `InboundEnvelopeParser` ya usa
        // el messageId y el type de las propiedades como respaldo.
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        String amqpCorrelationId =
                acceptedCorrelationId(message.getMessageProperties().getCorrelationId());
        if (amqpCorrelationId != null) {
            MDC.put(MDC_CORRELATION_ID, amqpCorrelationId);
        }

        InboundEvent event;
        try {
            event = parser.parse(message);
        } catch (UnprocessableEventException e) {
            // No se puede ni identificar: no hay messageId con el que
            // deduplicar ni reintentar con sentido.
            try {
                log.atError()
                        .addKeyValue(LogFields.EVENT, LogFields.CONSUMER_DEAD_LETTERED)
                        .addKeyValue(LogFields.REASON, "unreadable")
                        .addKeyValue(LogFields.ATTEMPT, attempt)
                        .addKeyValue(LogFields.EXCEPTION_CLASS, Throwables.classOf(e))
                        .addKeyValue("detail", Throwables.reasonOf(e))
                        .log("Mensaje ilegible: va a la dead letter del consumidor");
                toDeadLetter(message, attempt, "ilegible: " + e.getMessage());
            } finally {
                MdcTaskDecorator.restore(previousMdc);
            }
            return;
        }

        // El envelope es el contrato y manda sobre las propiedades AMQP, que
        // son su espejo. Recién acá se puede saber cuál es el id de verdad.
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
            log.atError()
                    .addKeyValue(LogFields.EVENT, LogFields.CONSUMER_DEAD_LETTERED)
                    .addKeyValue(LogFields.EVENT_TYPE, event.type())
                    .addKeyValue(LogFields.SUBJECT, event.subject())
                    .addKeyValue(LogFields.MESSAGE_ID, event.messageId())
                    .addKeyValue(LogFields.REASON, "unprocessable")
                    .addKeyValue(LogFields.ATTEMPT, attempt)
                    .addKeyValue(LogFields.EXCEPTION_CLASS, Throwables.classOf(e))
                    .addKeyValue("detail", Throwables.reasonOf(e))
                    .log("Mensaje no procesable: va a la dead letter del consumidor");
            toDeadLetter(message, attempt, e.getMessage());
        } catch (RuntimeException e) {
            // La CLASE de la excepción y no su `toString()`. El comentario que
            // estaba acá prometía «va sin el cuerpo y con los identificadores»
            // y la línea siguiente escribía el mensaje completo, que en un
            // error de JPA o de PostgreSQL trae los valores enlazados —o sea,
            // el payload que el comentario decía excluir (hallazgo 4)—. El
            // motivo va redactado; el detalle completo viaja al header de la
            // dead letter, que es nuestro broker y no el SaaS de logs.
            boolean lastRound = attempt + 1 >= maxAttempts;
            log.atWarn()
                    .addKeyValue(LogFields.EVENT, LogFields.CONSUMER_RETRY)
                    .addKeyValue(LogFields.EVENT_TYPE, event.type())
                    .addKeyValue(LogFields.SUBJECT, event.subject())
                    .addKeyValue(LogFields.MESSAGE_ID, event.messageId())
                    .addKeyValue(LogFields.ATTEMPT, attempt + 1)
                    .addKeyValue(LogFields.MAX_ATTEMPTS, maxAttempts)
                    .addKeyValue(LogFields.OUTCOME, lastRound ? "exhausted" : "retrying")
                    .addKeyValue(LogFields.EXCEPTION_CLASS, Throwables.rootClassOf(e))
                    .addKeyValue(LogFields.REASON, Throwables.reasonOf(e))
                    .log("Fallo transitorio procesando el mensaje");
            if (lastRound) {
                log.atError()
                        .addKeyValue(LogFields.EVENT, LogFields.CONSUMER_DEAD_LETTERED)
                        .addKeyValue(LogFields.EVENT_TYPE, event.type())
                        .addKeyValue(LogFields.SUBJECT, event.subject())
                        .addKeyValue(LogFields.MESSAGE_ID, event.messageId())
                        .addKeyValue(LogFields.REASON, "attempts_exhausted")
                        .addKeyValue(LogFields.MAX_ATTEMPTS, maxAttempts)
                        .addKeyValue(LogFields.EXCEPTION_CLASS, Throwables.rootClassOf(e))
                        .log("Agotadas las vueltas de reintento: va a la dead letter del consumidor");
                toDeadLetter(message, attempt, "agotó las %d vueltas de reintento: %s".formatted(maxAttempts, e));
            } else {
                toRetry(message, attempt);
            }
        } finally {
            // Se restituye el MDC anterior en vez de borrar la clave: el
            // contenedor reusa el hilo entre mensajes, y con `remove` la
            // correlación del siguiente dependía de que su envelope trajera la
            // suya. Mismo motivo que en el relay (hallazgo 10).
            MdcTaskDecorator.restore(previousMdc);
        }
    }

    /**
     * El correlation id de las propiedades AMQP, si cumple el formato.
     *
     * <p>Se valida con el mismo patrón que {@code CorrelationIdFilter} aplica
     * al header del cliente, y por la misma razón: el valor termina en el MDC y
     * de ahí en cada línea de log, así que quien publique en la cola estaría
     * escribiendo en nuestros logs. Un valor que no cumple se descarta en
     * silencio; el mensaje se procesa igual, sólo que sin id.
     */
    private static String acceptedCorrelationId(String claimed) {
        return claimed != null && ACCEPTED_CORRELATION_ID.matcher(claimed).matches() ? claimed : null;
    }

    /**
     * A la cola de espera. Al vencer su TTL sale por su DLX hacia el exchange
     * de reinyección, que está atado a la cola principal.
     */
    private void toRetry(Message message, int attempt) {
        MessageProperties properties = message.getMessageProperties();
        properties.setHeader(MessagingTopology.ATTEMPT_HEADER, attempt + 1);
        // La espera va por vencimiento del mensaje y no por TTL fija de la
        // cola, y eso arregla dos cosas a la vez.
        //
        // Crece: con una espera fija de 30 s, cinco vueltas son 150 s y la
        // caída de un consumidor de más de dos minutos y medio vacía la cola
        // hacia la dead letter en bloque. Con backoff exponencial, las mismas
        // cinco vueltas cubren minutos.
        //
        // Y tiene jitter: sin él, todos los mensajes que fallaron juntos
        // vuelven JUNTOS, exactamente 30 s después, contra un destino que
        // sigue caído. El sorteo sobre el cuarto superior los reparte sin
        // que ninguno espere mucho más de lo que le toca — que es lo que
        // acota el bloqueo de cabeza de cola propio de una sola cola de
        // espera.
        properties.setExpiration(String.valueOf(retryDelay(attempt).toMillis()));
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
        // Sin esto, el mensaje llega a la dead letter con el vencimiento de su
        // última vuelta de reintento y desaparece de ahí solo: la dead letter
        // es para inspeccionar y reenviar, no para caducar.
        properties.setExpiration(null);
        rabbitTemplate.send(MessagingTopology.DLQ_EXCHANGE, "", message);
        count(DEAD_LETTERED, properties.getType(), "consumer");
    }

    /**
     * Espera antes de la vuelta {@code attempt + 1}: exponencial con techo y
     * con jitter sobre el último cuarto.
     */
    Duration retryDelay(int attempt) {
        long millis = initialDelay.toMillis();
        for (int i = 0; i < attempt && millis < maxDelay.toMillis(); i++) {
            millis *= 2;
        }
        long capped = Math.min(millis, maxDelay.toMillis());
        long floor = Math.max(1L, capped * 3 / 4);
        return Duration.ofMillis(floor + ThreadLocalRandom.current().nextLong(capped - floor + 1));
    }

    private static int attemptOf(Message message) {
        Object header = message.getMessageProperties().getHeaders().get(MessagingTopology.ATTEMPT_HEADER);
        return header instanceof Number number ? number.intValue() : 0;
    }

    /**
     * Incrementa un contador con el tipo <b>validado</b>.
     *
     * <p>{@link #boundedType(String)} es lo que impide que la etiqueta la elija
     * quien publica. Se aplica en los tres contadores y no sólo en el de la
     * dead letter: el camino del duplicado reclama el mensaje
     * ({@code ProcessReservationEventService}) <b>antes</b> de validar contra
     * el vocabulario, así que `consumed` compartía el mismo riesgo por otra
     * puerta.
     */
    private void count(String metric, String type, String result) {
        Counter.builder(metric)
                .tags(Tags.of("type", boundedType(type), "result", result))
                .register(registry)
                .increment();
    }

    /** El tipo si está en el vocabulario; {@code other} si no. */
    private static String boundedType(String type) {
        if (type == null || type.isBlank()) {
            return OTHER_TYPE;
        }
        String normalized = LogSanitizer.sanitize(type, 64).toLowerCase(Locale.ROOT);
        return KNOWN_TYPES.contains(normalized) ? normalized : OTHER_TYPE;
    }

    /** El header no es el lugar para un stack trace. */
    private static String truncate(String reason) {
        if (reason == null) {
            return "sin motivo";
        }
        return reason.length() <= 300 ? reason : reason.substring(0, 297) + "...";
    }
}
