package com.edteam.reservations.infrastructure.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Prefijos de clave y el resumen que usan los caches con clave compuesta.
 *
 * <p>Los prefijos están acá y no dispersos en cada decorador porque son el
 * espacio de nombres de una base compartida: verlos juntos es lo que permite
 * darse cuenta de que dos usos distintos están por pisarse.
 */
public final class CacheKeys {

    /** Existencia de una ciudad en el catálogo externo: {@code catalog:city:EZE}. */
    public static final String CITY_PREFIX = "catalog:city:";

    /** Total del listado para una combinación de filtros: {@code rsv:count:<resumen>}. */
    public static final String RESERVATION_COUNT_PREFIX = "rsv:count:";

    /** Versión de una reserva, para responder {@code 304}: {@code rsv:ver:1042}. */
    public static final String RESERVATION_VERSION_PREFIX = "rsv:ver:";

    /**
     * Bytes del resumen que entran en la clave. 16 bytes (128 bits) dejan la
     * probabilidad de colisión fuera de lo relevante para este volumen y
     * mantienen la clave en 32 caracteres, que es lo que pesa en la memoria de
     * un free tier.
     */
    private static final int DIGEST_BYTES = 16;

    private CacheKeys() {
    }

    /**
     * Resumen estable de un descriptor.
     *
     * <p>Se usa SHA-256 y no {@code String.hashCode()}: 32 bits colisionan en
     * serio con miles de combinaciones de filtros vivas, y una colisión acá no
     * es una entrada perdida sino un total equivocado servido a un filtro que
     * nunca se consultó.
     *
     * <p>Que sea estable entre JVMs importa: las claves las comparten todas las
     * instancias contra el mismo Redis.
     */
    public static String digest(String descriptor) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(descriptor.getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[DIGEST_BYTES];
            System.arraycopy(hash, 0, truncated, 0, DIGEST_BYTES);
            return HexFormat.of().formatHex(truncated);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 es obligatorio en toda implementación de la plataforma.
            throw new IllegalStateException("SHA-256 no está disponible en esta JVM", e);
        }
    }
}
