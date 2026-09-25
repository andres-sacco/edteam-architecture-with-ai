package com.edteam.reservations.infrastructure.adapter.out.messaging;

/**
 * Nombres de la topología, en un solo lugar.
 *
 * <p>Están en infraestructura y no en configuración de negocio porque son el
 * contrato operativo con el broker. Lo único que el productor <em>necesita</em>
 * conocer es el exchange: las colas son del consumidor, y se declaran acá sólo
 * para que {@code docker compose up} deje el circuito andando de punta a punta.
 */
public final class MessagingTopology {

    /**
     * Topic exchange al que publica el relay.
     *
     * <p>Un exchange y no una cola: el productor no nombra a ningún
     * destinatario y no sabe si hay uno, tres o ninguno. Una cola directa haría
     * exactamente lo mismo hoy y obligaría a tocar este código el día que
     * aparezca un segundo interesado.
     */
    public static final String EVENTS_EXCHANGE = "reservations.events";

    /** Cola de trabajo del consumidor. */
    public static final String CONSUMER_QUEUE = "notifications.reservation-events";

    /**
     * Cola de espera del reintento. No tiene consumidor: la TTL la devuelve
     * sola por su DLX.
     *
     * <p>El backoff vive en la TTL de esta cola y no en un {@code sleep} del
     * consumidor: dormir dentro del handler ocupa el canal y frena los
     * mensajes sanos que venían detrás del venenoso.
     */
    public static final String RETRY_QUEUE = CONSUMER_QUEUE + ".retry";

    /** Dead letter del consumidor: lo que no se pudo procesar. */
    public static final String DLQ = CONSUMER_QUEUE + ".dlq";

    /** Fanout hacia la cola de espera. */
    public static final String RETRY_EXCHANGE = "notifications.retry";

    /** Fanout atado a la cola principal: por acá vuelven los mensajes que esperaron. */
    public static final String REQUEUE_EXCHANGE = "notifications.requeue";

    /** Fanout hacia la DLQ. */
    public static final String DLQ_EXCHANGE = "notifications.dlq";

    /**
     * Binding del consumidor.
     *
     * <p>{@code reservation.*} y nunca {@code reservation.#}: en un topic
     * exchange {@code *} matchea exactamente una palabra, y eso es lo que hace
     * que una versión mayor futura ({@code reservation.created.v2}) no llegue a
     * los consumidores viejos. Con {@code #} recibirían v1 y v2 y duplicarían
     * todo.
     */
    public static final String CONSUMER_BINDING = "reservation.*";

    /** Header con la vuelta de reintento en curso. */
    public static final String ATTEMPT_HEADER = "x-attempt";

    /** Header con el motivo por el que el mensaje terminó en la DLQ. */
    public static final String DEAD_LETTER_REASON_HEADER = "x-dead-letter-reason";

    /** Header con la entidad a la que se refiere el mensaje (el id de la reserva). */
    public static final String SUBJECT_HEADER = "x-subject";

    public static final String SCHEMA_VERSION_HEADER = "x-schema-version";
    public static final String SEQUENCE_HEADER = "x-sequence";

    private MessagingTopology() {}
}
