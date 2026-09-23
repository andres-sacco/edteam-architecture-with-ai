package com.edteam.reservations.domain.access;

import com.edteam.reservations.domain.exception.DomainException;

/**
 * El solicitante pidió algo que no le corresponde, y decírselo no le revela
 * nada que no supiera.
 *
 * <p>Es el caso raro: la regla general es que un recurso ajeno se comporte como
 * inexistente (ver {@link ReservationAccessPolicy}). Esta excepción se usa
 * solamente cuando el rechazo no funciona como oráculo —pedir el listado de
 * otro usuario, por ejemplo—, y el adaptador la traduce a un 403.
 */
public class ReservationAccessDeniedException extends DomainException {

    public ReservationAccessDeniedException(String message) {
        super(message);
    }
}
