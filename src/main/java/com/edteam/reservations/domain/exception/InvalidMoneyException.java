package com.edteam.reservations.domain.exception;

/** El importe o la moneda no son válidos. */
public class InvalidMoneyException extends DomainException {

    public InvalidMoneyException(String message) {
        super(message);
    }
}
