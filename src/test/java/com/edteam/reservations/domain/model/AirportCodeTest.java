package com.edteam.reservations.domain.model;

import com.edteam.reservations.domain.exception.InvalidAirportCodeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AirportCode")
class AirportCodeTest {

    @Test
    @DisplayName("acepta un código IATA de tres letras")
    void acceptsValidIataCode() {
        assertThat(AirportCode.of("EZE").value()).isEqualTo("EZE");
    }

    @Test
    @DisplayName("normaliza a mayúsculas y recorta espacios")
    void normalizesInput() {
        assertThat(AirportCode.of("  eze ").value()).isEqualTo("EZE");
    }

    @Test
    @DisplayName("dos códigos con el mismo valor son iguales")
    void hasValueEquality() {
        assertThat(AirportCode.of("eze")).isEqualTo(AirportCode.of("EZE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EZ", "EZEE", "EZ1", "123", "E-E"})
    @DisplayName("rechaza códigos con formato inválido")
    void rejectsInvalidFormat(String code) {
        assertThatThrownBy(() -> AirportCode.of(code))
                .isInstanceOf(InvalidAirportCodeException.class)
                .hasMessageContaining("no es un código IATA válido");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("rechaza códigos nulos o vacíos")
    void rejectsBlank(String code) {
        assertThatThrownBy(() -> AirportCode.of(code))
                .isInstanceOf(InvalidAirportCodeException.class)
                .hasMessageContaining("obligatorio");
    }
}
