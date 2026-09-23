package com.edteam.reservations;

import com.edteam.reservations.application.outbox.OutboxDispatchResult;
import com.edteam.reservations.application.port.in.CreateReservationCommand;
import com.edteam.reservations.application.port.in.CreateReservationUseCase;
import com.edteam.reservations.application.port.in.DispatchPendingNotificationsUseCase;
import com.edteam.reservations.application.port.in.ItineraryData;
import com.edteam.reservations.application.port.out.EventPublisherPort;
import com.edteam.reservations.infrastructure.adapter.out.messaging.DeadLetterQueue;
import com.edteam.reservations.infrastructure.adapter.out.messaging.MessagingTopology;
import com.edteam.reservations.infrastructure.adapter.out.messaging.RabbitEventPublisher;
import com.edteam.reservations.support.AbstractRabbitIT;
import com.edteam.reservations.support.TestFixtures;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El circuito completo contra un broker real: alta → outbox → exchange → cola
 * → consumidor → efecto.
 *
 * <p>Los unitarios prueban cada pieza con dobles; lo que falta verificar es
 * que el conjunto funcione con el transporte de verdad, incluidos los
 * comportamientos del broker que no se pueden simular: el confirm, el
 * dead-lettering y la TTL que devuelve el mensaje.
 */
@DisplayName("Circuito de mensajería (PostgreSQL + RabbitMQ)")
class MessagingFlowIT extends AbstractRabbitIT {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private CreateReservationUseCase createReservation;

    @Autowired
    private DispatchPendingNotificationsUseCase dispatchNotifications;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private DeadLetterQueue deadLetterQueue;

    @BeforeEach
    void drainQueues() {
        // Las colas son durables y el contexto se comparte entre los tests: sin
        // esto, un mensaje de un test se procesa en el siguiente. Es el
        // equivalente al TRUNCATE de la base.
        rabbitAdmin.purgeQueue(MessagingTopology.CONSUMER_QUEUE, true);
        rabbitAdmin.purgeQueue(MessagingTopology.RETRY_QUEUE, true);
        rabbitAdmin.purgeQueue(MessagingTopology.DLQ, true);
    }

    private CreateReservationCommand createCommand() {
        Instant departure = Instant.now().plus(Duration.ofDays(30));
        return new CreateReservationCommand(
                TestFixtures.owner(),
                UUID.randomUUID().toString(),
                new ItineraryData(new BigDecimal("1250.50"), "USD",
                        List.of(TestFixtures.segmentData(TestFixtures.EZE, TestFixtures.SCL, departure))),
                TestFixtures.passengerData());
    }

    private long deliveriesFor(String reservationId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notificacion_entrega WHERE reserva_id = ?", Long.class, reservationId);
        return count == null ? 0L : count;
    }

