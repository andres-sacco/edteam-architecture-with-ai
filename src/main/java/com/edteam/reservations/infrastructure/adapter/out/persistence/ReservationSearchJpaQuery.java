package com.edteam.reservations.infrastructure.adapter.out.persistence;

import com.edteam.reservations.application.query.ReservationSearchCriteria;
import com.edteam.reservations.application.query.ReservationSortBy;
import com.edteam.reservations.application.query.SortDirection;
import com.edteam.reservations.domain.model.ReservationStatus;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.ItineraryJpaEntity;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.ReservationJpaEntity;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.ReservationStatusJpa;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.SegmentJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.ListJoin;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resuelve la parte "buscar ids" del listado de reservas.
 *
 * <p>Está separado del adaptador porque resuelve un problema distinto: el
 * adaptador traduce entre dominio y JPA, y esto arma una consulta dinámica.
 *
 * <h2>Por qué en dos consultas</h2>
 * El agregado se lee con {@code @EntityGraph} para traer itinerario, segmentos
 * y pasajeros sin caer en un N+1. Pero paginar <em>y</em> traer colecciones en
 * la misma consulta no se puede: el SQL devuelve varias filas por reserva, así
 * que {@code LIMIT} recortaría por filas y no por reservas. Hibernate lo
 * resuelve trayendo todo y paginando en memoria —con la advertencia
 * {@code HHH90003004}—, que es exactamente lo que no se quiere en un listado.
 *
 * <p>Por eso se pagina acá sobre los ids, sin colecciones (una fila por
 * reserva, {@code LIMIT} real en la base), y después se leen los agregados
 * completos de esa página.
 *
 * <h2>Orden estable</h2>
 * Todo orden termina desempatando por id. Sin desempate, dos reservas con el
 * mismo {@code createdAt} pueden salir en distinto orden en dos consultas, y
 * entonces la página 2 repite o se saltea filas que ya mostró la página 1.
 *
 * <h2>El primer tramo</h2>
 * Filtrar y ordenar por fecha de salida significa la salida del <em>primer</em>
 * tramo. El orden de los tramos es la columna {@code orden} de
 * {@code itinerario_segmento}, que en el mapeo es el {@code @OrderColumn} de la
 * lista: {@code index(segmento) = 0} es el primer tramo, y el join no
 * multiplica filas porque hay exactamente uno por itinerario.
 */
@Component
public class ReservationSearchJpaQuery {

    private static final int FIRST_SEGMENT_INDEX = 0;

    @PersistenceContext
    private EntityManager entityManager;

    /** Cantidad total de reservas que cumplen el filtro, ignorando la paginación. */
    public long count(ReservationSearchCriteria criteria) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<ReservationJpaEntity> reservation = query.from(ReservationJpaEntity.class);
        ListJoin<ItineraryJpaEntity, SegmentJpaEntity> firstSegment = joinFirstSegment(reservation);

        query.select(builder.count(reservation.get("id")))
                .where(toPredicates(builder, reservation, firstSegment, criteria));

        return entityManager.createQuery(query).getSingleResult();
    }

    /** Ids de la página pedida, ya en el orden del criterio. */
    public List<Long> findPageOfIds(ReservationSearchCriteria criteria) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<ReservationJpaEntity> reservation = query.from(ReservationJpaEntity.class);
        ListJoin<ItineraryJpaEntity, SegmentJpaEntity> firstSegment = joinFirstSegment(reservation);

        query.select(reservation.get("id"))
                .where(toPredicates(builder, reservation, firstSegment, criteria))
                .orderBy(toOrder(builder, reservation, firstSegment, criteria));

        return entityManager.createQuery(query)
                .setFirstResult(criteria.offset())
                .setMaxResults(criteria.size())
                .getResultList();
    }

    private static ListJoin<ItineraryJpaEntity, SegmentJpaEntity> joinFirstSegment(
            Root<ReservationJpaEntity> reservation) {
        Join<ReservationJpaEntity, ItineraryJpaEntity> itinerary = reservation.join("itinerary");
        return itinerary.joinList("segments");
    }

    private static Predicate[] toPredicates(CriteriaBuilder builder,
                                            Root<ReservationJpaEntity> reservation,
                                            ListJoin<ItineraryJpaEntity, SegmentJpaEntity> firstSegment,
                                            ReservationSearchCriteria criteria) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(builder.equal(firstSegment.index(), FIRST_SEGMENT_INDEX));

        // El filtro llega como email —lo que el cliente conoce— y se resuelve
        // con un join contra usuario, no con el id interno.
        criteria.userEmail().ifPresent(email ->
                predicates.add(builder.equal(reservation.join("user").get("email"), email.value())));

        if (criteria.filtersByStatus()) {
            predicates.add(reservation.get("status").in(toJpaStatuses(criteria.statuses())));
        }

        Path<Instant> departureAt = firstSegment.get("departureAt");
        criteria.departureFrom().ifPresent(from ->
                predicates.add(builder.greaterThanOrEqualTo(departureAt, from)));
        criteria.departureTo().ifPresent(to ->
                predicates.add(builder.lessThanOrEqualTo(departureAt, to)));

        return predicates.toArray(Predicate[]::new);
    }

    private static List<Order> toOrder(CriteriaBuilder builder,
                                       Root<ReservationJpaEntity> reservation,
                                       ListJoin<ItineraryJpaEntity, SegmentJpaEntity> firstSegment,
                                       ReservationSearchCriteria criteria) {
        Path<?> sortPath = criteria.sortBy() == ReservationSortBy.FIRST_DEPARTURE_AT
                ? firstSegment.get("departureAt")
                : reservation.get("createdAt");
        Path<?> tieBreaker = reservation.get("id");

        boolean ascending = criteria.direction() == SortDirection.ASC;
        return ascending
                ? List.of(builder.asc(sortPath), builder.asc(tieBreaker))
                : List.of(builder.desc(sortPath), builder.desc(tieBreaker));
    }

    private static Set<ReservationStatusJpa> toJpaStatuses(Set<ReservationStatus> statuses) {
        return statuses.stream().map(ReservationStatusJpa::fromDomain).collect(Collectors.toSet());
    }
}
