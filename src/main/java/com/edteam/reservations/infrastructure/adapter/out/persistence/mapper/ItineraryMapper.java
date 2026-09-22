package com.edteam.reservations.infrastructure.adapter.out.persistence.mapper;

import com.edteam.reservations.domain.model.Itinerary;
import com.edteam.reservations.domain.model.ItineraryId;
import com.edteam.reservations.domain.model.Money;
import com.edteam.reservations.domain.model.Segment;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.ItineraryJpaEntity;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.SegmentJpaEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Traduce itinerarios entre el dominio y JPA. */
@Component
public class ItineraryMapper {

    private final SegmentMapper segmentMapper;

    public ItineraryMapper(SegmentMapper segmentMapper) {
        this.segmentMapper = Objects.requireNonNull(segmentMapper);
    }

    public Itinerary toDomain(ItineraryJpaEntity entity) {
        List<Segment> segments = entity.getSegments().stream()
                .map(segmentMapper::toDomain)
                .toList();

        return Itinerary.existing(
                ItineraryId.of(entity.getId()),
                new Money(entity.getPrice(), entity.getCurrency()),
                segments);
    }

    /**
     * Crea la entidad para un itinerario nuevo, con los segmentos ya resueltos
     * por el adaptador (algunos serán filas preexistentes y otros nuevos).
     *
     * <p>El orden de la lista es el que termina en la columna {@code orden} de
     * la tabla intermedia, así que se preserva tal cual.
     */
    public ItineraryJpaEntity toNewEntity(Itinerary itinerary, List<SegmentJpaEntity> resolvedSegments) {
        Money price = itinerary.price();
        return new ItineraryJpaEntity(null, price.amount(), price.currency(), resolvedSegments);
    }
}
