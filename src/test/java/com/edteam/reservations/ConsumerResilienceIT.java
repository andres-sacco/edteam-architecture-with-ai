package com.edteam.reservations;

import com.edteam.reservations.application.exception.UnprocessableEventException;
import com.edteam.reservations.application.port.in.EventProcessingOutcome;
import com.edteam.reservations.application.port.in.InboundEvent;
import com.edteam.reservations.application.port.in.ProcessReservationEventUseCase;
import com.edteam.reservations.infrastructure.adapter.out.messaging.DeadLetterQueue;
import com.edteam.reservations.infrastructure.adapter.out.messaging.MessagingTopology;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Qué pasa cuando el consumidor no puede procesar: reintentos acotados, dead
 * letter, y —lo que más importa— <b>nada de eso llega al usuario de la API</b>.
 *
 * <p>Los tres escenarios son los que el diseño promete y que hasta ahora no
 * tenían prueba: que un mensaje que agota sus intentos termine en la DLQ, que
 * el consumidor caído no afecte la latencia de la API, y que el productor no
 * espere al consumidor.
 */
@AutoConfigureMockMvc
@DisplayName("Resiliencia del consumo (PostgreSQL + RabbitMQ)")
class ConsumerResilienceIT extends AbstractRabbitIT {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.edteam.reservations.application.port.in.DispatchPendingNotificationsUseCase dispatchNotifications;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private DeadLetterQueue deadLetterQueue;

    /**
     * Espía sobre el caso de uso, no sobre el adaptador: lo que hace falta es
     * simular que el <em>procesamiento</em> falla —el proveedor de email dio
     * 503— sin tocar la maquinaria de transporte que se está probando.
     */
    @MockitoSpyBean
    private ProcessReservationEventUseCase processEvent;

    @BeforeEach
    void drainQueues() {
        rabbitAdmin.purgeQueue(MessagingTopology.CONSUMER_QUEUE, true);
        rabbitAdmin.purgeQueue(MessagingTopology.RETRY_QUEUE, true);
        rabbitAdmin.purgeQueue(MessagingTopology.DLQ, true);
    }

    // =================================================================
    // La cola de espera tiene cota
    // =================================================================

    @Test
    @DisplayName("la cola de espera declara cota y política de rebalse, igual que la principal")
    void theRetryQueueIsBounded() {
        // Sin cota, con el destino caído esta cola crece sin límite en el
        // broker y la alarma de disco de RabbitMQ termina frenando LAS
        // PUBLICACIONES DEL RELAY: la falta de un límite en una cola interna
        // se convierte en la caída del broker entero, y se lleva puesto el
        // camino que sí tenía protección. Con reject-publish el rebalse es
        // visible en lugar de silencioso.
        java.util.Properties properties = rabbitAdmin.getQueueProperties(MessagingTopology.RETRY_QUEUE);

        assertThat(properties).isNotNull();
        assertThat(properties.toString())
                .as("la cola declarada tiene que llevar x-max-length y x-overflow")
                .isNotEmpty();
        assertThat(queueArguments(MessagingTopology.RETRY_QUEUE))
                .containsKeys("x-max-length", "x-overflow", "x-message-ttl");
    }

    @Autowired
    private java.util.List<org.springframework.amqp.core.Queue> declaredQueues;

    private java.util.Map<String, Object> queueArguments(String queue) {
        return declaredQueues.stream()
                .filter(candidate -> candidate.getName().equals(queue))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No se declaró la cola " + queue))
                .getArguments();
    }

    // =================================================================
    // Reintentos acotados y dead letter
    // =================================================================

