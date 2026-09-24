package com.edteam.reservations.infrastructure.security.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * Cifrado a nivel de columna para los datos personales que se guardan.
 *
 * <h2>Qué problema resuelve y cuál no</h2>
 * Un dump de la tabla {@code pasajero} es un dump de PII en claro, y el backup
 * hereda el problema: el archivo viaja, se copia a un bucket, alguien lo
 * restaura en un entorno de pruebas. El cifrado en reposo del motor no cubre
 * eso —protege el disco, no el {@code pg_dump}—. Cifrar la columna sí: el dump
 * sale ilegible sin la clave, que no está en la base.
 *
 * <p>Lo que <b>no</b> resuelve: la aplicación tiene la clave, así que cualquier
 * cosa que corra con sus privilegios lee el dato en claro. Eso se acota con
 * los controles de acceso de arriba, no con esto.
 *
 * <h2>AES-GCM y no AES-CBC</h2>
 * GCM es cifrado autenticado: si alguien con acceso a la base edita un byte
 * del ciphertext, el descifrado falla en lugar de devolver basura que parece
 * un documento. Con CBC el mismo cambio produce un valor distinto y creíble.
 *
 * <h2>Por qué el resultado no es determinista, y qué se perdió con eso</h2>
 * Cada cifrado usa un IV aleatorio, así que el mismo documento produce
 * ciphertexts distintos. Esto <b>rompe a propósito</b> la búsqueda por
 * documento: ya no se puede hacer {@code WHERE documento = ?}. Es justamente lo
 * que había que romper —el {@code UNIQUE} sobre el documento era el que
 * convertía el alta de una reserva en un oráculo de datos de pasajeros
 * ajenos—. Si algún día hace falta buscar por documento, la forma es un índice
 * ciego (HMAC de la clave con una clave distinta) en una columna aparte, no
 * volver al cifrado determinista.
 *
 * <h2>Compatibilidad con lo que ya está escrito</h2>
 * El formato lleva prefijo de versión. Un valor sin prefijo es un documento
 * escrito antes de este cambio y se devuelve tal cual, en lugar de fallar: la
 * migración deja los existentes en claro y cada escritura los va convirtiendo.
 * El prefijo es además lo que permite rotar el algoritmo sin reescribir la
 * tabla entera de una vez.
 */
public class PiiCipher {

    /** Versión del formato: {@code v1:}<base64(IV || ciphertext || tag)>. */
    static final String PREFIX = "v1:";

    private static final int IV_BYTES = 12;

    private static final int TAG_BITS = 128;

    private static final int KEY_BYTES = 32;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final Logger log = LoggerFactory.getLogger(PiiCipher.class);

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public PiiCipher(PiiCipherProperties properties) {
        Objects.requireNonNull(properties, "La configuración de cifrado es obligatoria");
        if (!properties.hasKey()) {
            throw new IllegalStateException("""
                    Falta 'reservations.security.pii.key': sin clave no se puede guardar el documento \
                    de un pasajero, y guardarlo en claro no es una opción.""");
        }
        byte[] material = decodeKey(properties.key());
        if (material.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "La clave de cifrado tiene que ser AES-256 (%d bytes en Base64) y tiene %d"
                            .formatted(KEY_BYTES, material.length));
        }
        if (properties.usesPublishedDevKey()) {
            // Un evento de log, un registro físico. El banner ASCII de seis
            // líneas era, para un recolector orientado a líneas sin regla de
            // multilínea, un registro con timestamp y cinco sin nivel, sin
            // logger y sin mensaje — y justo en el registro que dice que este
            // entorno corre con secretos de desarrollo (hallazgo 30).
            //
            // El aviso queda en el `message` fijo y lo demás en campos. El
            // gauge `reservations.security.pii.dev_key` es lo que además lo
            // hace alertable: un WARN de arranque aparece una vez en la vida
            // del proceso, y nadie lo está mirando cuando aparece.
            log.atWarn()
                    .addKeyValue("event", "startup.wiring")
                    .addKeyValue("component", "pii-cipher")
                    .addKeyValue("pii.key.source", "dev")
                    .addKeyValue("remediation", "PII_ENCRYPTION_KEY")
                    .log("El documento de los pasajeros se cifra con la clave publicada en el repositorio");
        }
        this.key = new SecretKeySpec(material, "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] payload = ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv)
                    .put(ciphertext)
                    .array();
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            // No se degrada a guardar en claro: es preferible fallar el alta.
            throw new IllegalStateException("No se pudo cifrar un dato personal", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            // Escrito antes de que la columna estuviera cifrada.
            return stored;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (payload.length <= IV_BYTES) {
                throw new IllegalStateException("El valor cifrado está truncado");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
            byte[] plaintext = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // El mensaje no incluye el valor: es el dato que se está protegiendo.
            throw new IllegalStateException("No se pudo descifrar un dato personal almacenado", e);
        }
    }

    private static byte[] decodeKey(String encoded) {
        try {
            return Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("'reservations.security.pii.key' no es Base64 válido", e);
        }
    }
}
