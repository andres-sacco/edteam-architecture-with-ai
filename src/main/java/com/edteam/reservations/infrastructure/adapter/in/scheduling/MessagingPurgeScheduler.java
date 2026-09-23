package com.edteam.reservations.infrastructure.adapter.in.scheduling;

import com.edteam.reservations.application.port.out.ProcessedMessagePort;
import com.edteam.reservations.infrastructure.adapter.out.outbox.OutboxAdmin;
import com.edteam.reservations.infrastructure.config.OutboxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Purga diaria de las dos tablas de mensajería.
 *
 * <p>Sin esto ninguna de las dos deja de crecer, y el daño es gradual: la
 * métrica de retenidos lo muestra mucho antes de que duela. El índice parcial
 * hace que el reclamo del relay cueste en función de la cola y no del
 * histórico, así que lo que la purga protege es el espacio y el tiempo de los
 * conteos, no el camino caliente.
 *
 * <h2>Dos ventanas distintas, y el orden importa</h2>
 * Los mensajes despachados se borran a los {@code retention} días (7 por
 * defecto). Los ids deduplicados se conservan <b>más</b>: mientras un mensaje
 * pueda reenviarse desde el outbox o desde la DLQ, su id tiene que seguir en
 * {@code processed_message}. Purgarlos con la misma ventana abriría exactamente
 * el agujero que la deduplicación tapa: un replay al día ocho produciría un
 * segundo aviso al usuario.
 */
@Component
@ConditionalOnProperty(name = "reservations.outbox.purge-enabled", havingValue = "true", matchIfMissing = true)
public class MessagingPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(MessagingPurgeScheduler.class);

    /** Margen sobre la retención del outbox. Ver el javadoc de la clase. */
    private static final Duration DEDUP_MARGIN = Duration.ofDays(7);

    private final OutboxAdmin outbox;
    private final ProcessedMessagePort processedMessages;
    private final OutboxProperties properties;
    private final Clock clock;

    public MessagingPurgeScheduler(OutboxAdmin outbox,
                                   ProcessedMessagePort processedMessages,
                                   OutboxProperties properties,
                                   Clock clock) {
        this.outbox = Objects.requireNonNull(outbox);
        this.processedMessages = Objects.requireNonNull(processedMessages);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Scheduled(cron = "${reservations.outbox.purge-cron:0 17 3 * * *}")
    public void purge() {
        try {
            Instant now = clock.instant();
            int dispatched = outbox.purgeDispatchedBefore(now.minus(properties.retention()));
            int processed = processedMessages.purgeProcessedBefore(
                    now.minus(properties.retention().plus(DEDUP_MARGIN)));
            if (dispatched > 0 || processed > 0) {
                log.info("Purga de mensajería: {} mensajes despachados y {} ids deduplicados borrados",
                        dispatched, processed);
            }
        } catch (RuntimeException e) {
            // No se propaga: una purga que falla no puede cancelar las
            // siguientes ejecuciones de la tarea.
            log.error("Error purgando las tablas de mensajería", e);
        }
    }
}
