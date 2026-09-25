package com.edteam.reservations.infrastructure.logging;

/**
 * El vocabulario del log: nombres de campo y nombres de evento.
 *
 * <p>Existe por una razón concreta y medible: la auditoría encontró 97
 * llamadas a {@code log.*} con el dato interpolado adentro del mensaje y
 * <b>un formato de mensaje por línea</b>. Filtrar por ciudad, por dependencia
 * o por id de reserva requería una expresión regular distinta para cada una.
 * Con el dato en un campo, la consulta es una sola y sobrevive a que alguien
 * reescriba la redacción del mensaje.
 *
 * <p>Son constantes y no un enum porque el consumidor de estos valores es
 * {@code addKeyValue(String, Object)}: un enum obligaría a un {@code .name()}
 * en cada llamada sin comprar nada. Lo que sí compra tenerlas acá es que el
 * test de esquema pueda recorrer el vocabulario y exigir, por cada
 * {@code event}, la lista de campos obligatorios.
 *
 * <h2>Por qué esta clase vive en infraestructura y la aplicación igual la usa</h2>
 * Los nombres de campo <b>no</b> son el formato del log: son el contrato
 * semántico. Que {@code event=reservation.created} termine siendo una clave de
 * un objeto JSON, un campo de un formato binario o nada es decisión del
 * encoder, que se configura en {@code logback-spring.xml}. La capa de
 * aplicación escribe pares clave/valor con {@code org.slf4j} y no importa ni
 * Logback ni {@code net.logstash}.
 *
 * <p>Aun así, {@code application} <b>no</b> importa esta clase: la regla de
 * ArchUnit prohíbe {@code infrastructure.logging..} desde adentro, y romperla
 * sería abrir la puerta al import cómodo de {@code StructuredArguments.kv}.
 * Los servicios de aplicación repiten los literales, que es el precio de la
 * regla; el test de esquema es lo que mantiene las dos listas alineadas.
 */
public final class LogFields {

    private LogFields() {}

    // ------------------------------------------------------------------
    // El campo sobre el que se filtra
    // ------------------------------------------------------------------

    /** Nombre estable del hecho. Vocabulario cerrado: ver las constantes de abajo. */
    public static final String EVENT = "event";

    // ------------------------------------------------------------------
    // Vocabulario de eventos (§1.3 y §3.1 del diseño)
    // ------------------------------------------------------------------

    public static final String HTTP_REQUEST = "http.request";
    public static final String CATALOG_CALL = "catalog.call";
    public static final String CATALOG_FANOUT = "catalog.fanout";
    public static final String CATALOG_RETRY = "catalog.retry";

    public static final String RESERVATION_CREATED = "reservation.created";
    public static final String RESERVATION_CONFIRMED = "reservation.confirmed";
    public static final String RESERVATION_MODIFIED = "reservation.modified";
    public static final String RESERVATION_CANCELLED = "reservation.cancelled";
    public static final String RESERVATION_DUPLICATE = "reservation.duplicate";

    public static final String AUTH_FAILED = "auth.failed";
    public static final String AUTH_DENIED = "auth.denied";
    public static final String RATE_LIMITED = "rate.limited";
    public static final String VERSION_CONFLICT = "reservation.version_conflict";

    public static final String DEGRADED_SERVED = "degraded.served";
    public static final String DEGRADED_EXHAUSTED = "degraded.exhausted";
    public static final String CACHE_DEGRADED = "cache.degraded";
    public static final String CIRCUIT_STATE = "circuit.state";

    public static final String OUTBOX_RELAY_TICK = "outbox.relay.tick";
    public static final String OUTBOX_DISPATCHED = "outbox.dispatched";
    public static final String OUTBOX_DEAD_LETTERED = "outbox.dead_lettered";
    public static final String OUTBOX_REQUEUED = "outbox.requeued";
    public static final String OUTBOX_PURGED = "outbox.purged";
    public static final String MESSAGING_PURGE = "messaging.purge";

    public static final String CONSUMER_APPLIED = "consumer.applied";
    public static final String CONSUMER_DUPLICATE = "consumer.duplicate";
    public static final String CONSUMER_OUT_OF_ORDER = "consumer.out_of_order";
    public static final String CONSUMER_RETRY = "consumer.retry";
    public static final String CONSUMER_DEAD_LETTERED = "consumer.dead_lettered";

    public static final String UNHANDLED_ERROR = "unhandled.error";
    public static final String STARTUP_WIRING = "startup.wiring";

    // ------------------------------------------------------------------
    // Campos comunes y contextuales
    // ------------------------------------------------------------------

    /** Clave del MDC. La escribe {@code CorrelationIdFilter} y la reponen el relay y el consumidor. */
    public static final String CORRELATION_ID = "correlationId";

    /** Clave del MDC con la IP del cliente. Sólo se escribe en {@code http.request} y en la auditoría. */
    public static final String CLIENT_IP = "clientIp";

    /**
     * Clave del MDC con el seudónimo estable del solicitante. <b>Nunca el email.</b>
     * Ver {@link ActorRef}.
     */
    public static final String ACTOR_REF = "actorRef";

    /** Clave del MDC con el nombre de la tarea programada. La pone {@link MdcTaskDecorator}. */
    public static final String JOB = "job";

    public static final String JOB_RUN_ID = "job.runId";

    public static final String HTTP_METHOD = "http.method";
    public static final String HTTP_ROUTE = "http.route";
    public static final String HTTP_STATUS = "http.status";
    public static final String DURATION_MS = "duration_ms";
    public static final String ERROR_CODE = "errorCode";
    public static final String DEGRADED = "degraded";

    public static final String DEPENDENCY = "dependency";
    public static final String OPERATION = "operation";
    public static final String OUTCOME = "outcome";
    public static final String REASON = "reason";
    public static final String CITY_CODE = "cityCode";
    public static final String ATTEMPT = "attempt";
    public static final String MAX_ATTEMPTS = "maxAttempts";
    public static final String BACKOFF_MS = "backoff_ms";

    public static final String RESERVATION_ID = "reservationId";
    public static final String USER_ID = "userId";
    public static final String RESERVATION_VERSION = "reservationVersion";
    public static final String EXPECTED_VERSION = "expectedVersion";
    public static final String ITINERARY_ORIGIN = "itinerary.origin";
    public static final String ITINERARY_DESTINATION = "itinerary.destination";
    public static final String PASSENGERS = "passengers";
    public static final String IDEMPOTENCY_KEY = "idempotencyKey";

    public static final String EVENT_TYPE = "eventType";
    public static final String MESSAGE_ID = "messageId";
    public static final String SUBJECT = "subject";
    public static final String SEQUENCE = "sequence";
    public static final String ATTEMPTS = "attempts";

    public static final String DISPATCHED = "dispatched";
    public static final String FAILED = "failed";
    public static final String DEFERRED = "deferred";
    public static final String SKIP_REASON = "skipReason";
    public static final String PURGED = "purged";

    /** Clase de la excepción. Nunca su mensaje crudo: ver {@link Throwables}. */
    public static final String EXCEPTION_CLASS = "exception.class";

    /** El actor humano de una operación manual sobre datos de producción. */
    public static final String ACTOR = "actor";

    public static final String CACHE = "cache";
    public static final String KEY = "key";
    public static final String COUNT = "count";
}
