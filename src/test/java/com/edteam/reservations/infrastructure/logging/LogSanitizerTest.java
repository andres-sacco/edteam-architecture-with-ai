package com.edteam.reservations.infrastructure.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Log forging desde un proveedor externo.
 *
 * <p>El vector es viejo y sigue vivo: en un log de texto el separador de
 * registros es el salto de línea, así que un cuerpo de error que lo contiene
 * no agrega una línea a nuestro registro, agrega registros enteros.
 */
@DisplayName("Saneado de datos externos antes de loguearlos")
class LogSanitizerTest {

    @Test
    @DisplayName("neutraliza los saltos de línea: son el separador de registros del log")
    void neutralisesNewlines() {
        String forged = "no encontrado\n2026-09-23 INFO [reservations] Reserva 42 cancelada por admin";

        assertThat(LogSanitizer.sanitize(forged))
                .doesNotContain("\n")
                .doesNotContain("\r")
                .contains("no encontrado")
                .contains("Reserva 42 cancelada por admin");
    }

    @Test
    @DisplayName("neutraliza el retorno de carro y las secuencias de escape de la terminal")
    void neutralisesControlCharacters() {
        assertThat(LogSanitizer.sanitize("a\rb\tc\u001b[31md"))
                .isEqualTo("a b c [31md");
    }

    @Test
    @DisplayName("trunca: un cuerpo de error de un proveedor no puede llenar el log")
    void truncates() {
        String huge = "x".repeat(LogSanitizer.MAX_LENGTH * 3);

        assertThat(LogSanitizer.sanitize(huge))
                .hasSize(LogSanitizer.MAX_LENGTH + 1)
                .endsWith("…");
    }

    @Test
    @DisplayName("respeta un largo máximo más chico, como el de una columna")
    void honoursACustomLimit() {
        assertThat(LogSanitizer.sanitize("abcdefghij", 4)).isEqualTo("abcd…");
    }

    @Test
    @DisplayName("un valor vacío o nulo queda explícito, no como un hueco en la línea")
    void marksEmptyValues() {
        assertThat(LogSanitizer.sanitize(null)).isEqualTo("<vacío>");
        assertThat(LogSanitizer.sanitize("   ")).isEqualTo("<vacío>");
    }

    @Test
    @DisplayName("un valor normal pasa sin cambios")
    void leavesNormalValuesAlone() {
        assertThat(LogSanitizer.sanitize("{\"error\":\"not found\"}")).isEqualTo("{\"error\":\"not found\"}");
    }
}
