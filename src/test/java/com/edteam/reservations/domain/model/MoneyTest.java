package com.edteam.reservations.domain.model;

import com.edteam.reservations.domain.exception.InvalidMoneyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Money")
class MoneyTest {

    @Test
    @DisplayName("acepta un importe con dos decimales")
    void acceptsValidAmount() {
        Money money = Money.of("1250.50", "USD");

        assertThat(money.amount()).isEqualByComparingTo("1250.50");
        assertThat(money.currency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("normaliza la escala para que la igualdad por valor funcione")
    void normalizesScale() {
        assertThat(Money.of("100.0", "USD")).isEqualTo(Money.of("100.00", "USD"));
        assertThat(Money.of("100", "USD")).isEqualTo(Money.of("100.00", "USD"));
    }

    @Test
    @DisplayName("normaliza la moneda a mayúsculas")
    void normalizesCurrency() {
        assertThat(Money.of("10.00", " usd ").currency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("acepta importe cero")
    void acceptsZero() {
        assertThat(Money.of("0", "ARS").amount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("rechaza importes negativos")
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> Money.of("-0.01", "USD"))
                .isInstanceOf(InvalidMoneyException.class)
                .hasMessageContaining("no puede ser negativo");
    }

    @Test
    @DisplayName("rechaza más de dos decimales, que la columna NUMERIC(10,2) no puede guardar")
    void rejectsTooManyDecimals() {
        assertThatThrownBy(() -> Money.of("10.001", "USD"))
                .isInstanceOf(InvalidMoneyException.class)
                .hasMessageContaining("decimales");
    }

    @Test
    @DisplayName("rechaza importes que exceden la precisión de la columna")
    void rejectsAmountOverColumnPrecision() {
        assertThatThrownBy(() -> Money.of("100000000.00", "USD"))
                .isInstanceOf(InvalidMoneyException.class)
                .hasMessageContaining("supera el máximo");
    }

    @ParameterizedTest
    @ValueSource(strings = {"US", "USDD", "US1", "$$$"})
    @DisplayName("rechaza monedas que no son ISO 4217")
    void rejectsInvalidCurrency(String currency) {
        assertThatThrownBy(() -> Money.of("10.00", currency))
                .isInstanceOf(InvalidMoneyException.class)
                .hasMessageContaining("ISO 4217");
    }

    @Test
    @DisplayName("exige importe y moneda")
    void rejectsMissingValues() {
        assertThatThrownBy(() -> new Money(null, "USD"))
                .isInstanceOf(InvalidMoneyException.class)
                .hasMessageContaining("importe es obligatorio");
        assertThatThrownBy(() -> new Money(BigDecimal.TEN, null))
                .isInstanceOf(InvalidMoneyException.class)
                .hasMessageContaining("moneda es obligatoria");
    }
}
