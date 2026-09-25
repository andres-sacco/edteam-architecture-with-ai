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
     * Sin el email.
     *
     * <p>Decía «representación segura para logs» y escribía el email entero.
     * La premisa —«el enmascarado es del adaptador que loguea»— es correcta
     * para los campos que el adaptador ELIGE escribir, y no vale para un
     * {@code toString()}: un {@code toString()} termina en un log sin que
     * nadie lo decida. El gate de datos sensibles lo encontró donde era
     * previsible que apareciera: Spring Security escribe el principal entero
     * en un {@code DEBUG} de su propio filtro, y ahí nuestro adaptador no
     * interviene.
     *
     * <p>Lo que queda identifica al actor sin identificar a la persona: el
     * mismo seudónimo estable que lleva el log de acceso. Quien necesite el
     * email tiene {@link #email()}, que es una decisión explícita de quien
     * escribe la línea.
     *
     * <p>El seudónimo se calcula acá, en el dominio, y no con
     * {@code infrastructure.logging.ActorRef}: el dominio no puede depender de
     * infraestructura, y ArchUnit lo verifica. Los dos usan los mismos 12 hex
     * de {@code sha256}, y {@code ActorTest} sostiene que coincidan.
     */
    @Override
    public String toString() {
        return "Actor[ref=%s, roles=%s]".formatted(reference(), roles);
    }

    /**
     * Seudónimo estable del actor: los primeros 12 hex de
     * {@code sha256(email)}.
     *
     * <p>No es anonimización —con la lista de emails el hash se invierte
     * probando— sino seudonimización, y alcanza para lo que hace: que el log
     * deje de ser una lista de emails indexada en un sistema con otra
     * retención, conservando la capacidad de decir «estas N líneas son del
     * mismo solicitante».
     */
    public String reference() {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(email.value()
                    .trim()
                    .toLowerCase(java.util.Locale.ROOT)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash).substring(0, REFERENCE_LENGTH);
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 es obligatorio en toda JVM. Si no estuviera, lo correcto
            // es no escribir nada antes que escribir el valor crudo.
            return "anon";
        }
    }

    /** 48 bits: la colisión es despreciable y su costo es un panel, no una decisión. */
    private static final int REFERENCE_LENGTH = 12;

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidUserException(
                    "El %s del solicitante es obligatorio: el token no lo trae".formatted(field));
        }
        return value.trim();
    }
}
