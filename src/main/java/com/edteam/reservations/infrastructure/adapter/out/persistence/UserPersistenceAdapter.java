package com.edteam.reservations.infrastructure.adapter.out.persistence;

import com.edteam.reservations.application.port.out.UserRepositoryPort;
import com.edteam.reservations.domain.model.Email;
import com.edteam.reservations.domain.model.User;
import com.edteam.reservations.infrastructure.adapter.out.persistence.mapper.UserMapper;
import com.edteam.reservations.infrastructure.adapter.out.persistence.repository.UserJpaRepository;
import com.edteam.reservations.infrastructure.logging.PiiMasker;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * Adaptador de salida que implementa {@link UserRepositoryPort} sobre
 * PostgreSQL.
 *
 * <h2>Reutilización de filas</h2>
 * Igual que con segmentos y pasajeros, antes de insertar se intenta reutilizar
 * la fila existente usando la clave natural del modelo de datos: el
 * {@code UNIQUE} sobre {@code usuario.email}. Alguien que vuelve a reservar es
 * el mismo usuario, no uno nuevo.
 *
 * <p>Si el usuario ya existe, se lo devuelve tal cual está almacenado: la
 * reserva <em>identifica</em> al usuario, no le actualiza el perfil. Pisar el
 * nombre con el del último pedido convertiría una reserva en una edición
 * encubierta del usuario, y cualquier cliente podría renombrar a otro con sólo
 * conocer su email.
 *
 * <h2>Concurrencia</h2>
 * Entre la búsqueda y el insert hay una ventana en la que otra transacción
 * puede crear el mismo usuario. La cierra el {@code INSERT ... ON CONFLICT DO
 * NOTHING}: el perdedor no falla, y la consulta posterior encuentra la fila de
 * la otra transacción.
 */
@Repository
public class UserPersistenceAdapter implements UserRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(UserPersistenceAdapter.class);

    private final UserJpaRepository userRepository;
    private final UserMapper userMapper;

    public UserPersistenceAdapter(UserJpaRepository userRepository, UserMapper userMapper) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.userMapper = Objects.requireNonNull(userMapper);
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        Objects.requireNonNull(email, "El email es obligatorio");
        return userRepository.findByEmail(email.value()).map(userMapper::toDomain);
    }

    @Override
    public User findOrRegister(User candidate) {
        Objects.requireNonNull(candidate, "El usuario es obligatorio");

        String email = candidate.email().value();
        return userRepository.findByEmail(email).map(userMapper::toDomain).orElseGet(() -> register(candidate, email));
    }

    private User register(User candidate, String email) {
        int inserted = userRepository.insertIfAbsent(
                email, candidate.firstName(), candidate.lastName(), candidate.registeredAt());

        // El email va enmascarado en los dos casos: estos logs salen del
        // perímetro hacia el SaaS de observabilidad, que no tiene por qué
        // heredar un dato personal regulado. Y el alta baja a DEBUG: en
        // producción es ruido, y el dato que importa —cuántos usuarios se dan
        // de alta— ya está en las métricas.
        if (inserted == 0) {
            log.debug(
                    "Otra transacción dio de alta al usuario {} primero; se reutiliza su fila", PiiMasker.mask(email));
        } else {
            log.debug("Usuario dado de alta al reservar: {}", PiiMasker.mask(email));
        }

        return userRepository
                .findByEmail(email)
                .map(userMapper::toDomain)
                // Enmascarado, como las dos líneas de arriba. Sin esto el email
                // salía en claro por el camino de la excepción no controlada,
                // que además la loguea con stack trace y a nivel ERROR: el
                // mismo archivo declaraba la regla seis líneas antes y la
                // rompía acá (hallazgo 2).
                .orElseThrow(() -> new IllegalStateException(
                        "El usuario %s no quedó disponible después del insert".formatted(PiiMasker.mask(email))));
    }
}
