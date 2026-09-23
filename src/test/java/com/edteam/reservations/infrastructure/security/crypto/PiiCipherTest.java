package com.edteam.reservations.infrastructure.security.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Cifrado de datos personales en reposo")
class PiiCipherTest {

    private static final String KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private final PiiCipher cipher = new PiiCipher(new PiiCipherProperties(KEY));

    @Test
    @DisplayName("lo que se cifra se recupera")
    void roundTrips() {
        assertThat(cipher.decrypt(cipher.encrypt("30123456"))).isEqualTo("30123456");
    }

    @Test
    @DisplayName("el valor almacenado no contiene el documento y lleva prefijo de versión")
    void theStoredValueIsOpaque() {
        String stored = cipher.encrypt("30123456");

        assertThat(stored).startsWith("v1:").doesNotContain("30123456");
    }

    @Test
    @DisplayName("el mismo documento produce ciphertexts distintos")
    void isNotDeterministic() {
        // No es un detalle: es lo que hace imposible deducir que dos reservas
        // llevan al mismo pasajero mirando la tabla, y de paso lo que rompió
        // —a propósito— la deduplicación global por documento que convertía el
        // alta en un oráculo de datos ajenos.
        assertThat(cipher.encrypt("30123456")).isNotEqualTo(cipher.encrypt("30123456"));
    }

    @Test
    @DisplayName("un ciphertext manipulado no se descifra: falla, no devuelve basura creíble")
    void detectsTampering() {
        String stored = cipher.encrypt("30123456");
        String tampered = stored.substring(0, stored.length() - 4) + "AAA=";

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("30123456");
    }

    @Test
    @DisplayName("un valor sin prefijo se devuelve tal cual: es una fila anterior al cifrado")
    void readsLegacyPlaintext() {
        assertThat(cipher.decrypt("30123456")).isEqualTo("30123456");
    }

    @Test
    @DisplayName("null viaja como null: el documento es opcional")
    void toleratesNulls() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    @DisplayName("sin clave la aplicación no arranca: guardar en claro no es una alternativa")
    void refusesToStartWithoutAKey() {
        assertThatThrownBy(() -> new PiiCipher(new PiiCipherProperties(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reservations.security.pii.key");
    }

    @Test
    @DisplayName("rechaza una clave que no es AES-256")
    void rejectsAWeakKey() {
        String tooShort = Base64.getEncoder().encodeToString("corta".getBytes());

        assertThatThrownBy(() -> new PiiCipher(new PiiCipherProperties(tooShort)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AES-256");
    }

    @Test
    @DisplayName("rechaza una clave que no es Base64")
    void rejectsGarbage() {
        assertThatThrownBy(() -> new PiiCipher(new PiiCipherProperties("no es base64 ###")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    @DisplayName("reconoce la clave de desarrollo publicada en el repositorio")
    void detectsThePublishedDevelopmentKey() {
        // La detección es lo que permite avisar en cada arranque: una clave que
        // está en el repositorio no es un secreto, cualquiera que lea el código
        // puede descifrar la columna.
        assertThat(new PiiCipherProperties("ZGV2LW9ubHkta2V5LTMyLWJ5dGVzLWRlbW8tMDEyMzQ=")
                .usesPublishedDevKey()).isTrue();
        assertThat(new PiiCipherProperties(KEY).usesPublishedDevKey()).isFalse();
    }
}
