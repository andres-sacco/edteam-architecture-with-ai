package com.edteam.reservations.infrastructure.adapter.out.persistence.repository;

import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.PassengerJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Acceso a la tabla {@code pasajero}.
 *
 * <p>La búsqueda por documento usa el {@code UNIQUE} del modelo de datos y es
 * lo que evita duplicar a la misma persona entre reservas.
 */
public interface PassengerJpaRepository extends JpaRepository<PassengerJpaEntity, Long> {

    Optional<PassengerJpaEntity> findByDocumentNumber(String documentNumber);

    /**
     * Inserta el pasajero sólo si no hay otro con el mismo documento.
     *
     * <p>Mismo razonamiento que en {@code SegmentJpaRepository.insertIfAbsent}:
     * el {@code DO NOTHING} resuelve la carrera entre dos reservas simultáneas
     * del mismo pasajero sin romper la transacción.
     *
     * @return 1 si insertó, 0 si ya existía
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO pasajero (nombre, apellido, fecha_nacimiento, documento)
            VALUES (:firstName, :lastName, :birthDate, :documentNumber)
            ON CONFLICT ON CONSTRAINT uq_pasajero_doc DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("firstName") String firstName,
                       @Param("lastName") String lastName,
                       @Param("birthDate") LocalDate birthDate,
                       @Param("documentNumber") String documentNumber);
}
