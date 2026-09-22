package com.edteam.reservations.infrastructure.adapter.out.persistence.repository;

import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.ReservationJpaEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Acceso a la tabla {@code reserva}.
 *
 * <p>Las consultas usan un {@code @EntityGraph} para traer usuario, itinerario,
 * segmentos y pasajeros en la misma query. Sin eso, reconstruir el agregado
 * dispararía el clásico N+1: una consulta por la reserva y varias más por cada
 * colección, en cada lectura.
 *
 * <p>Ambas colecciones son ordenadas ({@code @OrderColumn}) o {@code Set}, no
 * <em>bags</em>, así que Hibernate admite traerlas juntas.
 */
public interface ReservationJpaRepository extends JpaRepository<ReservationJpaEntity, Long> {

    @Override
    @EntityGraph(attributePaths = {"user", "itinerary", "itinerary.segments", "passengers"})
    Optional<ReservationJpaEntity> findById(Long id);

    @EntityGraph(attributePaths = {"user", "itinerary", "itinerary.segments", "passengers"})
    Optional<ReservationJpaEntity> findByIdempotencyKey(UUID idempotencyKey);

    /**
     * Lee los agregados completos de una página ya paginada.
     *
     * <p>No lleva orden ni {@code Pageable}: la página y su orden los resolvió
     * antes {@code ReservationSearchJpaQuery} sobre los ids. Acá sólo se
     * hidratan esas reservas, y el orden lo repone el adaptador siguiendo la
     * lista de ids. Hacerlo al revés —paginar con las colecciones ya
     * traídas— obligaría a Hibernate a paginar en memoria.
     */
    @EntityGraph(attributePaths = {"user", "itinerary", "itinerary.segments", "passengers"})
    List<ReservationJpaEntity> findAllByIdIn(Collection<Long> ids);
}
