package com.edteam.reservations.infrastructure.adapter.in.messaging;

import ch.qos.logback.classic.Level;
import com.edteam.reservations.application.exception.UnprocessableEventException;
import com.edteam.reservations.application.port.in.EventProcessingOutcome;
import com.edteam.reservations.application.port.in.InboundEvent;
import com.edteam.reservations.application.port.in.ProcessReservationEventUseCase;
import com.edteam.reservations.infrastructure.logging.LogFields;
import com.edteam.reservations.support.ForbiddenPatterns;
import com.edteam.reservations.support.LogCapture;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Los tres hallazgos del consumidor: el ERROR sin correlación, el
 * {@code toString()} de la excepción y la bomba de cardinalidad.
 */
@DisplayName("Observabilidad del consumidor")
class ReservationEventListenerObservabilityTest {

    private static final String KNOWN_CORRELATION_ID = "audit-0000-0001";

    private ProcessReservationEventUseCase useCase;
    private MeterRegistry registry;
    private ReservationEventListener listener;
    private LogCapture logs;

    @BeforeEach
    void setUp() {
        useCase = mock(ProcessReservationEventUseCase.class);
        registry = new SimpleMeterRegistry();
        listener = new ReservationEventListener(
                useCase, new InboundEnvelopeParser(new ObjectMapper()),
                mock(RabbitTemplate.class), 3,
                Duration.ofSeconds(1), Duration.ofSeconds(10), registry);
        logs = LogCapture.startAt(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logs.close();
    }

    @Test
    @DisplayName("el ERROR del mensaje ilegible lleva el correlationId de las propiedades AMQP")
    void theUnreadableMessageErrorCarriesTheAmqpCorrelationId() {
        // El hallazgo 12: el registro MÁS SEVERO del consumidor no podía llevar
        // correlationId por construcción, porque se escribía antes de tocar el
        // MDC y el envelope no se había podido parsear. La salida ya estaba
        // ahí: las propiedades AMQP lo traen aunque el cuerpo sea basura, y el
        // parser ya las usa como respaldo para el messageId y el type.
        MessageProperties properties = new MessageProperties();
        properties.setCorrelationId(KNOWN_CORRELATION_ID);
        listener.onMessage(new Message("esto no es JSON".getBytes(StandardCharsets.UTF_8), properties));

        List<LogCapture.Captured> dead = logs.withEvent(LogFields.CONSUMER_DEAD_LETTERED);
        assertThat(dead).hasSize(1);
        assertThat(dead.get(0).level()).isEqualTo("ERROR");
        assertThat(dead.get(0).mdc(LogFields.CORRELATION_ID))
                .withFailMessage("El ERROR del mensaje ilegible volvió a salir sin correlationId")
                .isEqualTo(KNOWN_CORRELATION_ID);
    }

    @Test
    @DisplayName("un correlationId de AMQP que no cumple el formato se descarta")
    void aMalformedAmqpCorrelationIdIsIgnored() {
        // El valor lo elige quien publica en la cola, igual que el header lo
        // elige el cliente HTTP: se valida con el mismo patrón y por el mismo
        // motivo, porque termina en el MDC y de ahí en cada línea de log.
        MessageProperties properties = new MessageProperties();
        properties.setCorrelationId("x\ny");
        listener.onMessage(new Message("basura".getBytes(StandardCharsets.UTF_8), properties));

        assertThat(logs.withEvent(LogFields.CONSUMER_DEAD_LETTERED))
                .allSatisfy(captured ->
                        assertThat(captured.mdc(LogFields.CORRELATION_ID)).isNull());
    }

    @Test
    @DisplayName("el MDC queda como estaba después de procesar: el contenedor reusa el hilo")
    void theMdcIsRestoredAfterEachMessage() {
        org.slf4j.MDC.put(LogFields.CORRELATION_ID, "id-anterior");
        try {
            MessageProperties properties = new MessageProperties();
            properties.setCorrelationId(KNOWN_CORRELATION_ID);
            listener.onMessage(new Message("basura".getBytes(StandardCharsets.UTF_8), properties));

            assertThat(org.slf4j.MDC.get(LogFields.CORRELATION_ID)).isEqualTo("id-anterior");
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    @Test
    @DisplayName("un fallo transitorio escribe la clase de la excepción y no su mensaje")
    void aTransientFailureDoesNotLogTheExceptionMessage() {
        // El hallazgo 4: el comentario de la línea prometía «va sin el cuerpo y
        // con los identificadores» y la línea siguiente escribía `e.toString()`
        // entero, que en un error de JPA o de PostgreSQL trae los valores
        // enlazados — o sea, el payload que el comentario decía excluir.
        when(useCase.process(any(InboundEvent.class))).thenThrow(
                new IllegalStateException("no se pudo insertar Detail: Key (email)=(ana.perez@example.com)"));

        listener.onMessage(envelope("reservation.created"));

        assertThat(logs.withEvent(LogFields.CONSUMER_RETRY))
                .hasSize(1)
                .allSatisfy(captured -> {
                    assertThat(captured.field(LogFields.EXCEPTION_CLASS)).isEqualTo("IllegalStateException");
                    assertThat(ForbiddenPatterns.firstMatch(captured.allText()))
                            .withFailMessage("El fallo transitorio del consumidor volvió a filtrar un dato")
                            .isEmpty();
                });
    }

    @Test
    @DisplayName("la etiqueta 'type' está acotada aunque el que publica elija cualquier cosa")
    void theTypeTagIsBounded() {
        // El hallazgo 16: `reservations.messaging.dead-lettered` se etiquetaba
        // con el header `type` crudo de AMQP. Series ilimitadas en Prometheus a
        // pedido de cualquiera que pueda publicar en la cola, y el monitoreo se
        // cae justo cuando llega la avalancha.
        for (int i = 0; i < 200; i++) {
            MessageProperties properties = new MessageProperties();
            properties.setType("tipo-" + ThreadLocalRandom.current().nextLong());
            listener.onMessage(new Message("basura".getBytes(StandardCharsets.UTF_8), properties));
        }

        Set<String> types = registry.find(ReservationEventListener.DEAD_LETTERED).counters().stream()
                .map(counter -> counter.getId().getTag("type"))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(types)
                .withFailMessage("200 mensajes con tipo al azar crearon %d series: "
                        + "la etiqueta la elige quien publica", types.size())
                .containsExactly("other");
    }

    @Test
    @DisplayName("un tipo conocido conserva su valor: la cota no borra la información útil")
    void aKnownTypeKeepsItsValue() {
        when(useCase.process(any(InboundEvent.class))).thenReturn(EventProcessingOutcome.APPLIED);

        listener.onMessage(envelope("reservation.created"));

        assertThat(registry.find(ReservationEventListener.CONSUMED).counters())
                .extracting(counter -> counter.getId().getTag("type"))
                .containsExactly("reservation.created");
    }

    @Test
    @DisplayName("el camino del duplicado también pasa por la cota")
    void theDuplicatePathIsBoundedToo() {
        // `reservations.messaging.consumed` compartía el riesgo por otra
        // puerta: el reclamo del deduplicador corre ANTES de la validación
        // contra el vocabulario, así que un tipo desconocido que resultara
        // duplicado llegaba igual a la etiqueta.
        when(useCase.process(any(InboundEvent.class))).thenReturn(EventProcessingOutcome.DUPLICATE);

        listener.onMessage(envelope("tipo.inventado.por.un.tercero"));

        assertThat(registry.find(ReservationEventListener.CONSUMED).counters())
                .extracting(counter -> counter.getId().getTag("type"))
                .containsExactly("other");
    }

    /** Un envelope mínimo y válido, con el tipo que se quiera probar. */
    private static Message envelope(String type) {
        String body = """
                {
                  "messageId": "0b4f1c2e-5b11-4f0d-9a3e-77c0c1d4e210",
                  "type": "%s",
                  "version": 1,
                  "source": "flight-reservations",
                  "subject": "10241",
                  "sequence": 7,
                  "occurredAt": "2026-09-24T14:03:11.402Z",
                  "correlationId": "%s",
                  "data": {"userId": "4471"}
                }
                """.formatted(type, KNOWN_CORRELATION_ID);
        MessageProperties properties = new MessageProperties();
        properties.setType(type);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}
