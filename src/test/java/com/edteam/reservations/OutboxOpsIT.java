package com.edteam.reservations;

import com.edteam.reservations.application.outbox.OutboxDispatchResult;
import com.edteam.reservations.application.port.in.CreateReservationCommand;
import com.edteam.reservations.application.port.in.CreateReservationUseCase;
import com.edteam.reservations.application.port.in.DispatchPendingNotificationsUseCase;
import com.edteam.reservations.application.port.in.ItineraryData;
import com.edteam.reservations.application.port.out.EventPublisherPort;
import com.edteam.reservations.infrastructure.adapter.out.messaging.LoggingEventPublisher;
import com.edteam.reservations.infrastructure.adapter.out.outbox.MeteredEventOutbox;
import com.edteam.reservations.infrastructure.adapter.out.outbox.OutboxMetrics;
import com.edteam.reservations.support.AbstractPostgresIT;
import com.edteam.reservations.support.SecurityTestSupport;
import com.edteam.reservations.support.TestFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Las herramientas de operación de la mensajería, enchufadas: contexto real,
 * PostgreSQL real, HTTP real y <strong>sin broker</strong>.
 *
 * <p>Esa última parte es el punto, y es una restricción explícita del diseño:
 * encender el broker tiene que ser un cambio de configuración, no un requisito
 * para levantar la aplicación ni para correr el build. Es el mismo criterio con
 * el que {@code CacheIT} verifica que la aplicación arranque sin Redis.
 *
 * <p>Lo que se prueba acá es que las tres preguntas operativas se puedan
 * responder desde afuera del proceso: cuántos hay pendientes, hace cuánto está
 * trabado el más viejo y cuántos quedaron muertos. Antes ninguna tenía
 * respuesta: el estado vivía en el heap y el único rastro de un mensaje muerto
 * era una línea de log que desaparecía con el reinicio.
 */
