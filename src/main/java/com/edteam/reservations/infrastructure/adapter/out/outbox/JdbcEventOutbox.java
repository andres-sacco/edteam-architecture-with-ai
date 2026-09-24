package com.edteam.reservations.infrastructure.adapter.out.outbox;

import com.edteam.reservations.application.outbox.OutboxFailure;
import com.edteam.reservations.application.outbox.OutboxMessage;
import com.edteam.reservations.application.outbox.OutboxStatus;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.domain.event.DomainEvent;
import com.edteam.reservations.infrastructure.adapter.out.messaging.DomainEventPayloadMapper;
import com.edteam.reservations.infrastructure.config.OutboxProperties;
import com.edteam.reservations.infrastructure.jdbc.Utc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Outbox durable sobre PostgreSQL.
 *
 * <p>Reemplaza al stub en memoria, que tenía dos problemas que ninguna cantidad
 * de cuidado en el código podía arreglar: los pendientes se perdían si el
 * proceso se caía, y con varias instancias cada una tenía su propio outbox.
 *
 * <h2>{@code enqueue} es transaccional de verdad</h2>
 * Es un {@code INSERT} con {@code JdbcTemplate}, así que participa de la
 * transacción del caso de uso sin hacer nada especial. Eso arregla el problema
 * inverso al que documentaba el stub: no se pierde un evento, se dejaba de
 * emitir uno <em>fantasma</em>. Dos confirmaciones concurrentes en las que una
 * pierde el conflicto optimista ya no dejan dos eventos, porque el
 * {@code INSERT} del perdedor se va con su rollback.
 *
 * <h2>{@code pollPending} reclama, no sólo lee</h2>
 * Una sola sentencia hace las dos cosas: el {@code SELECT ... FOR UPDATE SKIP
 * LOCKED} elige y el {@code UPDATE} marca. {@code SKIP LOCKED} es lo que
 * permite que N instancias despachen en paralelo sin lock distribuido: cada una
 * se lleva un subconjunto disjunto y ninguna espera a la otra. Corre en su
 * propia transacción ({@code REQUIRES_NEW}) porque el reclamo tiene que quedar
 * comprometido antes de intentar publicar; si viviera en la transacción del
 * llamador, un fallo posterior lo desharía y el mensaje volvería a estar
 * disponible mientras todavía se lo está enviando.
 *
 * <p>Que el reclamo sea explícito es un prerrequisito del endpoint de replay:
 * sin él, el endpoint y el relay tomarían el mismo mensaje y lo publicarían dos
 * veces.
 *
 * <h2>El reintento no es en caliente</h2>
 * {@code markFailed} agenda {@code next_attempt_at} con backoff exponencial y
 * jitter, y {@code pollPending} filtra por esa columna. Antes los cinco
 * intentos se consumían en veinte segundos contra un destino que seguía caído,
 * así que un blip de medio minuto mandaba el outbox entero a la dead letter.
 */
public class JdbcEventOutbox implements EventOutboxPort, OutboxAdmin {

    private static final Logger log = LoggerFactory.getLogger(JdbcEventOutbox.class);

    /** Misma clave que usa el filtro HTTP: el correlation id se toma del MDC. */
    private static final String MDC_CORRELATION_ID = "correlationId";

    private static final String INSERT = """
            INSERT INTO outbox_message
                (id, type, schema_version, subject, payload, correlation_id,
                 occurred_at, enqueued_at, status, attempts, next_attempt_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, 'PENDING', 0, ?)
            """;

    /**
     * Reclamo atómico.
     *
     * <p>Elegibles: los {@code PENDING} con la espera vencida y los
     * {@code IN_FLIGHT} cuyo lease expiró —esos son los que dejó un proceso que
     * murió entre el reclamo y la publicación—.
     *
     * <p>{@code ORDER BY sequence} dentro del subselect: el relay entrega en el
     * orden en que los hechos ocurrieron.
     */
    private static final String CLAIM = """
            UPDATE outbox_message SET status = 'IN_FLIGHT', claimed_at = ?
            WHERE id IN (
                SELECT id FROM outbox_message
                WHERE next_attempt_at <= ?
                  AND (status = 'PENDING' OR (status = 'IN_FLIGHT' AND claimed_at < ?))
                ORDER BY sequence
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, type, schema_version, subject, sequence, payload,
                      correlation_id, occurred_at, enqueued_at, attempts, status
            """;

