package com.edteam.reservations.infrastructure.adapter.out.persistence.repository;

import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.UserJpaEntity;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Acceso a la tabla {@code usuario}.
 *
 * <p>La búsqueda por email usa el {@code UNIQUE} del modelo de datos y es lo
 * que evita duplicar a la misma persona entre reservas.
 */
public interface UserJpaRepository extends JpaRepository<UserJpaEntity, Long> {

    Optional<UserJpaEntity> findByEmail(String email);

    /**
     * Inserta el usuario sólo si no hay otro con el mismo email.
     *
     * <p>Mismo razonamiento que en {@code PassengerJpaRepository.insertIfAbsent}:
     * el {@code DO NOTHING} resuelve la carrera entre dos reservas simultáneas
     * del mismo usuario nuevo sin romper la transacción. Sin esto, la segunda
     * recibiría una violación de constraint y perdería la reserva entera por
     * un usuario que, de hecho, ya existe.
     *
     * @return 1 si insertó, 0 si ya existía
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO usuario (email, nombre, apellido, fecha_alta)
            VALUES (:email, :firstName, :lastName, :registeredAt)
            ON CONFLICT ON CONSTRAINT uq_usuario_email DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("email") String email,
            @Param("firstName") String firstName,
            @Param("lastName") String lastName,
            @Param("registeredAt") Instant registeredAt);
}
