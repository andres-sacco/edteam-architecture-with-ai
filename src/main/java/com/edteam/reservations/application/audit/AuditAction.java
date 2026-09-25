package com.edteam.reservations.application.audit;

/**
 * Operación sensible que queda registrada en la auditoría.
 *
 * <p>Son las que cambian el estado de una reserva más el intento fallido de
 * alcanzar una ajena. Las lecturas exitosas no se auditan a propósito: una
 * fila por {@code GET} convertiría la tabla de auditoría en el doble del
 * tráfico de la API y en el mismo problema de retención de PII que se está
 * tratando de acotar. Lo que hace falta ante una disputa es quién <em>cambió</em>
 * qué, y quién intentó entrar donde no debía.
 */
public enum AuditAction {
    RESERVATION_CREATED,
    RESERVATION_MODIFIED,
    RESERVATION_CANCELLED,
    RESERVATION_CONFIRMED,

    /** Alguien pidió una reserva que no es suya. Se registra aunque la respuesta sea 404. */
    RESERVATION_ACCESS_DENIED
}