    /**
     * Un fallo transitorio se reintenta con espera —la TTL de la cola de
     * espera, no un {@code sleep} en el handler— y al agotar las vueltas
     * termina en la DLQ. No se reintenta para siempre y no se pierde.
     */
    @Test
    @DisplayName("un mensaje que agota sus vueltas de reintento termina en la DLQ")
    void aMessageThatExhaustsItsRetriesEndsUpInTheDeadLetterQueue() {
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            attempts.incrementAndGet();
            // Transitorio: el proveedor de email contestó 503.
            throw new IllegalStateException("el proveedor de email no responde");
        }).when(processEvent).process(any(InboundEvent.class));

        publishEvent("4242", "reservation.confirmed", 1L);

        Awaitility.await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(deadLetterQueue.depth()).isEqualTo(1L));

        // Se intentó más de una vez (el mecanismo de reintento funciona) y
        // terminó acotado por 'max-retry-rounds' (2 en los tests).
        assertThat(attempts.get()).isBetween(2, 3);
        assertThat(deadLetterQueue.peek(5)).singleElement().satisfies(dead -> {
            assertThat(dead.reason()).contains("agotó");
            assertThat(dead.subject()).isEqualTo("4242");
        });
        // La cola principal quedó limpia: el mensaje venenoso no bloquea al resto.
        assertThat(messagesIn(MessagingTopology.CONSUMER_QUEUE)).isZero();
    }

    /**
     * La distinción que el diseño promete: lo permanente no gasta reintentos.
     * Insistir con un payload que no cumple el esquema da el mismo resultado
     * cinco veces.
     */
    @Test
    @DisplayName("un fallo permanente va a la DLQ en el primer intento, sin reintentos")
    void aPermanentFailureGoesStraightToTheDeadLetterQueue() {
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            attempts.incrementAndGet();
            throw new UnprocessableEventException("el payload no cumple el esquema");
        }).when(processEvent).process(any(InboundEvent.class));

        publishEvent("4343", "reservation.confirmed", 1L);

        Awaitility.await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(deadLetterQueue.depth()).isEqualTo(1L));

        assertThat(attempts.get()).isEqualTo(1);
    }

    /** Un mensaje venenoso no puede frenar a los que venían detrás. */
    @Test
    @DisplayName("un mensaje venenoso no bloquea a los sanos que van detrás")
    void aPoisonMessageDoesNotBlockTheHealthyOnesBehindIt() {
        doAnswer(invocation -> {
            InboundEvent event = invocation.getArgument(0, InboundEvent.class);
            if (event.subject().equals("6666")) {
                throw new UnprocessableEventException("mensaje venenoso");
            }
            return invocation.callRealMethod();
        }).when(processEvent).process(any(InboundEvent.class));

        publishEvent("6666", "reservation.confirmed", 1L);
        publishEvent("7777", "reservation.confirmed", 2L);

        // El sano se procesa sin esperar a que el venenoso se resuelva: la
        // espera del reintento vive en la cola, no en el handler.
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(deliveriesFor("7777")).isEqualTo(1L);
            assertThat(deadLetterQueue.depth()).isEqualTo(1L);
        });
    }

    // =================================================================
    // El consumidor caído no afecta a la API
    // =================================================================

    /**
     * La propiedad que justifica toda la asincronía: <b>el productor no espera
     * al consumidor</b>. Con el consumidor rechazando todo, la API sigue
     * respondiendo con la misma latencia y el mismo resultado.
     */
    @Test
    @DisplayName("la caída del consumidor no afecta la latencia ni el resultado de la API")
    void aBrokenConsumerDoesNotAffectTheApi() throws Exception {
        // Consumidor caído: todo lo que reciba falla.
        doAnswer(invocation -> {
            throw new IllegalStateException("el consumidor está caído");
        }).when(processEvent).process(any(InboundEvent.class));

        long slowest = 0L;
        for (int i = 0; i < 10; i++) {
            long startedAt = System.nanoTime();
            String id = createReservationOverHttp();
            mockMvc.perform(get("/v1/reservations/{id}", id).with(com.edteam.reservations.support.SecurityTestSupport.asOwner()))
                    .andExpect(status().isOk());
            slowest = Math.max(slowest, (System.nanoTime() - startedAt) / 1_000_000);
        }

        // Diez altas y diez lecturas con el consumidor entero caído. El umbral
        // es holgado a propósito —esto corre en CI— y lo que verifica es que no
        // haya NINGUNA espera del orden del reintento del consumidor (segundos)
        // en la ruta del usuario.
        assertThat(slowest)
                .as("el pedido más lento de la API con el consumidor caído")
                .isLessThan(2_000L);
        assertThat(countRows("reserva")).isEqualTo(10L);
    }

    @Test
    @DisplayName("con el consumidor caído los hechos se despachan igual: el productor no lo espera")
    void theProducerDoesNotWaitForTheConsumer() throws Exception {
        doAnswer(invocation -> {
            throw new IllegalStateException("el consumidor está caído");
        }).when(processEvent).process(any(InboundEvent.class));

        createReservationOverHttp();
        // El relay se dispara a mano: en los tests el scheduler está apagado
        // para que el despacho no compita con las aserciones.
        assertThat(dispatchNotifications.dispatchPending(50).dispatched()).isEqualTo(1);

        // El relay publica y marca DISPATCHED: su trabajo termina con el ack
        // del BROKER, no con el procesamiento del consumidor. Que el consumidor
        // no pueda procesar es su problema, y lo resuelve su DLQ.
        Awaitility.await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(countOutbox("DISPATCHED")).isEqualTo(1L));
        assertThat(countOutbox("FAILED")).isZero();

        // Y el hecho no se pierde: termina en la dead letter del consumidor,
        // que es de donde se reprocesa después de arreglar la causa.
        Awaitility.await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(deadLetterQueue.depth()).isEqualTo(1L));
    }

    @Test
    @DisplayName("el consumidor sano deja su efecto y confirma")
    void aHealthyConsumerAppliesTheEvent() {
        publishEvent("8888", "reservation.created", 1L);

        Awaitility.await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(deliveriesFor("8888")).isEqualTo(1L));
        assertThat(processEvent.process(new InboundEvent(
                UUID.randomUUID().toString(), "reservation.created", 1, "urn:test",
                "8888", 2L, "317", Instant.now(), null)))
                .isEqualTo(EventProcessingOutcome.APPLIED);
    }

    // -----------------------------------------------------------------
    // Utilidades
    // -----------------------------------------------------------------

    private String createReservationOverHttp() throws Exception {
        Instant departure = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        String body = """
                {"itinerary":{"price":1250.50,"currency":"USD","segments":[
                   {"originAirportCode":"EZE","destinationAirportCode":"SCL",
                    "airline":"%s","departureAt":"%s"}]},
                 "passengers":[{"firstName":"Ana","lastName":"Pérez",
                    "birthDate":"1990-05-20","documentNumber":"30123456"}]}
                """.formatted(TestFixtures.AIRLINE, departure);

        String location = mockMvc.perform(post("/v1/reservations")
                        .with(com.edteam.reservations.support.SecurityTestSupport.asOwner())
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        return location == null ? "" : location.substring(location.lastIndexOf('/') + 1);
    }

    private long deliveriesFor(String reservationId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notificacion_entrega WHERE reserva_id = ?", Long.class, reservationId);
        return count == null ? 0L : count;
    }

    private long messagesIn(String queue) {
        java.util.Properties properties = rabbitAdmin.getQueueProperties(queue);
        Object count = properties == null ? null : properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
        return count == null ? 0L : ((Number) count).longValue();
    }

    private void publishEvent(String subject, String type, long sequence) {
        String body = """
                {"messageId":"%s","type":"%s","version":1,"source":"urn:edteam:flight-reservations",
                 "subject":"%s","sequence":%d,"occurredAt":"%s",
                 "data":{"reservationId":"%s","userId":"317"}}
                """.formatted(UUID.randomUUID(), type, subject, sequence, Instant.now(), subject);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setHeader(MessagingTopology.SUBJECT_HEADER, subject);
        rabbitTemplate.send(MessagingTopology.EVENTS_EXCHANGE, type,
                new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }
}
