package com.edteam.reservations.infrastructure.logging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Seudónimo estable del solicitante, para el log de acceso.
 *
 * <h2>Por qué no es el {@code actorId} que el diseño pedía</h2>
 * El §1.3 del diseño escribe {@code actorId} y lo describe como «el id interno,
 * nunca el email», puesto por {@code JwtActorConverter}. El id interno no
 * existe en ese punto: {@code JwtActorConverter} convierte un token en un
 * {@link com.edteam.reservations.domain.access.Actor}, cuya identidad es el
 * email; el id de la fila de {@code usuario} se conoce recién adentro del caso
 * de uso, después de un {@code SELECT}, y en un 401 no se conoce nunca. Poner
 * un {@code SELECT} por pedido en la cadena de filtros sólo para etiquetar una
 * línea de log es pagar una consulta a la base por cada 401 de una campaña de
 * credenciales, que es exactamente el momento en que menos se la quiere pagar.
 *
 * <p>Lo que se escribe entonces es {@code actorRef}: los primeros 12 hex de
 * {@code sha256(email)}. Conserva lo único que el log necesita —poder decir
 * «estos 400 pedidos son del mismo solicitante»— y no lleva el dato. Los
 * eventos de dominio siguen llevando {@code userId}, que sí es el id interno y
 * sí está disponible ahí.
 *
 * <p>No es anonimización: con la lista de emails, el hash se invierte probando.
 * Es seudonimización, y alcanza para lo que hace: el log deja de ser una lista
 * de emails indexada y buscable en un sistema con otra retención y otro
 * perímetro. Cuando hace falta la identidad real, está en la tabla de
 * auditoría, que vive en nuestra base y escribe el email en claro a propósito.
 */
public final class ActorRef {

    /**
     * 12 hex = 48 bits. Con los ~10⁵ usuarios que el escenario de volumen del
     * diseño contempla, la probabilidad de una colisión es despreciable, y el
     * costo de una colisión es dos solicitantes que se ven como uno en un
     * panel, no una decisión equivocada.
     */
    private static final int LENGTH = 12;

    private static final String UNKNOWN = "anon";

    private ActorRef() {
    }

    public static String of(String identity) {
        if (identity == null || identity.isBlank()) {
            return UNKNOWN;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(identity.trim().toLowerCase(java.util.Locale.ROOT)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 es obligatorio en toda JVM. Si no está, lo correcto es no
            // escribir nada antes que escribir el valor crudo.
            return UNKNOWN;
        }
    }
}
