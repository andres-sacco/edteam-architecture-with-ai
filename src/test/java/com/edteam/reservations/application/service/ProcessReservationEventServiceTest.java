package com.edteam.reservations.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.edteam.reservations.application.exception.UnprocessableEventException;
import com.edteam.reservations.application.notification.NotificationDelivery;
import com.edteam.reservations.application.port.in.EventProcessingOutcome;
import com.edteam.reservations.application.port.in.InboundEvent;
import com.edteam.reservations.application.port.out.NotificationDeliveryPort;
import com.edteam.reservations.application.port.out.ProcessedMessagePort;
import com.edteam.reservations.support.MutableClock;
import com.edteam.reservations.support.TestFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El lado del consumo que este repositorio controla.
 *
 * <p>Con dobles en memoria y no con mocks: lo que hay que probar es que
 * procesar dos veces deje <b>el mismo estado</b>, y eso se asserta contando
 * efectos acumulados, no verificando invocaciones. Un mock que confirma «se
 * llamó una vez» no dice nada sobre el estado resultante.
 */
@DisplayName("ProcessReservationEventService (consumidor)")
class ProcessReservationEventServiceTest {

    private static final String SUBJECT = "8421";
    private static final String USER = "317";

    private FakeProcessedMessages processedMessages;
    private FakeDeliveries deliveries;
    private MutableClock clock;
    private ProcessReservationEventService service;

    @BeforeEach
    void setUp() {
        processedMessages = new FakeProcessedMessages();
        deliveries = new FakeDeliveries();
        clock = MutableClock.at(TestFixtures.NOW);
        service = new ProcessReservationEventService(processedMessages, deliveries, clock);
    }

    private InboundEvent event(String messageId, String type, long sequence) {
        return event(messageId, type, sequence, clock.instant());
    }

    private static InboundEvent event(String messageId, String type, long sequence, Instant occurredAt) {
        return new InboundEvent(
                messageId, type, 1, "urn:edteam:flight-reservations", SUBJECT, sequence, USER, occurredAt, "corr-1");
    }

    // -----------------------------------------------------------------
    // H3 / H12 — idempotencia
    // -----------------------------------------------------------------

    /**
     * La prueba central de todo el paso: la entrega es at-least-once, así que
     * el mismo mensaje llega más de una vez por diseño. Procesarlo dos veces
     * tiene que dejar exactamente el mismo estado.
     */
    @Test
    @DisplayName("H3: el mismo mensaje procesado dos veces deja un solo efecto")
    void processingTheSameMessageTwiceLeavesOneEffect() {
        InboundEvent first = event("m-1", "reservation.confirmed", 11L);

        assertThat(service.process(first)).isEqualTo(EventProcessingOutcome.APPLIED);
        assertThat(service.process(first)).isEqualTo(EventProcessingOutcome.DUPLICATE);

        assertThat(deliveries.countFor(SUBJECT)).isEqualTo(1);
        assertThat(deliveries.all()).extracting(NotificationDelivery::messageId).containsExactly("m-1");
    }

    @Test
    @DisplayName("H3: cinco entregas del mismo mensaje siguen dejando un solo efecto")
    void isIdempotentAcrossManyRedeliveries() {
        InboundEvent redelivered = event("m-1", "reservation.created", 10L);

        for (int i = 0; i < 5; i++) {
            service.process(redelivered);
        }

        assertThat(deliveries.countFor(SUBJECT)).isEqualTo(1);
    }

    @Test
    @DisplayName("dos mensajes distintos de la misma reserva dejan dos efectos")
    void differentMessagesAreNotDuplicates() {
        service.process(event("m-1", "reservation.created", 10L));
        service.process(event("m-2", "reservation.confirmed", 11L));

        assertThat(deliveries.countFor(SUBJECT)).isEqualTo(2);
    }

