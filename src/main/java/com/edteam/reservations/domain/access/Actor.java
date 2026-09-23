package com.edteam.reservations.domain.access;

import com.edteam.reservations.domain.exception.InvalidUserException;
import com.edteam.reservations.domain.model.Email;

import java.util.Objects;
import java.util.Set;

/**
 * Quién hace el pedido, en términos del negocio.
 *
 * <p>Es el dato que faltaba: hasta ahora los casos de uso recibían <em>qué</em>
 * había que hacer pero no <em>quién</em> lo pedía, y sin eso la autorización
 * por recurso no se puede expresar en ningún lado salvo en el controller —donde
 * queda atada al próximo adaptador de entrada que se acuerde de chequearla—.
 *
 * <p>El {@code Actor} es de dominio a propósito: no conoce JWT, ni
 * {@code Authentication}, ni ninguna clase de Spring Security. El adaptador de
 * entrada lo construye a partir de las credenciales que haya —hoy un token
 * Bearer, mañana un certificado de cliente o una firma de partner— y el
 * dominio decide con él. Es lo que permite que
 * {@link ReservationAccessPolicy} se pruebe sin levantar un contexto.
 *
 * <p>El email es la identidad: es el mismo identificador con el que la API
 * viene identificando al usuario desde el primer día. Nombre y apellido vienen
 * del emisor del token porque el alta del usuario ocurre como efecto de
 * reservar y ya no puede tomarlos del cuerpo del pedido: que el cliente
 * declarara a nombre de quién reservaba era justamente la suplantación que hay
 * que cerrar.
 *
 * @param email     identidad del solicitante, tal como la afirma el emisor del token
 * @param firstName nombre, para el alta del usuario en su primera reserva
 * @param lastName  apellido, para lo mismo
 * @param roles     roles de negocio; nunca vacío
 */
public record Actor(Email email, String firstName, String lastName, Set<ActorRole> roles) {

    public Actor {
        Objects.requireNonNull(email, "La identidad del solicitante es obligatoria");
        Objects.requireNonNull(roles, "Los roles son obligatorios");
        if (roles.isEmpty()) {
            throw new InvalidUserException("El solicitante %s no tiene ningún rol".formatted(email));
        }
        firstName = requireText(firstName, "nombre");
        lastName = requireText(lastName, "apellido");
        roles = Set.copyOf(roles);
    }

    /** Titular sin privilegios: el caso normal. */
    public static Actor customer(Email email, String firstName, String lastName) {
        return new Actor(email, firstName, lastName, Set.of(ActorRole.CUSTOMER));
    }

    /** Operación interna: alcanza reservas ajenas, con auditoría. */
    public static Actor backoffice(Email email, String firstName, String lastName) {
        return new Actor(email, firstName, lastName, Set.of(ActorRole.BACKOFFICE));
    }

    public boolean hasRole(ActorRole role) {
        return roles.contains(role);
    }

    /** Si puede operar sobre reservas que no son suyas. */
    public boolean actsOnBehalfOfOthers() {
        return hasRole(ActorRole.BACKOFFICE);
    }

    /**
     * Representación segura para logs y auditoría: el dominio no decide cómo se
     * enmascara la PII, pero sí que la identidad de un actor se escribe por su
     * email y no por otra cosa. El enmascarado es del adaptador que loguea.
     */
    @Override
    public String toString() {
        return "Actor[%s, roles=%s]".formatted(email, roles);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidUserException(
                    "El %s del solicitante es obligatorio: el token no lo trae".formatted(field));
        }
        return value.trim();
    }
}
