package com.edteam.reservations.infrastructure.adapter.in.scheduling;

import com.edteam.reservations.application.port.out.ProcessedMessagePort;
import com.edteam.reservations.infrastructure.adapter.out.outbox.OutboxAdmin;
import com.edteam.reservations.infrastructure.config.OutboxProperties;
import com.edteam.reservations.infrastructure.logging.LogFields;
import com.edteam.reservations.infrastructure.logging.MdcTaskDecorator;
import com.edteam.reservations.infrastructure.logging.Throwables;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    /** Nombre de la tarea. Es el valor de la clave {@code job} del MDC. */
    public static final String JOB = "messaging-purge";

    private final OutboxAdmin outbox;
    private final ProcessedMessagePort processedMessages;
    private final OutboxProperties properties;
    private final Clock clock;

    public MessagingPurgeScheduler(
            OutboxAdmin outbox, ProcessedMessagePort processedMessages, OutboxProperties properties, Clock clock) {
        this.outbox = Objects.requireNonNull(outbox);
        this.processedMessages = Objects.requireNonNull(processedMessages);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Scheduled(cron = "${reservations.outbox.purge-cron:0 17 3 * * *}")
    public void purge() {
        String runId = MdcTaskDecorator.adopt(JOB);
        long startedAt = System.nanoTime();
        try {
            Instant now = clock.instant();
            int dispatched = outbox.purgeDispatchedBefore(now.minus(properties.retention()));
            int processed = processedMessages.purgeProcessedBefore(
                    now.minus(properties.retention().plus(DEDUP_MARGIN)));
            if (dispatched > 0 || processed > 0) {
                log.atInfo()
                        .addKeyValue(LogFields.EVENT, LogFields.MESSAGING_PURGE)
                        .addKeyValue(LogFields.JOB_RUN_ID, runId)
                        .addKeyValue("purged.dispatched", dispatched)
                        .addKeyValue("purged.deduplicated", processed)
                        .addKeyValue(LogFields.DURATION_MS, (System.nanoTime() - startedAt) / 1_000_000L)
                        .log("Purga de mensajería");
            }
        } catch (RuntimeException e) {
            // No se propaga: una purga que falla no puede cancelar las
            // siguientes ejecuciones de la tarea.
            log.atError()
                    .addKeyValue(LogFields.EVENT, LogFields.MESSAGING_PURGE)
                    .addKeyValue(LogFields.JOB_RUN_ID, runId)
                    .addKeyValue(LogFields.OUTCOME, "error")
                    .addKeyValue(LogFields.EXCEPTION_CLASS, Throwables.rootClassOf(e))
                    .addKeyValue(LogFields.REASON, Throwables.reasonOf(e))
                    .setCause(e)
                    .log("Error purgando las tablas de mensajería");
        }
    }
}
