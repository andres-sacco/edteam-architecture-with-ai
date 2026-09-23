package com.edteam.reservations.application.outbox;

/** Estado de un mensaje del outbox. */
public enum OutboxStatus {

    /** Pendiente de envío, o a reintentar cuando venza {@code next_attempt_at}. */
    PENDING,

    /**
     * Reclamado por un despachador y todavía sin resolver.
     *
     * <p>Existe para que el reclamo sea explícito: {@code pollPending} ya no
     * devuelve mensajes sin marcarlos, así que dos despachadores concurrentes
     * —el {@code @Scheduled} y el replay del endpoint de gestión, por ejemplo—
     * no pueden tomar el mismo mensaje. El reclamo tiene <em>lease</em>: si el
     * proceso muere en este estado, al vencer vuelve a ser elegible.
     */
    IN_FLIGHT,

    /** Publicado con éxito y confirmado por el broker. */
    DISPATCHED,

    /**
     * Dead letter del productor: no se pudo publicar.
     *
     * <p>A diferencia de antes, no es un estado del que nadie se entera. Tiene
     * métrica ({@code reservations.outbox.dead}, que alerta con &gt; 0),
     * listado y reproceso en el endpoint {@code outbox} del puerto de gestión.
     *
     * <p>Es la dead letter del <em>productor</em>, distinta de la DLQ del
     * consumidor en el broker: una dice "no pudimos publicar" y la otra "no
     * pudieron procesar". Si lo que está caído es el broker, un dead letter
     * dentro del broker es inalcanzable justo cuando hace falta.
     */
    FAILED
}
