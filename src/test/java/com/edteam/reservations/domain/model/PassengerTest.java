package com.edteam.reservations.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.edteam.reservations.domain.exception.InvalidPassengerException;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Passenger")
class PassengerTest {

    private static final LocalDate BIRTH_DATE = LocalDate.of(1990, 5, 20);

    @Test
    @DisplayName("un pasajero nuevo no tiene id")
    void newPassengerHasNoId() {
        assertThat(Passenger.newPassenger("Ana", "Pérez", BIRTH_DATE, "30123456")
                        .id())
                .isEmpty();
    }

    @Test
    @DisplayName("normaliza espacios y expone el nombre completo")
    void normalizesInput() {
        Passenger passenger = Passenger.newPassenger("  Ana ", " Pérez", BIRTH_DATE, " 30123456 ");

        assertThat(passenger.firstName()).isEqualTo("Ana");
        assertThat(passenger.lastName()).isEqualTo("Pérez");
        assertThat(passenger.documentNumber()).contains("30123456");
        assertThat(passenger.fullName()).isEqualTo("Ana Pérez");
    }

    @Test
    @DisplayName("admite pasajeros sin documento, como la columna del modelo de datos")
    void allowsPassengerWithoutDocument() {
        Passenger passenger = Passenger.newPassenger("Ana", "Pérez", BIRTH_DATE, null);

        assertThat(passenger.documentNumber()).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("exige nombre y apellido")
    void rejectsMissingNames(String value) {
        assertThatThrownBy(() -> Passenger.newPassenger(value, "Pérez", BIRTH_DATE, "1"))
                .isInstanceOf(InvalidPassengerException.class)
                .hasMessageContaining("nombre");
        assertThatThrownBy(() -> Passenger.newPassenger("Ana", value, BIRTH_DATE, "1"))
                .isInstanceOf(InvalidPassengerException.class)
                .hasMessageContaining("apellido");
    }

    @Test
    @DisplayName("exige fecha de nacimiento y rechaza fechas absurdas")
    void validatesBirthDate() {
        assertThatThrownBy(() -> Passenger.newPassenger("Ana", "Pérez", null, "1"))
                .isInstanceOf(InvalidPassengerException.class)
                .hasMessageContaining("fecha de nacimiento");
        assertThatThrownBy(() -> Passenger.newPassenger("Ana", "Pérez", LocalDate.of(1899, 12, 31), "1"))
                .isInstanceOf(InvalidPassengerException.class)
                .hasMessageContaining("anterior al mínimo");
    }

    @Test
    @DisplayName("rechaza un documento en blanco o más largo que la columna")
    void validatesDocument() {
        assertThatThrownBy(() -> Passenger.newPassenger("Ana", "Pérez", BIRTH_DATE, "   "))
                .isInstanceOf(InvalidPassengerException.class)
                .hasMessageContaining("no puede estar vacío");
        assertThatThrownBy(() -> Passenger.newPassenger("Ana", "Pérez", BIRTH_DATE, "1".repeat(51)))
                .isInstanceOf(InvalidPassengerException.class)
                .hasMessageContaining("50 caracteres");
    }

    @Test
    @DisplayName("exige id y documento explícitos en el constructor canónico")
    void rejectsNullOptionals() {
        assertThatNullPointerException()
                .isThrownBy(() -> new Passenger(null, "Ana", "Pérez", BIRTH_DATE, Optional.of("1")));
        assertThatNullPointerException()
                .isThrownBy(() -> new Passenger(Optional.empty(), "Ana", "Pérez", BIRTH_DATE, null));
    }

    @Test
    @DisplayName("isBornAfter compara contra la fecha de referencia")
    void detectsFutureBirthDate() {
        Passenger passenger = Passenger.newPassenger("Ana", "Pérez", LocalDate.of(2026, 10, 2), null);

        assertThat(passenger.isBornAfter(LocalDate.of(2026, 10, 1))).isTrue();
        assertThat(passenger.isBornAfter(LocalDate.of(2026, 10, 2))).isFalse();
    }

    @Test
    @DisplayName("con documento, la identidad es el documento")
    void identityIsTheDocumentWhenPresent() {
        Passenger ana = Passenger.newPassenger("Ana", "Pérez", BIRTH_DATE, "30123456");
        Passenger mismaPersonaOtroNombre = Passenger.newPassenger("Anita", "Pérez", BIRTH_DATE, "30123456");
        Passenger otraPersona = Passenger.newPassenger("Ana", "Pérez", BIRTH_DATE, "99999999");

        assertThat(ana.identityKey()).isEqualTo(mismaPersonaOtroNombre.identityKey());
        assertThat(ana.identityKey()).isNotEqualTo(otraPersona.identityKey());
    }

    @Test
    @DisplayName("sin documento, la identidad cae a nombre, apellido y fecha de nacimiento")
    void identityFallsBackToNameAndBirthDate() {
        Passenger ana = Passenger.newPassenger("Ana", "Pérez", BIRTH_DATE, null);
        Passenger mismaAna = Passenger.newPassenger("Ana", "Pérez", BIRTH_DATE, null);
        Passenger otroNacimiento = Passenger.newPassenger("Ana", "Pérez", LocalDate.of(1991, 5, 20), null);

        assertThat(ana.identityKey()).isEqualTo(mismaAna.identityKey());
        assertThat(ana.identityKey()).isNotEqualTo(otroNacimiento.identityKey());
    }
}
