package com.edteam.reservations.infrastructure.security.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Clave de cifrado de los datos personales que se guardan en la base.
 *
 * <p>La clave viene del entorno o del gestor de secretos, nunca del
 * repositorio. El valor que trae {@code application.yml} es un placeholder
 * reconocible ({@link #DEV_KEY_MARKER}) y sólo sirve para levantar la
 * aplicación en local: con él, cualquiera que lea el código puede descifrar la
 * columna, así que {@link PiiCipher} avisa en cada arranque y la aplicación se
 * niega a usarlo cuando los tokens de desarrollo están apagados.
 *
 * @param key clave AES de 256 bits, en Base64
 */
@ConfigurationProperties(prefix = "reservations.security.pii")
public record PiiCipherProperties(String key) {

    /** Marca del placeholder de desarrollo; su presencia se detecta al arrancar. */
    public static final String DEV_KEY_MARKER = "ZGV2LW9ubHk";

    public boolean hasKey() {
        return key != null && !key.isBlank();
    }

    public boolean usesPublishedDevKey() {
        return hasKey() && key.startsWith(DEV_KEY_MARKER);
    }
}
