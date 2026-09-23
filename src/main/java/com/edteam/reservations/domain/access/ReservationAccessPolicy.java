package com.edteam.reservations.domain.access;

import com.edteam.reservations.domain.model.Email;
import com.edteam.reservations.domain.model.Reservation;

import java.util.Objects;
import java.util.Optional;

/**
 * Quién puede ver y tocar qué reserva.
 *
 * <p>Es una regla de negocio, no una configuración de framework: «una reserva
 * pertenece a un único usuario» es la misma frase que está en la primera línea
 * de la descripción de la API. Por eso vive en el dominio y no en un
 * {@code SecurityFilterChain} ni en un {@code @PreAuthorize}: una anotación en
 * el controller sólo protege ese controller, y el día que entre un consumidor
 * de mensajería o un cliente gRPC la regla se tiene que volver a escribir.
 *
 * <p>La clase no sabe qué es un 403 ni un 404. Devuelve decisiones; el caso de
 * uso las traduce a excepciones y el adaptador de entrada, a códigos de estado.
 *
 * <h2>Por qué el titular no puede distinguir un ajeno de un inexistente</h2>
 * {@link #canRead(Actor, Reservation)} devuelve {@code false} en los dos casos
 * y el caso de uso responde lo mismo —la reserva no existe para vos—. Si la
 * respuesta fuera 403 para una reserva ajena y 404 para una inexistente, el
 * par de códigos sería un oráculo: recorriendo los ids se sabría exactamente
 * cuántas reservas hay en el sistema y cuáles están ocupadas, que es la mitad
 * de la enumeración que había que cerrar.
 */
public final class ReservationAccessPolicy {

    private ReservationAccessPolicy() {
    }

    /**
     * Si el actor puede ver la reserva: es suya, o tiene el rol que alcanza
     * reservas ajenas.
     */
    public static boolean canRead(Actor actor, Reservation reservation) {
        Objects.requireNonNull(actor, "El solicitante es obligatorio");
        Objects.requireNonNull(reservation, "La reserva es obligatoria");
        return actor.actsOnBehalfOfOthers() || isOwner(actor, reservation);
    }

    /**
     * Si el actor puede modificar o cancelar la reserva.
     *
     * <p>Hoy coincide con la lectura, y está separado a propósito: escribir
     * sobre una reserva ajena y mirarla son dos permisos distintos, y el día
     * que un rol de sólo lectura (analítica, soporte de primer nivel) entre al
     * sistema, el cambio es acá y no repartido por cinco casos de uso.
     */
    public static boolean canWrite(Actor actor, Reservation reservation) {
        return canRead(actor, reservation);
    }

    /** Si la reserva es del actor, sin privilegios de por medio. */
    public static boolean isOwner(Actor actor, Reservation reservation) {
        Objects.requireNonNull(actor, "El solicitante es obligatorio");
        Objects.requireNonNull(reservation, "La reserva es obligatoria");
        return reservation.user().email().equals(actor.email());
    }

    /**
     * Resuelve por qué usuario se filtra un listado.
     *
     * <p>El filtro deja de ser un parámetro de consulta con el que el cliente
     * hace lo que quiere y pasa a ser una decisión del dominio:
     *
     * <ul>
     *   <li>Titular sin filtro → se le impone el suyo. Nunca puede pedir «todas».</li>
     *   <li>Titular con su propio email → se respeta; es redundante pero válido.</li>
     *   <li>Titular con el email de otro → {@link ReservationAccessDeniedException}.
     *       Acá sí corresponde rechazar explícitamente y no responder una lista
     *       vacía: el cliente sabe cuál es su email, así que el rechazo no le
     *       revela nada que no supiera, y una lista vacía silenciosa esconde un
     *       bug del cliente.</li>
     *   <li>Backoffice → se respeta lo que pida, incluido «sin filtro».</li>
     * </ul>
     *
     * @param requestedOwner el filtro que pidió el cliente, si pidió alguno
     * @return el filtro efectivo; vacío sólo si el actor puede listar todo
     */
    public static Optional<Email> ownerFilterFor(Actor actor, Optional<Email> requestedOwner) {
        Objects.requireNonNull(actor, "El solicitante es obligatorio");
        Objects.requireNonNull(requestedOwner, "El filtro es obligatorio (usar Optional.empty() si no se filtra)");

        if (actor.actsOnBehalfOfOthers()) {
            return requestedOwner;
        }
        if (requestedOwner.isPresent() && !requestedOwner.get().equals(actor.email())) {
            throw new ReservationAccessDeniedException(
                    "El solicitante %s no puede listar las reservas de otro usuario".formatted(actor.email()));
        }
        return Optional.of(actor.email());
    }
}
