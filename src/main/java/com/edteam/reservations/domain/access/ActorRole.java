package com.edteam.reservations.domain.access;

/**
 * Rol de negocio de quien hace el pedido.
 *
 * <p>No es una autoridad de Spring Security ni un scope de OAuth: es la
 * distinción de negocio entre <em>el titular de una reserva</em> y <em>quien
 * opera sobre reservas ajenas</em>. El adaptador de seguridad traduce lo que
 * venga en el token —claim {@code roles}, {@code scope}, grupos del IdP— a
 * estos dos valores, y el dominio decide con ellos sin saber de dónde salieron.
 *
 * <p>Se mantiene deliberadamente chico. Cada rol nuevo es una regla de
 * autorización nueva que hay que escribir en {@link ReservationAccessPolicy} y
 * probar; una enumeración que crece sin que crezcan las reglas es una
 * enumeración que no se está usando.
 */
public enum ActorRole {

    /**
     * Titular. Sólo alcanza sus propias reservas, y ni siquiera puede
     * distinguir una reserva ajena de una inexistente.
     */
    CUSTOMER,

    /**
     * Operación interna y partners. Puede consultar y operar sobre reservas de
     * otros; cada vez que lo hace queda registrado en la auditoría.
     */
    BACKOFFICE
}
