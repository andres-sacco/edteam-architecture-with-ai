package com.edteam.reservations.application.port.out;

import com.edteam.reservations.domain.model.User;

/**
 * Puerto de salida hacia el almacenamiento de usuarios.
 *
 * <p>Existe porque la reserva necesita un usuario al cual atribuirse y el
 * sistema no tiene —todavía— un alta de usuarios propia. Antes, la fila tenía
 * que existir de antemano: la API pedía un {@code userId} y la clave foránea
 * rechazaba cualquier valor que no estuviera cargado a mano en la base.
 */
public interface UserRepositoryPort {

    /**
     * Devuelve el usuario con ese email, dándolo de alta si es la primera vez
     * que reserva.
     *
     * <p>Contrato que debe cumplir toda implementación:
     * <ul>
     *   <li>Si ya hay un usuario con ese email, lo devuelve con su id y
     *       <em>sin</em> modificarlo: la reserva identifica al usuario, no le
     *       actualiza el perfil.</li>
     *   <li>Si no lo hay, lo inserta y lo devuelve con el id asignado.</li>
     *   <li>Dos altas simultáneas con el mismo email no pueden crear dos
     *       filas ni romper la transacción: el {@code UNIQUE} del modelo de
     *       datos es el que cierra la carrera.</li>
     * </ul>
     *
     * @param candidate usuario sin id, tal como lo describe el pedido
     * @return el usuario tal como quedó almacenado, siempre con id
     */
    User findOrRegister(User candidate);
}