    private void awaitDeliveries(String reservationId, long expected) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(deliveriesFor(reservationId)).isEqualTo(expected));
    }

    // =================================================================
    // Camino feliz
    // =================================================================

    @Test
    @DisplayName("con broker se cablea el publicador real")
    void wiresTheRealPublisher() {
        assertThat(context.getBean(EventPublisherPort.class)).isInstanceOf(RabbitEventPublisher.class);
    }

    @Test
    @DisplayName("un alta llega al consumidor y deja su efecto")
    void anEventReachesTheConsumer() {
        String reservationId = createReservation.create(createCommand())
                .reservation().requireId().toString();

        OutboxDispatchResult result = dispatchNotifications.dispatchPending(50);

        // DISPATCHED sólo con el ack del broker: si el confirm no llegara, el
        // mensaje seguiría pendiente en lugar de darse por enviado.
        assertThat(result.dispatched()).isEqualTo(1);
        assertThat(countOutbox("DISPATCHED")).isEqualTo(1L);

        awaitDeliveries(reservationId, 1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT type FROM notificacion_entrega WHERE reserva_id = ?", String.class, reservationId))
                .isEqualTo("reservation.created");
    }

    @Test
    @DisplayName("el mensaje entregado no lleva datos sensibles")
    void theDeliveredMessageCarriesNoSensitiveData() {
        createReservation.create(createCommand());
        // Se apaga el consumidor para poder mirar el mensaje en la cola.
        rabbitAdmin.purgeQueue(MessagingTopology.CONSUMER_QUEUE, true);

        rabbitTemplate.convertAndSend(MessagingTopology.EVENTS_EXCHANGE, "reservation.created", "ping");
        dispatchNotifications.dispatchPending(50);

        // Lo publicado se valida contra la tabla, que es lo que se serializó.
        String payload = jdbcTemplate.queryForObject("SELECT payload::text FROM outbox_message", String.class);
        assertThat(payload)
                .doesNotContain(TestFixtures.USER_EMAIL)
                .doesNotContain("30123456");
    }

    // =================================================================
    // H3 / H12 — idempotencia de punta a punta
    // =================================================================

    /**
     * La prueba que cierra todo el paso: la misma entrega repetida no duplica
     * efectos. Se fuerza el escenario real —el relay publica dos veces el mismo
     * mensaje, porque perdió un ack— devolviendo la fila a pendiente.
     */
    @Test
    @DisplayName("H3: el mismo mensaje entregado dos veces deja un solo efecto")
    void processingTheSameMessageTwiceLeavesOneEffect() {
        String reservationId = createReservation.create(createCommand())
                .reservation().requireId().toString();

        dispatchNotifications.dispatchPending(50);
        awaitDeliveries(reservationId, 1L);

        // El relay lo republica: es exactamente lo que pasa cuando el ack del
        // broker se pierde y el mensaje vuelve a estar pendiente.
        jdbcTemplate.update("UPDATE outbox_message SET status = 'PENDING', next_attempt_at = " + NOW_UTC);
        assertThat(dispatchNotifications.dispatchPending(50).dispatched()).isEqualTo(1);

        // El messageId no cambia con el reenvío: es la PK de la fila. El
        // consumidor lo reconoce y confirma sin volver a procesar.
        Awaitility.await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(deliveriesFor(reservationId)).isEqualTo(1L));
    }

    // =================================================================
    // H2 — DLQ real
    // =================================================================

    /**
     * Un mensaje que no se puede procesar va a una cola de la que alguien se
     * entera, no a un estado mudo. Es lo que {@code OutboxStatus.FAILED}
     * prometía y no cumplía.
     */
    @Test
    @DisplayName("H2: un mensaje que el consumidor no puede procesar termina en la DLQ")
    void anUnprocessableMessageEndsUpInTheDeadLetterQueue() {
        // Tipo desconocido: un fallo permanente, no algo que reintentar.
        publishRaw("reservation.exploded", """
                {"messageId":"%s","type":"reservation.exploded","version":1,"subject":"777",
                 "sequence":1,"occurredAt":"%s","data":{"userId":"317"}}
                """.formatted(UUID.randomUUID(), Instant.now()));

        Awaitility.await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(deadLetterQueue.depth()).isEqualTo(1L));

        // Y se puede inspeccionar sin consumirla: el peek saca y devuelve el
        // mensaje, así que mirar la dead letter no puede vaciarla.
        List<DeadLetterQueue.DeadLetter> dead = deadLetterQueue.peek(10);
        assertThat(dead).singleElement().satisfies(message ->
                assertThat(message.reason()).contains("desconocido"));
        // El contador de la cola tarda unos milisegundos en reflejar el
        // reencolado, así que se espera en lugar de leerlo en el acto.
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(deadLetterQueue.depth()).isEqualTo(1L));
    }

    @Test
    @DisplayName("H2: la DLQ se reprocesa después de arreglar la causa, sin duplicar lo ya aplicado")
    void theDeadLetterQueueCanBeReplayed() {
        String reservationId = createReservation.create(createCommand())
                .reservation().requireId().toString();
        dispatchNotifications.dispatchPending(50);
        awaitDeliveries(reservationId, 1L);

        String messageId = jdbcTemplate.queryForObject(
                "SELECT message_id::text FROM notificacion_entrega", String.class);

        // Se mete a mano en la DLQ un mensaje YA aplicado, que es el caso
        // peligroso del replay: si no hubiera deduplicación, reprocesarlo
        // produciría un segundo aviso al usuario.
        publishToDlq(messageId, reservationId);
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(deadLetterQueue.depth()).isEqualTo(1L));

        assertThat(deadLetterQueue.replay(10)).isEqualTo(1);

        // Vuelve, se reconoce como duplicado y no deja un segundo efecto.
        Awaitility.await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(deadLetterQueue.depth()).isZero());
        Awaitility.await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(deliveriesFor(reservationId)).isEqualTo(1L));
    }

    // =================================================================
    // H8 — desorden
    // =================================================================

    @Test
    @DisplayName("H8: un evento que llega desordenado se aplica igual en lugar de descartarse")
    void outOfOrderEventsAreAppliedNotDiscarded() {
        String subject = "555";
        Instant now = Instant.now();

        // Primero el sequence 11, después el 10: es lo que produce el backoff
        // del relay y el ciclo de retry, por diseño.
        publishEvent(subject, "reservation.confirmed", 11L, now);
        publishEvent(subject, "reservation.created", 10L, now);

        // Los DOS efectos existen. Con la regla de descarte por sequence, el
        // alta se habría perdido con un ack y nadie se habría enterado.
        awaitDeliveries(subject, 2L);
        assertThat(jdbcTemplate.queryForList(
                "SELECT type FROM notificacion_entrega WHERE reserva_id = ? ORDER BY sequence",
                String.class, subject))
                .containsExactly("reservation.created", "reservation.confirmed");
    }

    // =================================================================
    // Un broker caído no tumba el servicio
    // =================================================================

    /**
     * El mensaje no ruteable tiene que ser un error visible y no un descarte
     * silencioso: un exchange sin colas atadas tira lo que recibe, y sin
     * {@code mandatory} eso se ve exactamente igual que un sistema sano.
     */
    @Test
    @DisplayName("un mensaje sin cola atada no se descarta en silencio: falla y se reintenta")
    void anUnroutableMessageFailsInsteadOfBeingDropped() {
        createReservation.create(createCommand());
        // Se rompe el binding: el exchange queda sin nadie escuchando
        // 'reservation.created'.
        rabbitAdmin.removeBinding(org.springframework.amqp.core.BindingBuilder
                .bind(new org.springframework.amqp.core.Queue(MessagingTopology.CONSUMER_QUEUE))
                .to(new org.springframework.amqp.core.TopicExchange(MessagingTopology.EVENTS_EXCHANGE))
                .with(MessagingTopology.CONSUMER_BINDING));
        try {
            OutboxDispatchResult result = dispatchNotifications.dispatchPending(50);

            assertThat(result.failed()).isEqualTo(1);
            // Sigue pendiente, con su intento contado y su backoff agendado.
            assertThat(countOutbox("PENDING")).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT last_error FROM outbox_message", String.class)).isNotBlank();
        } finally {
            rabbitAdmin.declareBinding(org.springframework.amqp.core.BindingBuilder
                    .bind(new org.springframework.amqp.core.Queue(MessagingTopology.CONSUMER_QUEUE))
                    .to(new org.springframework.amqp.core.TopicExchange(MessagingTopology.EVENTS_EXCHANGE))
                    .with(MessagingTopology.CONSUMER_BINDING));
        }
    }

    // -----------------------------------------------------------------
    // Publicación cruda, para armar escenarios que el productor no produce
    // -----------------------------------------------------------------

    private void publishEvent(String subject, String type, long sequence, Instant occurredAt) {
        publishRaw(type, """
                {"messageId":"%s","type":"%s","version":1,"source":"urn:edteam:flight-reservations",
                 "subject":"%s","sequence":%d,"occurredAt":"%s",
                 "data":{"reservationId":"%s","userId":"317"}}
                """.formatted(UUID.randomUUID(), type, subject, sequence, occurredAt, subject));
    }

    private void publishRaw(String routingKey, String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(MessagingTopology.EVENTS_EXCHANGE, routingKey,
                new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    private void publishToDlq(String messageId, String subject) {
        String body = """
                {"messageId":"%s","type":"reservation.created","version":1,
                 "subject":"%s","sequence":1,"occurredAt":"%s",
                 "data":{"reservationId":"%s","userId":"1"}}
                """.formatted(messageId, subject, Instant.now(), subject);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(messageId);
        properties.setType("reservation.created");
        properties.setHeader(MessagingTopology.SUBJECT_HEADER, subject);
        rabbitTemplate.send(MessagingTopology.DLQ_EXCHANGE, "",
                new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }
}
