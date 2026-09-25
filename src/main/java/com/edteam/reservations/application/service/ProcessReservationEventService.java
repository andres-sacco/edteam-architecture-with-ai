package com.edteam.reservations.application.service;

import com.edteam.reservations.application.exception.UnprocessableEventException;
import com.edteam.reservations.application.notification.NotificationDelivery;
import com.edteam.reservations.application.port.in.EventProcessingOutcome;
import com.edteam.reservations.application.port.in.InboundEvent;
import com.edteam.reservations.application.port.in.ProcessReservationEventUseCase;
import com.edteam.reservations.application.port.out.NotificationDeliveryPort;
import com.edteam.reservations.application.port.out.ProcessedMessagePort;
import com.edteam.reservations.domain.event.ReservationCancelled;
import com.edteam.reservations.domain.event.ReservationConfirmed;
import com.edteam.reservations.domain.event.ReservationCreated;
import com.edteam.reservations.domain.event.ReservationModified;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica un hecho de reserva recibido por mensajería.
 *
 * <p>Toda la lógica del consumo vive acá y no en el adaptador del broker, que
 * es lo que permite probar la idempotencia, el desorden y la clasificación de
 * fallos con tests unitarios y sin un broker levantado.
 *
 * <h2>Idempotencia: la reserva del id va primero y la hace la base</h2>
 * El {@code claim} es un {@code INSERT} cuya PK es el {@code messageId}. La
 * decisión la toma la base y no una consulta previa de la aplicación, que
 * tendría una carrera entre el {@code SELECT} y el {@code INSERT} con dos
 * consumidores concurrentes. Y ocurre <b>antes</b> de cualquier otra cosa,
 * incluida la decisión de reintentar: es lo único que contiene la
 * multiplicación de un mensaje que se reencola varias veces.
 *
 * <p>El registro de la deduplicación y el efecto se escriben en la misma
 * transacción. Si el efecto falla, el id no queda marcado como visto y el
 * mensaje se puede reintentar; si se marcara aparte, un fallo del efecto
 * dejaría el mensaje «procesado» sin haberse procesado.
 *
 * <h2>Desorden: se detecta y se aplica, nunca se descarta</h2>
 * Ver {@link EventProcessingOutcome#APPLIED_OUT_OF_ORDER}.
 */
@Service
public class ProcessReservationEventService implements ProcessReservationEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessReservationEventService.class);

    /** Los cuatro hechos del contrato. Un tipo fuera de esta lista no es procesable. */
    private static final Set<String> KNOWN_TYPES = Set.of(
            ReservationCreated.TYPE, ReservationConfirmed.TYPE,
            ReservationModified.TYPE, ReservationCancelled.TYPE);

    /** Versión mayor del esquema que este consumidor entiende. */
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    /**
     * Más allá de esto la notificación deja de tener sentido: un «tu reserva se
     * confirmó» que llega tres días tarde es peor que un ticket.
     *
     * <p>La ventana vive acá y no como {@code x-message-ttl} de la cola a
     * propósito. Con un ciclo de retry, una TTL de cola o reinicia el reloj en
     * cada vuelta —y no acota nada— o dead-letterea el mensaje hacia la misma
     * cola de espera de la que salió, que es un bucle. Acá es una regla
     * explícita, visible y con un test.
     */
    private static final Duration FRESHNESS_WINDOW = Duration.ofHours(24);

    private final ProcessedMessagePort processedMessages;
    private final NotificationDeliveryPort notificationDeliveries;
    private final Clock clock;

    public ProcessReservationEventService(
            ProcessedMessagePort processedMessages, NotificationDeliveryPort notificationDeliveries, Clock clock) {
        this.processedMessages = Objects.requireNonNull(processedMessages);
        this.notificationDeliveries = Objects.requireNonNull(notificationDeliveries);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional(timeout = 2)
    public EventProcessingOutcome process(InboundEvent event) {
        Objects.requireNonNull(event, "El evento es obligatorio");

        // 1. Deduplicar. Primero de todo, y antes de la validación: un
        //    duplicado de un mensaje ya aplicado se confirma sin volver a
        //    mirarlo, y sin arriesgar que una validación más estricta que la de
        //    ayer lo mande a la DLQ cuando su efecto ya está hecho.
        if (!processedMessages.claim(event.messageId(), event.type(), event.subject(), event.sequence())) {
            log.atInfo()
                    .addKeyValue("event", "consumer.duplicate")
                    .addKeyValue("eventType", event.type())
                    .addKeyValue("subject", event.subject())
                    .addKeyValue("messageId", event.messageId())
                    .log("Mensaje duplicado: se confirma sin reprocesar");
            return EventProcessingOutcome.DUPLICATE;
        }

        // 2. Validar. Lo que no se puede procesar va a la DLQ sin reintentos.
        reject(event);

        // 3. Detectar desorden ANTES de escribir el efecto, para que el
        //    sequence de este mensaje no cuente como "ya aplicado".
        long lastApplied = processedMessages.lastAppliedSequence(event.subject(), event.messageId());
        boolean outOfOrder = lastApplied != Long.MIN_VALUE && event.sequence() < lastApplied;

        // 4. El efecto.
        notificationDeliveries.deliver(new NotificationDelivery(
                event.messageId(),
                event.type(),
                event.subject(),
                event.userId(),
                event.sequence(),
                event.occurredAt()));

        // El payload no se loguea en INFO: lleva ruta y fecha de viaje, que
        // atadas a un usuario son dato personal. Acá quedan type, subject y
        // messageId, que es lo que hace falta para operar.
        log.atInfo()
                .addKeyValue("event", "consumer.applied")
                .addKeyValue("eventType", event.type())
                .addKeyValue("subject", event.subject())
                .addKeyValue("messageId", event.messageId())
                .addKeyValue("sequence", event.sequence())
                .addKeyValue("userId", event.userId())
                .log("Evento aplicado");

        if (outOfOrder) {
            // Se aplica igual y se deja constancia. Descartarlo sería perder
            // una notificación en silencio.
            log.atWarn()
                    .addKeyValue("event", "consumer.out_of_order")
                    .addKeyValue("eventType", event.type())
                    .addKeyValue("subject", event.subject())
                    .addKeyValue("messageId", event.messageId())
                    .addKeyValue("sequence", event.sequence())
                    .addKeyValue("lastAppliedSequence", lastApplied)
                    .log("Evento fuera de orden: se aplica igual y se registra como anomalía");
            return EventProcessingOutcome.APPLIED_OUT_OF_ORDER;
        }
        return EventProcessingOutcome.APPLIED;
    }

    /** Lo no procesable, temprano y con el motivo puesto. */
    private void reject(InboundEvent event) {
        if (!KNOWN_TYPES.contains(event.type())) {
            // Un tipo desconocido no es un mensaje venenoso: es un binding
            // demasiado amplio del lado del consumidor. Se descarta con su
            // motivo en lugar de reintentarse para siempre.
            throw new UnprocessableEventException("Tipo de evento desconocido: '%s'".formatted(event.type()));
        }
        if (event.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new UnprocessableEventException("Versión de esquema %d no soportada para '%s' (se entiende la %d)"
                    .formatted(event.schemaVersion(), event.type(), SUPPORTED_SCHEMA_VERSION));
        }
        Duration age = Duration.between(event.occurredAt(), clock.instant());
        if (age.compareTo(FRESHNESS_WINDOW) > 0) {
            throw new UnprocessableEventException(
                    ("El hecho '%s' de la reserva %s ocurrió hace %d h y supera la ventana de %d h: "
                                    + "notificarlo ahora es peor que no notificarlo")
                            .formatted(event.type(), event.subject(), age.toHours(), FRESHNESS_WINDOW.toHours()));
        }
    }
}
