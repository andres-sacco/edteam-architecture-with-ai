package com.edteam.reservations.infrastructure.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Enmascarado de PII en los logs")
class PiiMaskerTest {

    @Test
    @DisplayName("conserva el dominio, que sirve para operar, y esconde a la persona")
    void masksTheLocalPart() {
        assertThat(PiiMasker.mask("ana.perez@example.com")).isEqualTo("an***@example.com");
    }

    @Test
    @DisplayName("un local part corto no se filtra por el enmascarado")
    void handlesShortLocalParts() {
        assertThat(PiiMasker.mask("a@example.com")).isEqualTo("a***@example.com");
    }

    @Test
    @DisplayName("lo que no parece un email se omite entero")
    void redactsAnythingThatIsNotAnEmail() {
        assertThat(PiiMasker.mask("30123456")).isEqualTo("***");
        assertThat(PiiMasker.mask(null)).isEqualTo("***");
        assertThat(PiiMasker.mask("  ")).isEqualTo("***");
    }

    @Test
    @DisplayName("un documento no se enmascara parcialmente: se omite")
    void redactsDocuments() {
        assertThat(PiiMasker.redact()).isEqualTo("***");
    }
}