@AutoConfigureMockMvc
@DisplayName("Operación del outbox (PostgreSQL, sin broker)")
class OutboxOpsIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private CreateReservationUseCase createReservation;

    @Autowired
    private DispatchPendingNotificationsUseCase dispatchNotifications;

    @Autowired
    private MeterRegistry meterRegistry;

    private CreateReservationCommand createCommand() {
        Instant departure = Instant.now().plus(Duration.ofDays(30));
        return new CreateReservationCommand(
                TestFixtures.owner(),
                UUID.randomUUID().toString(),
                new ItineraryData(new BigDecimal("1250.50"), "USD",
                        List.of(TestFixtures.segmentData(TestFixtures.EZE, TestFixtures.SCL, departure))),
                TestFixtures.passengerData());
    }

    // =================================================================
    // Arranque sin broker
    // =================================================================

    @Test
    @DisplayName("arranca sin broker: el publicador es el que sólo loguea y el outbox está instrumentado")
    void startsWithoutABroker() {
        assertThat(context.getBean(EventPublisherPort.class))
                .isInstanceOf(LoggingEventPublisher.class);
        assertThat(context.getBean(com.edteam.reservations.application.port.out.EventOutboxPort.class))
                .isInstanceOf(MeteredEventOutbox.class);
        // El consumidor no se levanta: es otro servicio.
        assertThat(context.getBeanNamesForType(
                com.edteam.reservations.infrastructure.adapter.in.messaging.ReservationEventListener.class))
                .isEmpty();
    }

    @Test
    @DisplayName("la API responde igual con el broker ausente")
    void theApiWorksWithoutABroker() {
        assertThat(createReservation.create(createCommand()).created()).isTrue();

        assertThat(countOutbox("PENDING")).isEqualTo(1L);
    }

    // =================================================================
    // H5 — métricas
    // =================================================================

    @Test
    @DisplayName("H5: pendientes, lag y dead letter se leen por Actuator")
    void publishesOutboxMetrics() throws Exception {
        createReservation.create(createCommand());
        createReservation.create(createCommand());
        createReservation.create(createCommand());

        // Antes las tres daban 404: no había ninguna forma de contar los
        // pendientes desde afuera del proceso, porque el estado vivía en el
        // heap. Es lo que volvía invisibles al reintento en caliente y a la
        // dead letter muda.
        assertThat(meterRegistry.get(OutboxMetrics.PENDING).gauge().value()).isEqualTo(3.0);
        assertThat(meterRegistry.get(OutboxMetrics.DEAD).gauge().value()).isZero();
        assertThat(meterRegistry.get(OutboxMetrics.LAG).gauge().value()).isGreaterThanOrEqualTo(0.0);
        // Sin broker la profundidad de la DLQ es "no se sabe" (-1) y no un cero
        // falso, que en un tablero con alerta en '> 0' tranquilizaría.
        assertThat(meterRegistry.get(OutboxMetrics.DLQ_DEPTH).gauge().value()).isEqualTo(-1.0);

        mockMvc.perform(get("/actuator/metrics/" + OutboxMetrics.PENDING).with(SecurityTestSupport.asOwner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(3.0));
        mockMvc.perform(get("/actuator/metrics/" + OutboxMetrics.LAG).with(SecurityTestSupport.asOwner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUnit").value("seconds"));
    }

    @Test
    @DisplayName("el lag crece con la antigüedad del pendiente más viejo")
    void lagGrowsWithTheOldestPendingMessage() {
        createReservation.create(createCommand());
        jdbcTemplate.update("UPDATE outbox_message SET enqueued_at = " + NOW_UTC + " - interval '9 minutes'");

        // El número que traduce "hay N pendientes" a "una notificación tarda
        // nueve minutos", que es lo que le importa al usuario.
        assertThat(meterRegistry.get(OutboxMetrics.LAG).gauge().value()).isGreaterThan(500.0);
    }

    @Test
    @DisplayName("los contadores del relay distinguen el tipo de fallo")
    void countersTellApartTransientFromPermanentFailures() {
        createReservation.create(createCommand());
        dispatchNotifications.dispatchPending(10);

        assertThat(meterRegistry.get(MeteredEventOutbox.ENQUEUED).counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get(MeteredEventOutbox.CLAIMED).counter().count()).isGreaterThanOrEqualTo(1.0);
        assertThat(meterRegistry.get(MeteredEventOutbox.DISPATCHED).counter().count()).isEqualTo(1.0);
    }

    // =================================================================
    // H2 — la dead letter del productor, con camino de vuelta
    // =================================================================

    @Test
    @DisplayName("H2: el endpoint de gestión lista la dead letter y la reencola")
    void theManagementEndpointListsAndReplaysTheDeadLetter() throws Exception {
        createReservation.create(createCommand());
        String messageId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM outbox_message", String.class);
        // Se lo mata a mano, que es lo que haría el relay con un payload roto.
        jdbcTemplate.update("UPDATE outbox_message SET status = 'FAILED', attempts = 3, failed_at = "
                + NOW_UTC + ", last_error = 'el payload guardado no es JSON válido'");

        // Antes esto era un 404: el endpoint no existía y el único rastro era
        // una línea de log que se perdía al reiniciar.
        mockMvc.perform(get("/actuator/outbox").with(SecurityTestSupport.asOwner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dead").value(1))
                .andExpect(jsonPath("$.pending").value(0))
                .andExpect(jsonPath("$.deadLetter[0].id").value(messageId))
                .andExpect(jsonPath("$.deadLetter[0].type").value("reservation.created"))
                .andExpect(jsonPath("$.deadLetter[0].lastError")
                        .value("el payload guardado no es JSON válido"));

        mockMvc.perform(get("/actuator/outbox/{id}", messageId).with(SecurityTestSupport.asOwner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts").value(3));

        // Y se puede reenviar después de arreglar la causa.
        mockMvc.perform(post("/actuator/outbox")
                        .with(SecurityTestSupport.asOwner())
                        .contentType("application/json")
                        .content("{\"messageId\":\"%s\",\"dispatch\":true}".formatted(messageId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(1))
                .andExpect(jsonPath("$.dispatched").value(1));

        assertThat(countOutbox("FAILED")).isZero();
        assertThat(countOutbox("DISPATCHED")).isEqualTo(1L);
    }

    @Test
    @DisplayName("el endpoint de la DLQ del consumidor informa que no sabe cuando no hay broker")
    void theConsumerDlqEndpointSaysItDoesNotKnowWithoutABroker() throws Exception {
        mockMvc.perform(get("/actuator/messaging-dlq").with(SecurityTestSupport.asOwner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.depth").value(-1))
                .andExpect(jsonPath("$.known").value(false));
    }

    @Test
    @DisplayName("el endpoint purga los despachados viejos a pedido")
    void theEndpointPurgesOldDispatchedMessages() throws Exception {
        createReservation.create(createCommand());
        dispatchNotifications.dispatchPending(10);
        jdbcTemplate.update("UPDATE outbox_message SET enqueued_at = " + NOW_UTC + " - interval '30 days'");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/actuator/outbox?olderThanDays=7").with(SecurityTestSupport.asOwner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purged").value(1));

        assertThat(countRows("outbox_message")).isZero();
    }

    // =================================================================
    // Durabilidad: la caída del proceso entre el commit y el envío
    // =================================================================

    /**
     * El escenario que el outbox en memoria no podía sobrevivir: la reserva se
     * comprometió, el evento quedó encolado y el proceso murió antes de
     * publicar. Con el outbox en el heap se perdía la notificación sin dejar
     * rastro; con la tabla, el mensaje sigue ahí y el relay lo levanta.
     */
    @Test
    @DisplayName("un proceso que muere entre el commit y el envío no pierde la notificación")
    void aCrashBetweenCommitAndPublishLosesNothing() {
        createReservation.create(createCommand());

        // El proceso muere acá: nada despachó todavía. Lo que sobrevive es lo
        // que está en la base, y está.
        assertThat(countOutbox("PENDING")).isEqualTo(1L);

        // Reinicio: el relay de la instancia que arranca lo encuentra.
        OutboxDispatchResult afterRestart = dispatchNotifications.dispatchPending(50);

        assertThat(afterRestart.dispatched()).isEqualTo(1);
        assertThat(countOutbox("DISPATCHED")).isEqualTo(1L);
    }

    /**
     * La variante más fina: el proceso muere <em>después</em> de reclamar el
     * mensaje. Sin lease quedaría {@code IN_FLIGHT} para siempre y la
     * notificación no saldría nunca, sin que nadie se enterara.
     */
    @Test
    @DisplayName("un proceso que muere después de reclamar el mensaje tampoco lo pierde")
    void aCrashAfterClaimingLosesNothingEither() {
        createReservation.create(createCommand());
        jdbcTemplate.update("UPDATE outbox_message SET status = 'IN_FLIGHT', claimed_at = " + NOW_UTC);

        // Mientras el lease vale, otra instancia no lo toca: no hay doble envío.
        assertThat(dispatchNotifications.dispatchPending(50)).isEqualTo(OutboxDispatchResult.EMPTY);

        // Al vencer, vuelve a ser elegible.
        jdbcTemplate.update("UPDATE outbox_message SET claimed_at = " + NOW_UTC + " - interval '10 minutes'");

        assertThat(dispatchNotifications.dispatchPending(50).dispatched()).isEqualTo(1);
    }
}
