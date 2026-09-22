package com.edteam.reservations.domain.model;

import com.edteam.reservations.domain.exception.InvalidMoneyException;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Importe con su moneda.
 *
 * <p>Se usa {@link BigDecimal} y no {@code double}: los importes no admiten el
 * error de representación del punto flotante binario.
 *
 * <p>Los límites replican la columna {@code NUMERIC(10,2)} del modelo de datos,
 * para que un importe imposible de persistir se rechace acá y no explote recién
 * al hacer el {@code INSERT}.
 *
 * <p>La escala se normaliza a dos decimales en el constructor, así la igualdad
 * por valor del record funciona como uno espera: {@code 100.0 USD} y
 * {@code 100.00 USD} son el mismo importe, aunque {@code BigDecimal.equals} los
 * considere distintos.
 */
public record Money(BigDecimal amount, String currency) {

    /** ISO 4217: tres letras. */
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private static final int MAX_SCALE = 2;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

    public Money {
        if (amount == null) {
            throw new InvalidMoneyException("El importe es obligatorio");
        }
        if (currency == null || currency.isBlank()) {
            throw new InvalidMoneyException("La moneda es obligatoria");
        }
        currency = currency.trim().toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(currency).matches()) {
            throw new InvalidMoneyException(
                    "La moneda '%s' no es un código ISO 4217 válido (3 letras)".formatted(currency));
        }
        if (amount.signum() < 0) {
            throw new InvalidMoneyException("El importe no puede ser negativo: %s".formatted(amount));
        }
        if (amount.scale() > MAX_SCALE) {
            throw new InvalidMoneyException(
                    "El importe %s tiene más de %d decimales".formatted(amount, MAX_SCALE));
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidMoneyException("El importe %s supera el máximo admitido (%s)"
                    .formatted(amount, MAX_AMOUNT));
        }
        amount = amount.setScale(MAX_SCALE, java.math.RoundingMode.UNNECESSARY);
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    @Override
    public String toString() {
        return "%s %s".formatted(amount.toPlainString(), currency);
    }
}
