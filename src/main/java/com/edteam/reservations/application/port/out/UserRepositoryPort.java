package com.edteam.reservations.application.port.out;

import com.edteam.reservations.domain.model.Email;
import com.edteam.reservations.domain.model.User;

import java.util.Optional;

/** Persistencia del maestro de usuarios. */
public interface UserRepositoryPort {

    /**
     * Busca al usuario sin darlo de alta.
     *
     * <p>Hace falta porque la búsqueda por clave de idempotencia ahora se
     * alcanza al usuario, y esa búsqueda ocurre antes de validar el pedido.
     * Sin este método, el alta habría que adelantarla también, y entonces
     * cualquier pedido inválido dejaría una fila en el maestro: el mismo
     * envenenamiento por altas basura que el rate limiting trata de acotar.
     */
    Optional<User> findByEmail(Email email);

    /**
     * Devuelve el usuario existente o lo da de alta.
     *
     * <p>Resuelve la carrera entre dos altas simultáneas del mismo email en la
     * base y no en la aplicación.
     */
    User findOrRegister(User candidate);
}
