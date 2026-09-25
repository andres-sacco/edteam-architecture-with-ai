package com.edteam.reservations.infrastructure.adapter.out.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.edteam.reservations.application.outbox.OutboxFailure;
import com.edteam.reservations.application.outbox.OutboxMessage;
import com.edteam.reservations.application.outbox.OutboxStatus;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.domain.event.DomainEvent;
import com.edteam.reservations.domain.event.ReservationCancelled;
import com.edteam.reservations.domain.event.ReservationConfirmed;
import com.edteam.reservations.domain.event.ReservationCreated;
import com.edteam.reservations.support.AbstractPostgresIT;
import com.edteam.reservations.support.TestFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * El outbox durable contra PostgreSQL real.
 *
 * <p>Se prueba contra la base y no con un doble porque lo que hay que validar
 * es exactamente lo específico de PostgreSQL: el {@code FOR UPDATE SKIP
 * LOCKED}, el {@code BIGSERIAL} que da el orden, el índice parcial y el
 * {@code jsonb}. Con un doble en memoria estos tests pasarían sin probar nada
 * de eso: es justo lo que pasaba antes.
 */
@DisplayName("JdbcEventOutbox (outbox durable)")
class JdbcEventOutboxIT extends AbstractPostgresIT {

    @Autowired
    private JdbcEventOutbox outbox;

    /** El puerto que ven los casos de uso: el decorador instrumentado. */
    @Autowired
    private EventOutboxPort eventOutbox;

    @Autowired
    private com.edteam.reservations.infrastructure.config.OutboxProperties outboxProperties;

    private DomainEvent created() {
        return ReservationCreated.of(TestFixtures.storedReservation(0L));
    }

    private DomainEvent confirmed() {
        return ReservationConfirmed.of(TestFixtures.storedReservation(1L));
    }

    private DomainEvent cancelled() {
        return ReservationCancelled.of(TestFixtures.storedReservation(2L).cancel(TestFixtures.NOW));
    }

    // =================================================================
    // El reclamo es de quien lo tomó
    // =================================================================

    @Nested
    @DisplayName("La propiedad del reclamo")
    class ClaimOwnership {

