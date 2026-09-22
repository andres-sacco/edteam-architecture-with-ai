package com.edteam.reservations.application.exception;

import com.edteam.reservations.domain.model.UserId;

/**
 * El usuario dueño de la reserva no existe.
 *
 * <p>No se valida con un {@code SELECT} previo: se deja que la clave foránea
 * del modelo de datos haga el trabajo y se traduce el error. Además de ser una
 * consulta menos, evita la carrera del "consulto, existe, y entre medio lo
 * borran".
 */
public class UnknownUserException extends ApplicationException {

    private final UserId userId;

    public UnknownUserException(UserId userId, Throwable cause) {
        super("No existe el usuario %s".formatted(userId), cause);
        this.userId = userId;
    }

    public UserId userId() {
        return userId;
    }
}
