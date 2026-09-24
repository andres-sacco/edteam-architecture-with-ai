package com.edteam.reservations.application.port.out;

import com.edteam.reservations.application.outbox.OutboxFailure;
import com.edteam.reservations.application.outbox.OutboxMessage;
import com.edteam.reservations.domain.event.DomainEvent;

import java.util.Collection;
import java.util.List;

/**
 * Puerto de salida hacia el almacenamiento del outbox de eventos.
 *
 * <p>Separado de {@link EventPublisherPort} a propósito: uno guarda el hecho
 * (rápido, local, transaccional) y el otro lo publica afuera (lento, remoto,
 * falible). Esa separación es la que desacopla las reservas del destino, y es
 * también la que hace que un broker caído no tumbe el servicio: los eventos se
 * acumulan acá y salen cuando el broker vuelve.
 */
public interface EventOutboxPort {

    /**
     * Encola los eventos para publicarlos más tarde.
     *
     * <p>Se ejecuta en la misma transacción que la escritura de la reserva: si
     * la reserva no se guarda, el evento tampoco. Vale en los dos sentidos, y
     * el sentido inverso es el que más cuesta ver: sin esta garantía, dos
     * confirmaciones concurrentes en las que una pierde el conflicto optimista
     * dejan <em>dos</em> eventos y el usuario recibe dos avisos por una sola
     * confirmación.
     */
    void enqueue(Collection<DomainEvent> events);

    /**
     * <b>Reclama</b> hasta {@code maxMessages} mensajes elegibles y los
     * devuelve.
     *
     * <p>Reclamar, no sólo leer: la implementación marca los mensajes como
     * tomados antes de devolverlos, de modo que dos despachadores concurrentes
     * —varias instancias, o el {@code @Scheduled} y el replay del endpoint de
     * gestión— nunca reciban el mismo mensaje. En PostgreSQL eso es
     * {@code SELECT ... FOR UPDATE SKIP LOCKED} más el cambio de estado en la
     * misma sentencia.
     *
     * <p>Elegible significa: pendiente <b>y</b> con su espera de reintento
     * vencida. Un mensaje que acaba de fallar no vuelve en la corrida
     * siguiente.
     *
     * <p>Los mensajes vienen ordenados por {@code sequence}, y el reclamo tiene
     * <em>lease</em>: si el proceso muere entre el reclamo y la publicación, el
     * mensaje vuelve a ser elegible al vencer.
     */
    List<OutboxMessage> pollPending(int maxMessages);

    /** Marca el mensaje como publicado con éxito. */
    void markDispatched(String messageId);

    /**
     * Registra un intento fallido.
     *
     * <p>Con {@link OutboxFailure#TRANSIENT} agenda el próximo intento con
     * backoff exponencial y jitter, y sólo lo da por muerto al agotar los
     * intentos o al superar el techo de tiempo configurado. Con
     * {@link OutboxFailure#PERMANENT} lo deja en la dead letter de inmediato:
     * insistir con un payload que no serializa gasta el despachador sin
     * cambiar el resultado.
     */
    void markFailed(String messageId, String error, OutboxFailure failure);

    /**
     * Devuelve mensajes reclamados al estado pendiente <b>sin contarlos como
     * intento</b>.
     *
     * <p>Lo usa el despachador con los mensajes que decidió no enviar en este
     * lote porque otro mensaje de la misma reserva falló: no fallaron, así que
     * gastarles un intento los acercaría a la dead letter por un problema
     * ajeno.
     */
    void release(Collection<String> messageIds);

    /**
     * Reclama <strong>un</strong> mensaje pendiente, elegido al azar, para
     * usarlo como llamada de prueba contra un destino que se cree caído.
     *
     * <p>Al azar y no el más viejo, que es lo que devolvería
     * {@link #pollPending(int)}. La sonda de un circuito semiabierto se
     * dispara una y otra vez durante toda la caída, y si siempre tomara el
     * mismo mensaje —el más antiguo— sería siempre el mismo el que arriesga.
     * Con un backlog chico, ese mensaje es además el que más importa: el
     * {@code reservation.created} de la reserva más vieja.
     *
     * <p>Quien la use tiene que devolver el mensaje con {@link #release} si la
     * prueba falla: una sonda no es un intento de entrega y no puede gastar
     * uno.
     *
     * @return una lista de 0 o 1 mensajes
     */
    List<OutboxMessage> pollProbe();
}