        @Test
        @DisplayName("un markFailed tardío no revive un mensaje que otro ya despachó")
        void aLateMarkFailedDoesNotRevertADispatchedMessage() {
            // El hallazgo: markDispatched y markFailed no verificaban que el
            // reclamo siguiera siendo suyo. Con un lease que puede vencer en
            // medio de un lote, otra instancia re-reclama un mensaje todavía
            // en vuelo; la publicación doble la absorbe la deduplicación del
            // consumidor, pero el markFailed tardío del primero devolvía a
            // PENDING un mensaje YA ENTREGADO —reenvío indefinido— y contaba
            // el intento dos veces, acelerando su llegada a la dead letter.
            inTransaction(() -> eventOutbox.enqueue(List.of(created())));
            String messageId = outbox.pollPending(1).getFirst().id();

            // La instancia B lo despacha.
            outbox.markDispatched(messageId);

            // La instancia A, que lo tenía reclamado desde antes, llega tarde.
            outbox.markFailed(messageId, "el broker no confirmó", OutboxFailure.TRANSIENT);

            assertThat(statusOf(messageId)).isEqualTo("DISPATCHED");
            assertThat(attemptsOf(messageId))
                    .as("un solo intento: el tardío no cuenta")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("un markDispatched tardío tampoco pisa el estado de quien lo tenía")
        void aLateMarkDispatchedIsIgnored() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created())));
            String messageId = outbox.pollPending(1).getFirst().id();

            outbox.markFailed(messageId, "el broker no confirmó", OutboxFailure.TRANSIENT);
            outbox.markDispatched(messageId);

            assertThat(statusOf(messageId)).isEqualTo("PENDING");
            assertThat(attemptsOf(messageId)).isEqualTo(1);
        }

        @Test
        @DisplayName("la sonda reclama uno solo y liberarlo no le cuesta el intento")
        void theProbeClaimsOneAndReleasingItCostsNothing() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created(), confirmed(), cancelled())));

            List<OutboxMessage> probe = outbox.pollProbe();

            assertThat(probe).hasSize(1);
            outbox.release(List.of(probe.getFirst().id()));

            assertThat(statusOf(probe.getFirst().id())).isEqualTo("PENDING");
            assertThat(attemptsOf(probe.getFirst().id()))
                    .as("una sonda no es un intento de entrega")
                    .isZero();
        }

        @Test
        @DisplayName("la sonda no toma siempre el mismo mensaje: reparte el riesgo")
        void theProbeSpreadsTheRisk() {
            // Con un backlog chico, la sonda se dispara una y otra vez durante
            // toda la caída. Si tomara siempre el más viejo, sería siempre el
            // mismo el que arriesga —y con el orden natural ése es el
            // reservation.created de la reserva más antigua, que es el que más
            // importa—.
            inTransaction(() -> eventOutbox.enqueue(List.of(created(), confirmed(), cancelled())));

            Set<String> picked = new java.util.HashSet<>();
            for (int i = 0; i < 40; i++) {
                List<OutboxMessage> probe = outbox.pollProbe();
                if (!probe.isEmpty()) {
                    picked.add(probe.getFirst().id());
                    outbox.release(List.of(probe.getFirst().id()));
                }
            }

            assertThat(picked)
                    .as("cuarenta sondas sobre tres mensajes tocaron más de uno")
                    .hasSizeGreaterThan(1);
        }

        private String statusOf(String messageId) {
            return jdbcTemplate.queryForObject(
                    "SELECT status FROM outbox_message WHERE id = ?::uuid", String.class, messageId);
        }

        private int attemptsOf(String messageId) {
            Integer attempts = jdbcTemplate.queryForObject(
                    "SELECT attempts FROM outbox_message WHERE id = ?::uuid", Integer.class, messageId);
            return attempts == null ? -1 : attempts;
        }
    }

    // =================================================================
    // H4 — encolado transaccional
    // =================================================================

    @Nested
    @DisplayName("H4: el encolado participa de la transacción del caso de uso")
    class TransactionalEnqueue {

        @Test
        @DisplayName("el evento se compromete junto con la reserva")
        void commitsWithTheReservation() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created())));

            assertThat(countOutbox("PENDING")).isEqualTo(1L);
        }

        /**
         * El problema <b>inverso</b> al que documentaba el stub en memoria, y el
         * que más cuesta ver: no se pierde un evento, se emitía uno
         * <em>fantasma</em>. Dos confirmaciones concurrentes en las que una
         * pierde el conflicto optimista dejaban dos eventos, y el usuario
         * recibía dos avisos por una sola confirmación.
         */
        @Test
        @DisplayName("un rollback se lleva el evento: no se notifica un hecho que no ocurrió")
        void aRollbackTakesTheEventWithIt() {
            assertThat(countOutbox("PENDING")).isZero();

            try {
                inTransaction(() -> {
                    eventOutbox.enqueue(List.of(created()));
                    // Cualquier fallo después del encolado: un UNIQUE, un
                    // conflicto optimista, la auditoría que explota.
                    throw new IllegalStateException("la transacción se cae después de encolar");
                });
            } catch (IllegalStateException expected) {
                // Es el escenario del test.
            }

            assertThat(countRows("outbox_message")).isZero();
        }

        @Test
        @DisplayName("varios eventos de la misma transacción se comprometen juntos")
        void enqueuesABatchAtomically() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created(), confirmed(), cancelled())));

            assertThat(outboxTypes("PENDING"))
                    .containsExactly("reservation.created", "reservation.confirmed", "reservation.cancelled");
        }
    }

    // =================================================================
    // H10 — el reclamo
    // =================================================================

    @Nested
    @DisplayName("H10: pollPending reclama el mensaje, no sólo lo lee")
    class Claiming {

        @Test
        @DisplayName("un segundo poll sin resolver nada devuelve vacío")
        void aSecondPollReturnsNothing() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created(), confirmed())));

            assertThat(outbox.pollPending(10)).hasSize(2);
            // Antes devolvía los MISMOS mensajes, y lo único que evitaba el
            // doble envío era que hubiera un solo llamador.
            assertThat(outbox.pollPending(10)).isEmpty();
            assertThat(countOutbox("IN_FLIGHT")).isEqualTo(2L);
        }

        /**
         * La prueba de que varias instancias pueden despachar en paralelo: el
         * requisito que hace innecesario un lock distribuido.
         */
        @Test
        @DisplayName("dos despachadores concurrentes no reclaman el mismo mensaje")
        void concurrentDispatchersNeverClaimTheSameMessage() throws Exception {
            int events = 60;
            inTransaction(() -> eventOutbox.enqueue(
                    IntStream.range(0, events).mapToObj(i -> created()).toList()));

            int dispatchers = 4;
            ConcurrentLinkedQueue<String> claimedIds = new ConcurrentLinkedQueue<>();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(dispatchers);

            try (ExecutorService pool = Executors.newFixedThreadPool(dispatchers)) {
                for (int i = 0; i < dispatchers; i++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            // Varias vueltas: con SKIP LOCKED cada hilo se
                            // lleva un subconjunto disjunto y ninguno espera.
                            for (int round = 0; round < 10; round++) {
                                outbox.pollPending(7).forEach(message -> claimedIds.add(message.id()));
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            }

            Set<String> unique = Set.copyOf(claimedIds);
            // Cada mensaje entregado exactamente a un despachador.
            assertThat(claimedIds).hasSize(unique.size());
            assertThat(unique).hasSize(events);
        }

        /**
         * El escenario de «un proceso que muere entre el commit y el envío»
         * cuando ya había reclamado el mensaje: sin lease quedaría
         * {@code IN_FLIGHT} para siempre y la notificación no se enviaría nunca
         * sin que nadie lo notara.
         */
        @Test
        @DisplayName("un reclamo cuyo lease venció vuelve a ser elegible")
        void anExpiredClaimBecomesEligibleAgain() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created())));
            List<OutboxMessage> claimed = outbox.pollPending(10);
            assertThat(claimed).hasSize(1);

            // El proceso muere acá: el mensaje queda reclamado y sin resolver.
            assertThat(outbox.pollPending(10)).isEmpty();

            // Se simula el vencimiento del lease envejeciendo el reclamo.
            jdbcTemplate.update("UPDATE outbox_message SET claimed_at = claimed_at - interval '10 minutes'");

            assertThat(outbox.pollPending(10))
                    .extracting(OutboxMessage::id)
                    .containsExactly(claimed.getFirst().id());
        }

        @Test
        @DisplayName("release devuelve el mensaje a pendiente sin gastarle un intento")
        void releaseDoesNotConsumeAnAttempt() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created())));
            OutboxMessage claimed = outbox.pollPending(10).getFirst();

            outbox.release(List.of(claimed.id()));

            List<OutboxMessage> again = outbox.pollPending(10);
            assertThat(again).hasSize(1);
            assertThat(again.getFirst().attempts()).isZero();
        }
    }

    // =================================================================
    // H6 — orden
    // =================================================================

    @Nested
    @DisplayName("H6: el sequence cruza el puerto y ordena la entrega")
    class Ordering {

        @Test
        @DisplayName("el sequence llega al mensaje y es monotónico")
        void sequenceReachesTheMessage() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created(), confirmed(), cancelled())));

            List<OutboxMessage> claimed = outbox.pollPending(10);

            // Antes el sequence se descartaba en el borde del puerto: la
            // garantía de orden moría ahí y el consumidor no recibía el número
            // con el que ordenar, así que la aserción no se podía escribir.
            assertThat(claimed).extracting(OutboxMessage::sequence).isSorted();
            assertThat(claimed)
                    .allSatisfy(message -> assertThat(message.sequence()).isPositive());
            assertThat(claimed)
                    .extracting(OutboxMessage::type)
                    .containsExactly("reservation.created", "reservation.confirmed", "reservation.cancelled");
        }

        @Test
        @DisplayName("el subject es la reserva: es el ámbito del orden")
        void subjectIsTheReservation() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created())));

            assertThat(outbox.pollPending(10).getFirst().subject()).isEqualTo(TestFixtures.RESERVATION_ID.toString());
        }
    }

    // =================================================================
    // H1 — backoff
    // =================================================================

    @Nested
    @DisplayName("H1: el reintento no es en caliente")
    class Backoff {

        @Test
        @DisplayName("un mensaje que acaba de fallar no vuelve en la corrida siguiente")
        void aJustFailedMessageIsNotEligibleYet() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created())));
            OutboxMessage claimed = outbox.pollPending(10).getFirst();

            outbox.markFailed(claimed.id(), "el broker no confirmó", OutboxFailure.TRANSIENT);

            // Antes devolvía el mismo mensaje inmediatamente: con
            // dispatch-interval de 5s, los cinco intentos se consumían en 20
            // segundos y un blip de medio minuto mandaba el outbox entero a la
            // dead letter.
            assertThat(outbox.pollPending(10)).isEmpty();
            assertThat(countOutbox("PENDING")).isEqualTo(1L);
            assertThat(countOutbox("FAILED")).isZero();
        }

        @Test
        @DisplayName("la espera crece con los intentos y tiene techo")
        void theWaitGrowsAndIsCapped() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created())));
            String id = outbox.pollPending(10).getFirst().id();

            Duration first = failAndReadBackoff(id);
            for (int i = 0; i < 6; i++) {
                makeEligible(id);
                outbox.pollPending(10);
                failAndReadBackoff(id);
            }
            Duration later = readBackoff(id);

            // Crece —el jitter se sortea sobre un intervalo que se duplica— y
            // nunca pasa del techo configurado (5 min).
            assertThat(later).isGreaterThan(first);
            assertThat(later).isLessThanOrEqualTo(Duration.ofMinutes(5).plusSeconds(1));
        }

        /**
         * El escenario del hallazgo, de punta a punta: cincuenta mensajes
         * contra un destino caído. Antes quedaban los cincuenta en
         * {@code FAILED} en veinte segundos.
         */
        @Test
        @DisplayName("cincuenta mensajes contra un destino caído siguen pendientes, no en la dead letter")
        void fiftyMessagesAgainstADeadDestinationStayPending() {
            inTransaction(() -> eventOutbox.enqueue(
                    IntStream.range(0, 50).mapToObj(i -> created()).toList()));

            // Cuatro vueltas del relay, cada una fallando todo: son más de los
            // veinte segundos que antes alcanzaban para destruirlos.
            for (int round = 0; round < 4; round++) {
                jdbcTemplate.update("UPDATE outbox_message SET next_attempt_at = " + NOW_UTC + ", claimed_at = NULL");
                outbox.pollPending(50)
                        .forEach(message -> outbox.markFailed(message.id(), "destino caído", OutboxFailure.TRANSIENT));
            }

            assertThat(countOutbox("FAILED")).isZero();
            assertThat(countOutbox("PENDING")).isEqualTo(50L);
            assertThat(jdbcTemplate.queryForObject("SELECT max(attempts) FROM outbox_message", Integer.class))
                    .isEqualTo(4);
        }

        private Duration failAndReadBackoff(String id) {
            outbox.markFailed(id, "el broker no confirmó", OutboxFailure.TRANSIENT);
            return readBackoff(id);
        }

        private Duration readBackoff(String id) {
            Long millis = jdbcTemplate.queryForObject(
                    "SELECT (extract(epoch from (next_attempt_at - " + NOW_UTC + ")) * 1000)::bigint "
                            + "FROM outbox_message WHERE id = ?::uuid",
                    Long.class,
                    id);
            return Duration.ofMillis(millis == null ? 0L : millis);
        }

        private void makeEligible(String id) {
            jdbcTemplate.update(
                    "UPDATE outbox_message SET next_attempt_at = " + NOW_UTC + ", claimed_at = NULL WHERE id = ?::uuid",
                    id);
        }
    }

    // =================================================================
    // H9 / H2 — dead letter y replay
    // =================================================================

    @Nested
    @DisplayName("H9 y H2: la dead letter del productor tiene entrada y salida")
    class DeadLetter {

        @Test
        @DisplayName("H9: un fallo permanente va a la dead letter en el primer intento")
        void aPermanentFailureDiesImmediately() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created())));
            String id = outbox.pollPending(10).getFirst().id();

            outbox.markFailed(id, "el payload guardado no es JSON válido", OutboxFailure.PERMANENT);

            // Sin esta distinción, un mensaje venenoso quemaba todos los
            // intentos y veinticinco segundos del despachador por nada.
            assertThat(countOutbox("FAILED")).isEqualTo(1L);
            assertThat(outbox.deadLetter(10)).singleElement().satisfies(dead -> {
                assertThat(dead.attempts()).isEqualTo(1);
                assertThat(dead.lastError()).contains("no es JSON válido");
            });
        }

        @Test
        @DisplayName("un fallo transitorio muere al agotar los intentos")
        void aTransientFailureDiesWhenAttemptsRunOut() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created())));
            String id = outbox.pollPending(10).getFirst().id();

            // El tope se lee de la configuración y no se escribe: subió de 10
            // a 80 para que el techo de seis horas sea el corte que manda, y
            // un número literal acá habría quedado viejo en silencio.
            for (int i = 0; i < outboxProperties.maxAttempts() + 2; i++) {
                outbox.markFailed(id, "destino caído", OutboxFailure.TRANSIENT);
                jdbcTemplate.update(
                        "UPDATE outbox_message SET next_attempt_at = " + NOW_UTC
                                + ", claimed_at = NULL WHERE id = ?::uuid",
                        id);
                outbox.pollPending(10);
            }

            assertThat(countOutbox("FAILED")).isEqualTo(1L);
        }

        @Test
        @DisplayName("un fallo transitorio muere al superar el techo de tiempo, aunque le queden intentos")
        void aTransientFailureDiesWhenTheCeilingIsReached() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created())));
            String id = outbox.pollPending(10).getFirst().id();
            // Lleva más de las seis horas de techo esperando.
            jdbcTemplate.update(
                    "UPDATE outbox_message SET enqueued_at = " + NOW_UTC + " - interval '7 hours' WHERE id = ?::uuid",
                    id);

            outbox.markFailed(id, "el broker sigue caído", OutboxFailure.TRANSIENT);

            assertThat(countOutbox("FAILED")).isEqualTo(1L);
            assertThat(outbox.deadLetter(10).getFirst().attempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("H2: la dead letter se lista y se reencola")
        void theDeadLetterCanBeListedAndReplayed() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created())));
            String id = outbox.pollPending(10).getFirst().id();
            outbox.markFailed(id, "payload inválido", OutboxFailure.PERMANENT);

            assertThat(outbox.stats().dead()).isEqualTo(1L);
            assertThat(outbox.deadLetter(10))
                    .singleElement()
                    .satisfies(dead -> assertThat(dead.id()).isEqualTo(id));

            assertThat(outbox.replay(id)).isTrue();

            // Vuelve elegible y con los intentos en cero: el replay ocurre
            // después de arreglar la causa, así que arrancar con el contador
            // agotado lo mandaría de vuelta a la dead letter en el primer
            // tropiezo.
            assertThat(countOutbox("FAILED")).isZero();
            assertThat(outbox.pollPending(10)).singleElement().satisfies(message -> {
                assertThat(message.id()).isEqualTo(id);
                assertThat(message.attempts()).isZero();
            });
        }

        @Test
        @DisplayName("el replay de un id que no está muerto no hace nada")
        void replayingAnUnknownIdDoesNothing() {
            assertThat(outbox.replay("11111111-1111-1111-1111-111111111111")).isFalse();
        }

        @Test
        @DisplayName("replayAll reencola toda la dead letter")
        void replayAllRequeuesEverything() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created(), confirmed(), cancelled())));
            outbox.pollPending(10)
                    .forEach(message -> outbox.markFailed(message.id(), "payload inválido", OutboxFailure.PERMANENT));

            assertThat(outbox.replayAll()).isEqualTo(3);
            assertThat(countOutbox("PENDING")).isEqualTo(3L);
        }
    }

    // =================================================================
    // H5 — métricas
    // =================================================================

    @Nested
    @DisplayName("H5: el outbox se puede medir desde afuera del proceso")
    class Metrics {

        @Test
        @DisplayName("stats cuenta pendientes, muertos y despachados en una consulta")
        void statsCountsEverything() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created(), confirmed(), cancelled())));
            List<OutboxMessage> claimed = outbox.pollPending(10);
            outbox.markDispatched(claimed.get(0).id());
            outbox.markFailed(claimed.get(1).id(), "payload inválido", OutboxFailure.PERMANENT);
            outbox.release(List.of(claimed.get(2).id()));

            OutboxStats stats = outbox.stats();

            assertThat(stats.pending()).isEqualTo(1L);
            assertThat(stats.dead()).isEqualTo(1L);
            assertThat(stats.dispatched()).isEqualTo(1L);
        }

        /**
         * El número que importa: no cuántos hay, sino hace cuánto que el más
         * viejo está esperando. Es lo que traduce «hay 4.000 pendientes» a
         * «una notificación tarda 40 minutos».
         */
        @Test
        @DisplayName("el lag es la antigüedad del pendiente más viejo")
        void lagIsTheAgeOfTheOldestPending() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created())));
            jdbcTemplate.update("UPDATE outbox_message SET enqueued_at = " + NOW_UTC + " - interval '12 minutes'");

            assertThat(outbox.stats().lag()).isBetween(Duration.ofMinutes(11), Duration.ofMinutes(13));
        }

        @Test
        @DisplayName("sin pendientes el lag es cero, no un valor viejo")
        void lagIsZeroWithoutPendingMessages() {
            assertThat(outbox.stats().lag()).isZero();
            assertThat(outbox.stats().pending()).isZero();
        }
    }

    // =================================================================
    // H11 — purga
    // =================================================================

    @Nested
    @DisplayName("H11: la tabla no crece para siempre")
    class Purge {

        @Test
        @DisplayName("los despachados viejos se borran y los pendientes no se tocan")
        void purgesOldDispatchedOnly() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created(), confirmed(), cancelled())));
            List<OutboxMessage> claimed = outbox.pollPending(10);
            outbox.markDispatched(claimed.get(0).id());
            outbox.markDispatched(claimed.get(1).id());
            outbox.markFailed(claimed.get(2).id(), "payload inválido", OutboxFailure.PERMANENT);
            jdbcTemplate.update("UPDATE outbox_message SET enqueued_at = " + NOW_UTC + " - interval '10 days'");

            int purged = outbox.purgeDispatchedBefore(Instant.now().minus(Duration.ofDays(7)));

            assertThat(purged).isEqualTo(2);
            // La dead letter NO se purga: se conserva hasta resolverse, porque
            // es justamente lo que alguien tiene que mirar y reencolar.
            assertThat(countOutbox("FAILED")).isEqualTo(1L);
        }

        @Test
        @DisplayName("un despachado reciente no se purga")
        void keepsRecentDispatchedMessages() {
            inTransaction(() -> eventOutbox.enqueue(List.of(created())));
            outbox.markDispatched(outbox.pollPending(10).getFirst().id());

            assertThat(outbox.purgeDispatchedBefore(Instant.now().minus(Duration.ofDays(7))))
                    .isZero();
            assertThat(countOutbox("DISPATCHED")).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("el payload se guarda serializado y sin datos sensibles")
    void storesTheSerializedPayloadWithoutSensitiveData() {
        inTransaction(() -> eventOutbox.enqueue(List.of(created())));

        String payload = jdbcTemplate.queryForObject("SELECT payload::text FROM outbox_message", String.class);

        assertThat(payload)
                .contains("\"reservationId\"")
                .contains("\"userId\"")
                .doesNotContain(TestFixtures.USER_EMAIL)
                .doesNotContain("30123456");
    }

    @Test
    @DisplayName("el estado de un mensaje reclamado es IN_FLIGHT")
    void claimedMessagesAreInFlight() {
        inTransaction(() -> eventOutbox.enqueue(List.of(created())));

        assertThat(outbox.pollPending(10).getFirst().status()).isEqualTo(OutboxStatus.IN_FLIGHT);
    }

    @Test
    @DisplayName("el poll respeta el tamaño del lote")
    void honoursTheBatchSize() {
        inTransaction(() -> eventOutbox.enqueue(
                IntStream.range(0, 10).mapToObj(i -> created()).collect(Collectors.toList())));

        assertThat(outbox.pollPending(4)).hasSize(4);
        assertThat(outbox.pollPending(4)).hasSize(4);
        assertThat(outbox.pollPending(4)).hasSize(2);
    }
}
