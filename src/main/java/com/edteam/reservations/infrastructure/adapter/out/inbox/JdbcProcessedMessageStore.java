package com.edteam.reservations.infrastructure.adapter.out.inbox;

import com.edteam.reservations.application.port.out.ProcessedMessagePort;
import com.edteam.reservations.infrastructure.jdbc.Utc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Deduplicación del consumidor sobre PostgreSQL.
 *
 * <h2>La decisión la toma la base, no la aplicación</h2>
 * El {@code claim} es un {@code INSERT} contra una PK. La alternativa obvia
 * —{@code SELECT} y después {@code INSERT}— tiene una carrera: dos consumidores
 * que reciben el mismo duplicado al mismo tiempo leen «no está» los dos y
 * procesan los dos. Con el {@code INSERT} primero, uno gana y el otro recibe la
 * violación de unicidad, que es la señal de «ya está aplicado».
 *
 * <p>Se usa {@code ON CONFLICT DO NOTHING} y se mira la cantidad de filas
 * afectadas en lugar de atrapar {@link DuplicateKeyException}: una excepción de
 * base marca la transacción como <em>rollback only</em> en PostgreSQL, y el
 * efecto que vendría después ya no podría escribirse.
 *
 * <p>Participa de la transacción del caso de uso, que es lo que hace que el
 * registro de la deduplicación y el efecto se confirmen juntos: si el efecto
 * falla, el id no queda marcado como visto y el mensaje se puede reintentar.
 *
 * <p>JDBC y no JPA: es un insert, un max y un delete. Una entidad agregaría un
 * contexto de persistencia que puede retrasar el insert hasta el flush —justo
 * lo que no queremos en una deduplicación— y un mapeo que nadie consulta.
 */
@Repository
public class JdbcProcessedMessageStore implements ProcessedMessagePort {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public JdbcProcessedMessageStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public boolean claim(String messageId, String type, String subject, long sequence) {
        Objects.requireNonNull(messageId, "messageId es obligatorio");
        int inserted = jdbcTemplate.update("""
                INSERT INTO processed_message (message_id, type, subject, sequence, processed_at)
                VALUES (?::uuid, ?, ?, ?, ?)
                ON CONFLICT (message_id) DO NOTHING
                """, messageId, type, subject, sequence, Utc.param(clock.instant()));
        return inserted == 1;
    }

    @Override
    public long lastAppliedSequence(String subject, String excludingMessageId) {
        Objects.requireNonNull(subject, "subject es obligatorio");
        Long max = jdbcTemplate.queryForObject("""
                SELECT max(sequence) FROM processed_message
                 WHERE subject = ? AND message_id <> ?::uuid
                """, Long.class, subject, excludingMessageId);
        return max == null ? Long.MIN_VALUE : max;
    }

    @Override
    public int purgeProcessedBefore(Instant limit) {
        Objects.requireNonNull(limit, "El límite es obligatorio");
        return jdbcTemplate.update(
                "DELETE FROM processed_message WHERE processed_at < ?", Utc.param(limit));
    }
}
