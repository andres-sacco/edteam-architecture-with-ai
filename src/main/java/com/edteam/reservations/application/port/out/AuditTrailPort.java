package com.edteam.reservations.application.port.out;

import com.edteam.reservations.application.audit.AuditEntry;

/**
 * Registro de auditoría de las operaciones sensibles.
 *
 * <p>Es un puerto y no un logger por una razón concreta: el registro tiene que
 * escribirse <b>en la misma transacción</b> que el cambio que describe. Un log
 * a un archivo o a un SaaS de observabilidad no tiene esa garantía —se puede
 * perder el registro y quedar el cambio, o al revés—, y ante una disputa con
 * un pasajero o con una aerolínea eso es exactamente lo que no sirve. Con un
 * puerto, quién escribe y dónde es una decisión de infraestructura; que se
 * escriba junto con el cambio, una del diseño.
 *
 * <p>La implementación es append-only: nadie actualiza ni borra una línea de
 * auditoría, y eso se sostiene en la base y no en la buena intención del
 * adaptador.
 */
public interface AuditTrailPort {

    /**
     * Registra la entrada. Participa de la transacción en curso: si el caso de
     * uso hace rollback, el registro se va con él, y es lo correcto —no hubo
     * cambio que auditar—.
     */
    void record(AuditEntry entry);
}