    /**
     * El reclamo de la sonda: uno solo, <strong>al azar</strong>.
     *
     * <p>{@code ORDER BY random()} y no {@code ORDER BY sequence} a propósito.
     * La sonda del circuito semiabierto se dispara una y otra vez durante toda
     * la caída; con el orden natural sería siempre el mismo mensaje el que
     * arriesga, y con un backlog chico ese mensaje es el más viejo y el más
     * importante. Repartir el riesgo entre los pendientes es lo que evita que
     * la recuperación del circuito se pague con una notificación concreta.
     */
    private static final String CLAIM_PROBE = """
            UPDATE outbox_message SET status = 'IN_FLIGHT', claimed_at = ?
            WHERE id = (
                SELECT id FROM outbox_message
                WHERE next_attempt_at <= ?
                  AND (status = 'PENDING' OR (status = 'IN_FLIGHT' AND claimed_at < ?))
                ORDER BY random()
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, type, schema_version, subject, sequence, payload,
                      correlation_id, occurred_at, enqueued_at, attempts, status
            """;

    private static final RowMapper<OutboxMessage> MESSAGE_MAPPER = (rs, row) -> new OutboxMessage(
            rs.getString("id"),
            rs.getString("type"),
            rs.getInt("schema_version"),
            rs.getString("subject"),
            rs.getLong("sequence"),
            rs.getString("payload"),
            rs.getString("correlation_id"),
            Utc.read(rs, "occurred_at"),
            Utc.read(rs, "enqueued_at"),
            rs.getInt("attempts"),
            OutboxStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbcTemplate;
    private final DomainEventPayloadMapper payloadMapper;
    private final OutboxProperties properties;
    private final Clock clock;

    public JdbcEventOutbox(JdbcTemplate jdbcTemplate,
                           DomainEventPayloadMapper payloadMapper,
                           OutboxProperties properties,
                           Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.payloadMapper = Objects.requireNonNull(payloadMapper);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    // -----------------------------------------------------------------
    // EventOutboxPort
    // -----------------------------------------------------------------

    @Override
    public void enqueue(Collection<DomainEvent> events) {
        Objects.requireNonNull(events, "Los eventos son obligatorios");
        if (events.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        // El correlation id se toma acá, en el hilo del pedido: el relay corre
        // en otro hilo y en otro momento, así que si no se captura ahora la
        // traza se corta justo en el salto a lo asincrónico.
        String correlationId = MDC.get(MDC_CORRELATION_ID);

        for (DomainEvent event : events) {
            jdbcTemplate.update(INSERT,
                    UUID.randomUUID(),
                    event.eventType(),
                    payloadMapper.schemaVersion(event),
                    event.reservationId().toString(),
                    // Serializado ACÁ, dentro de la transacción del caso de
                    // uso: lo que se publique va a ser exactamente lo que pasó,
                    // aunque el código cambie entre el encolado y el despacho.
                    payloadMapper.toPayload(event),
                    correlationId,
                    Utc.param(event.occurredAt()),
                    Utc.param(now),
                    Utc.param(now));
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxMessage> pollPending(int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages debe ser positivo");
        }
        Instant now = clock.instant();
        java.time.LocalDateTime nowUtc = Utc.param(now);
        java.time.LocalDateTime leaseLimit = Utc.param(now.minus(properties.claimLease()));
        List<OutboxMessage> claimed = jdbcTemplate.query(CLAIM, MESSAGE_MAPPER,
                nowUtc, nowUtc, leaseLimit, maxMessages);
        // El ORDER BY del subselect decide CUÁLES se reclaman, no en qué orden
        // vuelven: el RETURNING de un UPDATE los devuelve en el orden en que el
        // motor los tocó. Se reordena acá porque el relay se apoya en este
        // orden para no adelantar un mensaje de una reserva cuyo mensaje
        // anterior todavía no salió.
        return claimed.stream()
                .sorted(Comparator.comparingLong(OutboxMessage::sequence))
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxMessage> pollProbe() {
        Instant now = clock.instant();
        java.time.LocalDateTime nowUtc = Utc.param(now);
        java.time.LocalDateTime leaseLimit = Utc.param(now.minus(properties.claimLease()));
        return jdbcTemplate.query(CLAIM_PROBE, MESSAGE_MAPPER, nowUtc, nowUtc, leaseLimit);
    }

    /**
     * {@inheritDoc}
     *
     * <p>El {@code AND status = 'IN_FLIGHT'} no es defensivo, es correctivo.
     * Sin él —como estaba— la marca no verifica que el reclamo siga siendo
     * nuestro, y con un lease que puede vencer en medio de un lote, otra
     * instancia re-reclama un mensaje todavía en vuelo. La publicación doble
     * la absorbe la deduplicación del consumidor; lo que no se absorbe es que
     * el {@code markFailed} tardío de la primera instancia devuelva a
     * {@code PENDING} un mensaje que la segunda ya despachó —reenvío
     * indefinido— y que {@code attempts} se cuente dos veces, acelerando su
     * llegada a la dead letter.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDispatched(String messageId) {
        Objects.requireNonNull(messageId, "messageId es obligatorio");
        int updated = jdbcTemplate.update("""
                UPDATE outbox_message
                   SET status = 'DISPATCHED', attempts = attempts + 1,
                       claimed_at = NULL, last_error = NULL
                 WHERE id = ?::uuid AND status = 'IN_FLIGHT'
                """, messageId);
        if (updated == 0) {
            log.warn("El mensaje {} ya no estaba reclamado por este relay: no se marca como despachado", messageId);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String messageId, String error, OutboxFailure failure) {
        Objects.requireNonNull(messageId, "messageId es obligatorio");
        Objects.requireNonNull(failure, "La clasificación del fallo es obligatoria");

        Instant now = clock.instant();
        Attempt attempt = currentAttempt(messageId);
        if (attempt == null) {
            log.warn("Se intentó marcar como fallido el mensaje {}, que ya no está en el outbox", messageId);
            return;
        }
        if (attempt.status() != OutboxStatus.IN_FLIGHT) {
            // El reclamo venció y otro lo tomó —o ya lo despachó—. Contarle el
            // intento ahora sería contarlo dos veces.
            log.warn("El mensaje {} está en {} y no en IN_FLIGHT: el reclamo ya no es nuestro",
                    messageId, attempt.status());
            return;
        }
        int attempts = attempt.attempts() + 1;

        String reason = deadLetterReason(failure, attempts, attempt.enqueuedAt(), now);
        if (reason != null) {
            log.error("El mensaje {} ({}) va a la dead letter del productor: {}. Último error: {}",
                    messageId, attempt.type(), reason, error);
            jdbcTemplate.update("""
                    UPDATE outbox_message
                       SET status = 'FAILED', attempts = ?, claimed_at = NULL,
                           failed_at = ?, last_error = ?
                     WHERE id = ?::uuid AND status = 'IN_FLIGHT'
                    """, attempts, Utc.param(now), trim(error), messageId);
            return;
        }

        Instant nextAttempt = now.plus(backoff(attempts));
        int updated = jdbcTemplate.update("""
                UPDATE outbox_message
                   SET status = 'PENDING', attempts = ?, claimed_at = NULL,
                       next_attempt_at = ?, last_error = ?
                 WHERE id = ?::uuid AND status = 'IN_FLIGHT'
                """, attempts, Utc.param(nextAttempt), trim(error), messageId);
        if (updated == 0) {
            log.warn("El mensaje {} ya no estaba reclamado por este relay: no se le cuenta el intento fallido",
                    messageId);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Collection<String> messageIds) {
        Objects.requireNonNull(messageIds, "Los ids son obligatorios");
        if (messageIds.isEmpty()) {
            return;
        }
        // Sin tocar 'attempts': estos mensajes no fallaron, sólo esperan a que
        // salga el mensaje anterior de su reserva. Gastarles un intento los
        // acercaría a la dead letter por un problema ajeno.
        for (String messageId : messageIds) {
            jdbcTemplate.update("""
                    UPDATE outbox_message SET status = 'PENDING', claimed_at = NULL
                     WHERE id = ?::uuid AND status = 'IN_FLIGHT'
                    """, messageId);
        }
    }

    // -----------------------------------------------------------------
    // OutboxAdmin
    // -----------------------------------------------------------------

    @Override
    public OutboxStats stats() {
        // Una consulta y no cuatro: el recolector de métricas raspa cada pocos
        // segundos y estas cuentas van por el índice parcial.
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FILTER (WHERE status IN ('PENDING', 'IN_FLIGHT'))  AS pendientes,
                       count(*) FILTER (WHERE status = 'FAILED')                   AS muertos,
                       count(*) FILTER (WHERE status = 'DISPATCHED')               AS despachados,
                       min(enqueued_at) FILTER (WHERE status IN ('PENDING', 'IN_FLIGHT')) AS mas_viejo
                  FROM outbox_message
                """, (rs, row) -> {
            Instant oldest = Utc.read(rs, "mas_viejo");
            Duration lag = oldest == null
                    ? Duration.ZERO
                    : Duration.between(oldest, clock.instant());
            return new OutboxStats(rs.getLong("pendientes"), rs.getLong("muertos"),
                    lag.isNegative() ? Duration.ZERO : lag, rs.getLong("despachados"));
        });
    }

    @Override
    public List<DeadOutboxMessage> deadLetter(int limit) {
        int capped = Math.clamp(limit, 1, 500);
        return jdbcTemplate.query("""
                SELECT id, type, subject, sequence, attempts, enqueued_at, failed_at, last_error
                  FROM outbox_message
                 WHERE status = 'FAILED'
                 ORDER BY failed_at DESC
                 LIMIT ?
                """, (rs, row) -> new DeadOutboxMessage(
                rs.getString("id"),
                rs.getString("type"),
                rs.getString("subject"),
                rs.getLong("sequence"),
                rs.getInt("attempts"),
                Utc.read(rs, "enqueued_at"),
                Utc.read(rs, "failed_at"),
                rs.getString("last_error")), capped);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean replay(String messageId) {
        Objects.requireNonNull(messageId, "messageId es obligatorio");
        int updated = jdbcTemplate.update(RESET_DEAD + " AND id = ?::uuid", Utc.param(clock.instant()), messageId);
        if (updated > 0) {
            log.info("Mensaje {} reencolado desde la dead letter del productor", messageId);
        }
        return updated > 0;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int replayAll() {
        int updated = jdbcTemplate.update(RESET_DEAD, Utc.param(clock.instant()));
        log.info("{} mensajes reencolados desde la dead letter del productor", updated);
        return updated;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeDispatchedBefore(Instant limit) {
        Objects.requireNonNull(limit, "El límite es obligatorio");
        return jdbcTemplate.update(
                "DELETE FROM outbox_message WHERE status = 'DISPATCHED' AND enqueued_at < ?",
                Utc.param(limit));
    }

    /**
     * Reencolado: intentos en cero y elegible ya.
     *
     * <p>Los intentos se resetean porque el replay ocurre <em>después</em> de
     * arreglar la causa: arrancar con el contador agotado lo mandaría de vuelta
     * a la dead letter en el primer tropiezo.
     */
    private static final String RESET_DEAD = """
            UPDATE outbox_message
               SET status = 'PENDING', attempts = 0, next_attempt_at = ?,
                   claimed_at = NULL, failed_at = NULL
             WHERE status = 'FAILED'
            """;

    // -----------------------------------------------------------------
    // Reintentos
    // -----------------------------------------------------------------

    /** @return el motivo por el que el mensaje muere, o {@code null} si se reintenta */
    private String deadLetterReason(OutboxFailure failure, int attempts, Instant enqueuedAt, Instant now) {
        if (failure == OutboxFailure.PERMANENT) {
            return "fallo permanente, no se reintenta";
        }
        if (attempts >= properties.maxAttempts()) {
            return "agotó los %d intentos".formatted(properties.maxAttempts());
        }
        Duration age = Duration.between(enqueuedAt, now);
        if (age.compareTo(properties.retryCeiling()) > 0) {
            return "lleva %d min en reintentos y supera el techo de %d min"
                    .formatted(age.toMinutes(), properties.retryCeiling().toMinutes());
        }
        return null;
    }

    /**
     * Espera exponencial con jitter completo.
     *
     * <p>El jitter se sortea sobre el intervalo entero y no como un porcentaje
     * del valor: es lo que descorrelaciona de verdad a N instancias que
     * fallaron al mismo tiempo contra el mismo destino caído, en lugar de
     * dejarlas reintentando todas juntas con una desviación despreciable.
     */
    private Duration backoff(int attempts) {
        long initialMillis = properties.initialBackoff().toMillis();
        long maxMillis = properties.maxBackoff().toMillis();
        int exponent = Math.min(attempts - 1, 32);
        long exponential = Math.min(maxMillis, initialMillis * (1L << exponent));
        // Nunca menos que el backoff inicial: con jitter puro, un sorteo bajo
        // dejaría el reintento casi en caliente, que es lo que se está evitando.
        long jittered = initialMillis + ThreadLocalRandom.current()
                .nextLong(Math.max(1L, exponential - initialMillis + 1L));
        return Duration.ofMillis(Math.min(maxMillis, jittered));
    }

    private Attempt currentAttempt(String messageId) {
        return DataAccessUtils.singleResult(jdbcTemplate.query("""
                SELECT type, attempts, enqueued_at, status FROM outbox_message WHERE id = ?::uuid
                """, (rs, row) -> new Attempt(
                rs.getString("type"), rs.getInt("attempts"), Utc.read(rs, "enqueued_at"),
                OutboxStatus.valueOf(rs.getString("status"))),
                messageId));
    }

    /** La columna es {@code VARCHAR(500)}: un stack trace entero no entra ni aporta. */
    private static String trim(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 497) + "...";
    }

    private record Attempt(String type, int attempts, Instant enqueuedAt, OutboxStatus status) {
    }
}
