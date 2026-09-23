package com.edteam.reservations.infrastructure.logging;

/**
 * Enmascarado de datos personales para los logs.
 *
 * <p>Los logs de este servicio salen del perímetro: van a un SaaS de
 * observabilidad, se copian a un bucket, se indexan y quedan buscables por
 * mucha más gente de la que puede consultar la base. Cada email en claro en un
 * {@code INFO} es un dato regulado que se replicó a un sistema que no está en
 * el alcance del tratamiento.
 *
 * <p>Lo que queda alcanza para operar —se puede correlacionar, se puede
 * reconocer un email repetido— y no alcanza para identificar a nadie. Cuando
 * hace falta identificar de verdad, el identificador es el id interno del
 * usuario, que no es un dato personal fuera de nuestra base.
 */
public final class PiiMasker {

    private static final String REDACTED = "***";

    private PiiMasker() {
    }

    /**
     * {@code ana.perez@example.com} → {@code an***@example.com}.
     *
     * <p>Se conserva el dominio: es lo que sirve para operar (distinguir un
     * partner de un usuario final, ver que un lote viene todo del mismo
     * dominio corporativo) y no identifica a la persona.
     */
    public static String mask(String email) {
        if (email == null || email.isBlank()) {
            return REDACTED;
        }
        int at = email.indexOf('@');
        if (at < 0) {
            // No parece un email; se lo trata como dato sensible igual.
            return REDACTED;
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        int visible = Math.min(2, local.length());
        return local.substring(0, visible) + REDACTED + domain;
    }

    /**
     * Documentos, nombres y cualquier otro dato de pasajero no se enmascaran
     * parcialmente: se omiten. No hay ningún problema de operación que se
     * resuelva viendo los primeros dígitos de un documento en un log, así que
     * el valor nunca se escribe.
     */
    public static String redact() {
        return REDACTED;
    }
}
