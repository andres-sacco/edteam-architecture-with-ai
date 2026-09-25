package com.edteam.reservations.infrastructure.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.edteam.reservations.application.exception.UnprocessableEventException;
import com.edteam.reservations.application.port.in.InboundEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

@DisplayName("InboundEnvelopeParser")
class InboundEnvelopeParserTest {

    private static final String COMPLETE = """
            {
              "messageId": "0f7a6f2e-6b77-4a3a-9a5f-3c4a6b2f10d1",
              "type": "reservation.created",
              "version": 1,
              "source": "urn:edteam:flight-reservations",
              "subject": "8421",
              "sequence": 10493,
              "occurredAt": "2026-09-23T14:05:12.481Z",
              "publishedAt": "2026-09-23T14:05:14.902Z",
              "correlationId": "3f7c2b81-5a4e-4d62-9f31-2b0c8d5e7a14",
              "data": {
                "reservationId": "8421",
                "userId": "317",
                "passengerCount": 2,
                "itinerary": {
                  "origin": "EZE", "destination": "MAD",
                  "firstDeparture": "2026-11-12T23:40:00Z", "segmentCount": 2,
                  "price": { "amount": "1843.75", "currency": "USD" }
                }
              }
            }
            """;

    private final InboundEnvelopeParser parser = new InboundEnvelopeParser(new ObjectMapper());

    private static Message message(String body) {
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    @Test
    @DisplayName("lee el envelope completo")
    void parsesACompleteEnvelope() {
        InboundEvent event = parser.parse(message(COMPLETE));

        assertThat(event.messageId()).isEqualTo("0f7a6f2e-6b77-4a3a-9a5f-3c4a6b2f10d1");
        assertThat(event.type()).isEqualTo("reservation.created");
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.source()).isEqualTo("urn:edteam:flight-reservations");
        assertThat(event.subject()).isEqualTo("8421");
        assertThat(event.sequence()).isEqualTo(10_493L);
        assertThat(event.userId()).isEqualTo("317");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-09-23T14:05:12.481Z"));
        assertThat(event.correlationId()).isEqualTo("3f7c2b81-5a4e-4d62-9f31-2b0c8d5e7a14");
    }

    /**
     * Lector tolerante: es la regla que hace que agregar un campo al envelope o
     * al {@code data} sea un cambio compatible. Un consumidor estricto convierte
     * cada agregado del productor en una caída coordinada.
     */
    @Test
    @DisplayName("ignora los campos que no conoce")
    void ignoresUnknownFields() {
        String withExtras = COMPLETE.replace(
                "\"version\": 1,", "\"version\": 1, \"tenantId\": \"ar\", \"experimento\": { \"a\": 1 },");

        assertThat(parser.parse(message(withExtras)).type()).isEqualTo("reservation.created");
    }

    @Test
    @DisplayName("no falla por un campo opcional ausente")
    void toleratesMissingOptionalFields() {
        String withoutCorrelation =
                COMPLETE.replace("\"correlationId\": \"3f7c2b81-5a4e-4d62-9f31-2b0c8d5e7a14\",", "");

        assertThat(parser.parse(message(withoutCorrelation)).correlationId()).isNull();
    }

    @Test
    @DisplayName("cae a las propiedades AMQP cuando el cuerpo no trae messageId ni type")
    void fallsBackToAmqpProperties() {
        String minimal = """
                {"subject": "8421", "occurredAt": "2026-09-23T14:05:12.481Z",
                 "data": {"userId": "317"}}
                """;
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("desde-la-propiedad");
        properties.setType("reservation.cancelled");
        Message message = new Message(minimal.getBytes(StandardCharsets.UTF_8), properties);

        InboundEvent event = parser.parse(message);

        assertThat(event.messageId()).isEqualTo("desde-la-propiedad");
        assertThat(event.type()).isEqualTo("reservation.cancelled");
        // Sin 'version' en el cuerpo se asume 1: el contrato la agregó después.
        assertThat(event.schemaVersion()).isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // Lo que es un fallo permanente
    // -----------------------------------------------------------------

    @Test
    @DisplayName("un cuerpo que no es JSON no es procesable")
    void rejectsNonJsonBodies() {
        assertThatThrownBy(() -> parser.parse(message("esto no es json")))
                .isInstanceOf(UnprocessableEventException.class);
    }

    @Test
    @DisplayName("sin messageId no hay con qué deduplicar")
    void rejectsAMissingMessageId() {
        assertThatThrownBy(() -> parser.parse(
                        message(COMPLETE.replace("\"messageId\": \"0f7a6f2e-6b77-4a3a-9a5f-3c4a6b2f10d1\",", ""))))
                .isInstanceOf(UnprocessableEventException.class)
                .hasMessageContaining("messageId");
    }

    @Test
    @DisplayName("sin userId no hay a quién notificar")
    void rejectsAMissingUserId() {
        assertThatThrownBy(() -> parser.parse(message(COMPLETE.replace("\"userId\": \"317\",", ""))))
                .isInstanceOf(UnprocessableEventException.class)
                .hasMessageContaining("userId");
    }

    @Test
    @DisplayName("sin occurredAt no se puede decidir si el hecho todavía vale la pena")
    void rejectsAMissingOccurredAt() {
        assertThatThrownBy(() ->
                        parser.parse(message(COMPLETE.replace("\"occurredAt\": \"2026-09-23T14:05:12.481Z\",", ""))))
                .isInstanceOf(UnprocessableEventException.class)
                .hasMessageContaining("occurredAt");
    }

    @Test
    @DisplayName("un occurredAt que no es RFC 3339 no es procesable")
    void rejectsAnInvalidOccurredAt() {
        assertThatThrownBy(() -> parser.parse(message(COMPLETE.replace("2026-09-23T14:05:12.481Z", "23/09/2026"))))
                .isInstanceOf(UnprocessableEventException.class)
                .hasMessageContaining("RFC 3339");
    }
}
