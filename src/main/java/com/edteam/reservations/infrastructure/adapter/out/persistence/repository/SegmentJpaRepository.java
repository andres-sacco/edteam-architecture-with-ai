package com.edteam.reservations.infrastructure.adapter.out.persistence.repository;

import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.SegmentJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * Acceso a la tabla {@code segmento}.
 *
 * <p>La búsqueda por clave natural es la que permite reutilizar el tramo en vez
 * de duplicarlo: coincide exactamente con el {@code UNIQUE (origen, destino,
 * aerolinea, fecha_vuelo)} del modelo de datos, que además es el índice que la
 * resuelve.
 */
public interface SegmentJpaRepository extends JpaRepository<SegmentJpaEntity, Long> {

    Optional<SegmentJpaEntity> findByOriginAndDestinationAndAirlineAndDepartureAt(
            String origin, String destination, String airline, Instant departureAt);

    /**
     * Inserta el segmento sólo si no existe, delegando la decisión en el
     * {@code UNIQUE} de la tabla.
     *
     * <p>Es un {@code INSERT ... ON CONFLICT DO NOTHING} y no un
     * "consulto y si no está inserto" porque eso último tiene una carrera: dos
     * transacciones simultáneas con el mismo tramo verían que no existe y las
     * dos intentarían insertarlo. La segunda recibiría una violación de
     * constraint, y con JPA eso marca la transacción entera como
     * <em>rollback-only</em>: ya no se podría ni reintentar ni seguir con la
     * reserva. {@code DO NOTHING} evita el error: si otra transacción está
     * insertando la misma fila, espera a que termine y no hace nada.
     *
     * <p>La fecha se pasa como {@link Instant}, el mismo tipo que mapea la
     * entidad, para que la consulta nativa y Hibernate escriban exactamente el
     * mismo valor en la columna {@code TIMESTAMP} sin zona. Pasar un
     * {@code LocalDateTime} no sirve: {@code hibernate.jdbc.time_zone} también
     * se le aplica y el valor queda corrido por el offset del servidor, con lo
     * que la búsqueda por clave natural no encontraría la fila recién insertada.
     *
     * @return 1 si insertó, 0 si ya existía
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO segmento (origen, destino, aerolinea, fecha_vuelo)
            VALUES (:origin, :destination, :airline, :departureAt)
            ON CONFLICT ON CONSTRAINT uq_segmento DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("origin") String origin,
                       @Param("destination") String destination,
                       @Param("airline") String airline,
                       @Param("departureAt") Instant departureAt);
}
