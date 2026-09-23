package com.edteam.reservations.infrastructure.adapter.out.messaging;

import com.edteam.reservations.application.exception.EventPublishException;
import com.edteam.reservations.application.outbox.OutboxMessage;
import com.edteam.reservations.application.port.out.EventPublisherPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publica los hechos al topic exchange de RabbitMQ.
 *
 * <h2>{@code DISPATCHED} sólo con el ack del broker</h2>
 * El {@code convertAndSend} de AMQP es <em>fire and forget</em>: devuelve en
 * cuanto los bytes salieron del socket, mucho antes de que el broker los haya
 * escrito. Marcar el mensaje como despachado ahí sería mentir, y la mentira
 * sólo se descubre cuando el mensaje ya no está en el outbox para reintentarlo.
 * Acá se espera el <em>publisher confirm</em> y se falla si no llega en
 * {@code confirm-timeout}.
 *
 * <h2>Un mensaje no ruteable es un fallo, no un descarte</h2>
 * Se publica con {@code mandatory=true}. Un exchange sin ninguna cola atada
 * descarta lo que recibe <b>en silencio</b>: sin esto, un binding faltante o
 * mal escrito se ve exactamente igual que un sistema sano, y las notificaciones
 * se pierden de a miles sin una sola línea de error. Con {@code mandatory} el
 * broker devuelve el mensaje y el confirm llega negativo.
 *
 * <h2>Qué es transitorio y qué no</h2>
 * La clasificación se hace acá y no en el despachador, porque el único que sabe
 * si el {@code 503} es del broker o si el payload no serializa es quien habla
 * con el broker:
 * <ul>
 *   <li>{@link AmqpException}, timeout del confirm, confirm negativo →
 *       {@link EventPublishException}: <b>transitorio</b>, se reintenta con
 *       backoff;</li>
 *   <li>payload que no se puede leer → {@link IllegalStateException}:
 *       <b>permanente</b>, va a la dead letter en el primer intento. Insistir
 *       con un payload roto quema el despachador sin cambiar el resultado.</li>
 * </ul>
 *
 * <p>Los timeouts son <b>de este proveedor</b> y no globales, igual que los del
 * catálogo de ciudades: el tiempo que tolera un confirm de RabbitMQ no tiene
 * por qué ser el del próximo servicio que se integre.
 */
public class RabbitEventPublisher implements EventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(RabbitEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String exchange;
    private final String source;
    private final Duration confirmTimeout;
    private final Clock clock;

    public RabbitEventPublisher(RabbitTemplate rabbitTemplate,
                                ObjectMapper objectMapper,
                                String exchange,
                                String source,
                                Duration confirmTimeout,
                                Clock clock) {
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.exchange = Objects.requireNonNull(exchange);
        this.source = Objects.requireNonNull(source);
        this.confirmTimeout = Objects.requireNonNull(confirmTimeout);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void publish(OutboxMessage message) {
        Objects.requireNonNull(message, "El mensaje es obligatorio");

        byte[] body = body(message);
        CorrelationData confirm = new CorrelationData(message.id());

        try {
            // La routing key ES el tipo del evento: no hay traducción que
            // mantener entre el nombre del hecho y el del transporte.
            rabbitTemplate.send(exchange, message.type(), amqpMessage(message, body), confirm);
        } catch (AmqpException e) {
            throw new EventPublishException(
                    "El broker no aceptó %s (mensaje %s): %s".formatted(message.type(), message.id(), e.getMessage()), e);
        }

        awaitConfirm(message, confirm);

        // INFO lleva lo que hace falta para operar y no identifica a nadie
        // fuera de nuestra base. El payload —ruta y fecha de viaje— va a DEBUG.
        log.info("[mensajería] publicado type={} subject={} messageId={} sequence={}",
                message.type(), message.subject(), message.id(), message.sequence());
        log.debug("[mensajería] messageId={} payload={}", message.id(), message.payload());
    }

    /**
     * Espera la confirmación del broker.
     *
     * <p>Un confirm negativo llega en dos casos y los dos son transitorios: la
     * cola rechazó la publicación por estar llena ({@code reject-publish}, que
     * es <em>backpressure</em> y no pérdida: la fila se acumula en el outbox,
     * donde se puede ver y drenar) o el mensaje no era ruteable.
     */
    private void awaitConfirm(OutboxMessage message, CorrelationData confirm) {
        CorrelationData.Confirm ack;
        try {
            ack = confirm.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new EventPublishException(
                    "El broker no confirmó el mensaje %s en %d ms".formatted(message.id(), confirmTimeout.toMillis()), e);
        } catch (ExecutionException e) {
            throw new EventPublishException(
                    "Falló la confirmación del mensaje %s: %s".formatted(message.id(), e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException("Interrumpido esperando la confirmación de " + message.id(), e);
        }

        if (ack == null || !ack.isAck()) {
            throw new EventPublishException(
                    ("El broker rechazó el mensaje %s (%s): con 'mandatory' un mensaje sin binding es un error, "
                            + "no un descarte silencioso")
                            .formatted(message.id(), ack == null ? "sin respuesta" : ack.getReason()));
        }
        if (confirm.getReturned() != null) {
            throw new EventPublishException(
                    ("El exchange '%s' devolvió el mensaje %s con routing key '%s': no hay ninguna cola atada. "
                            + "Se reintenta; hay que revisar los bindings.")
                            .formatted(exchange, message.id(), message.type()));
        }
    }

    /**
     * Las propiedades AMQP espejan el envelope.
     *
     * <p>No son el contrato —el cuerpo lo es— sino una comodidad del
     * transporte: la consola del broker y el deduplicador del consumidor leen
     * el {@code messageId} sin parsear el cuerpo.
     */
    private org.springframework.amqp.core.Message amqpMessage(OutboxMessage message, byte[] body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setMessageId(message.id());
        properties.setType(message.type());
        properties.setTimestamp(Date.from(message.occurredAt()));
        if (message.correlationId() != null) {
            properties.setCorrelationId(message.correlationId());
        }
        properties.setHeader(MessagingTopology.SUBJECT_HEADER, message.subject());
        properties.setHeader(MessagingTopology.SCHEMA_VERSION_HEADER, message.schemaVersion());
        properties.setHeader(MessagingTopology.SEQUENCE_HEADER, message.sequence());
        // Persistente: una cola durable con mensajes transitorios pierde todo
        // en el reinicio del broker, que es exactamente lo que el outbox está
        // tratando de evitar.
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return new org.springframework.amqp.core.Message(body, properties);
    }

    private byte[] body(OutboxMessage message) {
        try {
            EventEnvelope envelope = EventEnvelope.from(
                    message, source, clock.instant(), objectMapper.readTree(message.payload()));
            return objectMapper.writeValueAsBytes(envelope);
        } catch (JsonProcessingException e) {
            // Permanente: el payload guardado no es JSON válido. Reintentarlo
            // da el mismo resultado, así que el despachador lo manda a la dead
            // letter en el primer intento en lugar de quemar el tope.
            throw new IllegalStateException(
                    "El payload guardado del mensaje %s no es JSON válido".formatted(message.id()), e);
        }
    }
}
