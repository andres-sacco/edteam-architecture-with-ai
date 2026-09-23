package com.edteam.reservations.infrastructure.adapter.out.inbox;

import com.edteam.reservations.application.notification.NotificationDelivery;
import com.edteam.reservations.application.port.out.NotificationDeliveryPort;
import com.edteam.reservations.infrastructure.jdbc.Utc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.Objects;

/**
 * Efecto del consumidor: una fila por notificación emitida.
 *
 * <p>En el sistema real esto sería el envío por el canal (mail, push, SMS) y su
 * registro. Acá se registra, y el registro alcanza para lo que importa: que el
 * {@code UNIQUE} sobre {@code message_id} convierta un segundo procesamiento
 * del mismo mensaje en un error de base en lugar de en un segundo aviso al
 * usuario. Es la segunda red, debajo de la deduplicación.
 *
 * <p>Sin datos de contacto: el destinatario es el id interno del usuario. El
 * email es dato del dueño del canal y no viaja ni se guarda acá.
 */
@Repository
public class JdbcNotificationDeliveryLog implements NotificationDeliveryPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcNotificationDeliveryLog.class);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public JdbcNotificationDeliveryLog(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void deliver(NotificationDelivery delivery) {
        Objects.requireNonNull(delivery, "La entrega es obligatoria");
        jdbcTemplate.update("""
                INSERT INTO notificacion_entrega
                    (message_id, type, reserva_id, usuario_id, sequence, occurred_at, delivered_at)
                VALUES (?::uuid, ?, ?, ?, ?, ?, ?)
                """,
                delivery.messageId(), delivery.type(), delivery.subject(), delivery.userId(),
                delivery.sequence(), Utc.param(delivery.occurredAt()),
                Utc.param(clock.instant()));

        // El destinatario por id interno, que fuera de nuestra base no es un
        // dato personal. Ni el email ni la ruta del viaje entran acá.
        log.info("[notificaciones] emitida type={} reserva={} usuario={} messageId={}",
                delivery.type(), delivery.subject(), delivery.userId(), delivery.messageId());
    }

    @Override
    public int countFor(String subject) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notificacion_entrega WHERE reserva_id = ?", Integer.class, subject);
        return count == null ? 0 : count;
    }
}