    /**
     * H12 — el dedupe corre <b>antes</b> de cualquier decisión de reintento.
     *
     * <p>Es lo único que contiene la multiplicación de un mensaje que se
     * reencola varias veces, y sólo la contiene si corre primero: si el
     * consumidor decidiera reintentar antes de deduplicar, cada vuelta dejaría
     * un efecto más.
     */
    @Test
    @DisplayName("H12: el dedupe corre antes que la validación, así un duplicado no puede ir a la DLQ")
    void deduplicationRunsBeforeValidation() {
        InboundEvent applied = event("m-1", "reservation.created", 10L);
        service.process(applied);

        // El mismo mensaje vuelve, pero con un tipo que este consumidor ya no
        // reconoce (una regla que se endureció después de aplicarlo). Se
        // confirma como duplicado en lugar de mandarse a la DLQ: su efecto ya
        // está hecho y reprocesarlo no puede cambiar nada.
        InboundEvent sameIdUnknownType = new InboundEvent(
                "m-1",
                "reservation.exploded",
                1,
                "urn:edteam:flight-reservations",
                SUBJECT,
                10L,
                USER,
                clock.instant(),
                null);

        assertThat(service.process(sameIdUnknownType)).isEqualTo(EventProcessingOutcome.DUPLICATE);
        assertThat(deliveries.countFor(SUBJECT)).isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // H8 — desorden
    // -----------------------------------------------------------------

    /**
     * H8 — la regla de descarte por {@code sequence} borraba eventos legítimos.
     *
     * <p>El escenario exacto del hallazgo: {@code created} (seq 10) entra al
     * retry 30 s, {@code confirmed} (seq 11) se procesa, y vuelve el 10. Con la
     * regla vieja el consumidor lo descartaba y hacía {@code ack}: el usuario
     * nunca recibía el alta y <b>nadie se enteraba</b>, porque para el broker el
     * mensaje se había procesado bien.
     */
    @Test
    @DisplayName("H8: un evento distinto que llega desordenado se aplica igual y se registra como anomalía")
    void appliesOutOfOrderEventsInsteadOfDiscardingThem() {
        service.process(event("m-confirmed", "reservation.confirmed", 11L));

        EventProcessingOutcome outcome = service.process(event("m-created", "reservation.created", 10L));

        assertThat(outcome).isEqualTo(EventProcessingOutcome.APPLIED_OUT_OF_ORDER);
        // Los dos efectos existen: el alta NO se perdió.
        assertThat(deliveries.countFor(SUBJECT)).isEqualTo(2);
        assertThat(deliveries.all())
                .extracting(NotificationDelivery::messageId)
                .containsExactly("m-confirmed", "m-created");
    }

    @Test
    @DisplayName("H8: el desorden y el duplicado son cosas distintas y se resuelven distinto")
    void tellsApartOutOfOrderFromDuplicate() {
        InboundEvent created = event("m-created", "reservation.created", 10L);
        service.process(event("m-confirmed", "reservation.confirmed", 11L));

        // Distinto messageId, sequence menor: NO es un duplicado.
        assertThat(service.process(created)).isEqualTo(EventProcessingOutcome.APPLIED_OUT_OF_ORDER);
        // Mismo messageId: sí lo es.
        assertThat(service.process(created)).isEqualTo(EventProcessingOutcome.DUPLICATE);

        assertThat(deliveries.countFor(SUBJECT)).isEqualTo(2);
    }

    @Test
    @DisplayName("un evento en orden no se marca como anomalía")
    void inOrderEventsAreNotAnomalies() {
        service.process(event("m-created", "reservation.created", 10L));

        assertThat(service.process(event("m-confirmed", "reservation.confirmed", 11L)))
                .isEqualTo(EventProcessingOutcome.APPLIED);
    }

    // -----------------------------------------------------------------
    // Fallos permanentes
    // -----------------------------------------------------------------

    @Test
    @DisplayName("un tipo desconocido no es procesable: va a la DLQ, no a reintentos")
    void rejectsUnknownTypes() {
        InboundEvent unknown = event("m-1", "reservation.exploded", 10L);

        assertThatThrownBy(() -> service.process(unknown))
                .isInstanceOf(UnprocessableEventException.class)
                .hasMessageContaining("reservation.exploded");

        assertThat(deliveries.countFor(SUBJECT)).isZero();
    }

    @Test
    @DisplayName("una versión de esquema que no se entiende no es procesable")
    void rejectsUnsupportedSchemaVersions() {
        InboundEvent v2 = new InboundEvent(
                "m-1",
                "reservation.created",
                2,
                "urn:edteam:flight-reservations",
                SUBJECT,
                10L,
                USER,
                clock.instant(),
                null);

        assertThatThrownBy(() -> service.process(v2))
                .isInstanceOf(UnprocessableEventException.class)
                .hasMessageContaining("Versión de esquema 2");
    }

    @Test
    @DisplayName("un hecho más viejo que la ventana de frescura no se notifica")
    void rejectsStaleEvents() {
        InboundEvent old =
                event("m-1", "reservation.confirmed", 10L, clock.instant().minus(Duration.ofHours(30)));

        assertThatThrownBy(() -> service.process(old))
                .isInstanceOf(UnprocessableEventException.class)
                .hasMessageContaining("supera la ventana");

        assertThat(deliveries.countFor(SUBJECT)).isZero();
    }

    @Test
    @DisplayName("un hecho dentro de la ventana sí se notifica")
    void acceptsEventsInsideTheFreshnessWindow() {
        InboundEvent recent =
                event("m-1", "reservation.confirmed", 10L, clock.instant().minus(Duration.ofHours(23)));

        assertThat(service.process(recent)).isEqualTo(EventProcessingOutcome.APPLIED);
    }

    @Test
    @DisplayName("exige sus colaboradores")
    void requiresCollaborators() {
        assertThatThrownBy(() -> new ProcessReservationEventService(null, deliveries, clock))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProcessReservationEventService(processedMessages, null, clock))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProcessReservationEventService(processedMessages, deliveries, null))
                .isInstanceOf(NullPointerException.class);
    }

    // -----------------------------------------------------------------
    // Dobles
    // -----------------------------------------------------------------

    /** Reproduce el {@code INSERT ... ON CONFLICT DO NOTHING} de la tabla. */
    private static final class FakeProcessedMessages implements ProcessedMessagePort {

        private final Map<String, Long> sequencesById = new HashMap<>();
        private final Map<String, String> subjectsById = new HashMap<>();

        @Override
        public boolean claim(String messageId, String type, String subject, long sequence) {
            if (sequencesById.containsKey(messageId)) {
                return false;
            }
            sequencesById.put(messageId, sequence);
            subjectsById.put(messageId, subject);
            return true;
        }

        @Override
        public long lastAppliedSequence(String subject, String excludingMessageId) {
            return sequencesById.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(excludingMessageId))
                    .filter(entry -> subject.equals(subjectsById.get(entry.getKey())))
                    .mapToLong(Map.Entry::getValue)
                    .max()
                    .orElse(Long.MIN_VALUE);
        }

        @Override
        public int purgeProcessedBefore(Instant limit) {
            return 0;
        }
    }

    /** Efecto observable: lo que se cuenta para probar la idempotencia. */
    private static final class FakeDeliveries implements NotificationDeliveryPort {

        private final List<NotificationDelivery> delivered = new ArrayList<>();

        @Override
        public void deliver(NotificationDelivery delivery) {
            // Igual que el UNIQUE de la tabla: un segundo efecto para el mismo
            // mensaje no es un aviso duplicado, es un error.
            if (delivered.stream().anyMatch(d -> d.messageId().equals(delivery.messageId()))) {
                throw new IllegalStateException("Segunda entrega para el mensaje " + delivery.messageId());
            }
            delivered.add(delivery);
        }

        @Override
        public int countFor(String subject) {
            return (int)
                    delivered.stream().filter(d -> d.subject().equals(subject)).count();
        }

        List<NotificationDelivery> all() {
            return List.copyOf(delivered);
        }
    }
}
