package com.edteam.reservations.infrastructure.security;

import com.edteam.reservations.infrastructure.logging.ActorRef;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Quién está ejecutando una acción manual sobre datos de producción.
 *
 * <h2>Por qué existe</h2>
 * El reencolado de la dead letter y la purga del outbox son escrituras de una
 * persona sobre el estado de producción: {@code replay} borra el estado de
 * fallo de mensajes muertos y {@code purge} borra filas. La auditoría
 * encontró que el primero logueaba sólo la cantidad y el segundo <b>no
 * logueaba nada</b>, así que después de un incidente no se podía reconstruir
 * quién reencoló qué, ni que alguien había borrado filas del outbox.
 *
 * <p>El §3.1 del diseño ya exigía {@code outbox.requeued} «porque es una
 * acción de un humano sobre datos de producción: tiene que quedar escrita», y
 * no pedía el humano. Esta clase es ese campo que faltaba.
 *
 * <h2>Qué valor escribe</h2>
 * El mismo seudónimo del log de acceso ({@link ActorRef}) y no el email: una
 * acción de operación no es motivo para replicar un dato personal al SaaS de
 * logs, y el {@code actorRef} basta para cruzar esta línea con las del
 * operador en el resto del sistema. Cuando hace falta la identidad real, está
 * en la fila de {@code auditoria}.
 *
 * <p>{@code system} cuando no hay autenticación: la misma operación la
 * disparan también el scheduler de purga y los tests, y decir «system» es más
 * honesto que dejar el campo vacío o inventar un usuario.
 */
public final class OpsActor {

    /** Valor cuando la acción no viene de una persona autenticada. */
    public static final String SYSTEM = "system";

    private OpsActor() {}

    public static String current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            return SYSTEM;
        }
        return ActorRef.of(authentication.getName());
    }
}
