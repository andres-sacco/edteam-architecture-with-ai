package com.edteam.reservations.infrastructure.adapter.out.persistence.mapper;

import com.edteam.reservations.domain.model.AirportCode;
import com.edteam.reservations.domain.model.Segment;
import com.edteam.reservations.domain.model.SegmentId;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.SegmentJpaEntity;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Traduce segmentos entre el dominio y JPA.
 *
 * <p>Los mappers existen para que las entidades JPA no salgan nunca de este
 * paquete: son el único punto donde los dos modelos se conocen. Sin ellos, las
 * anotaciones de persistencia terminarían decorando el modelo de negocio y se
 * perdería la posibilidad de cambiar una cosa sin la otra.
 */
@Component
public class SegmentMapper {

    public Segment toDomain(SegmentJpaEntity entity) {
        return new Segment(
                Optional.of(SegmentId.of(entity.getId())),
                AirportCode.of(entity.getOrigin()),
                AirportCode.of(entity.getDestination()),
                entity.getAirline(),
                entity.getDepartureAt());
    }

    /**
     * Crea la entidad para un segmento nuevo. El id lo asigna la base, así que
     * va en nulo incluso si el segmento de dominio ya lo tuviera: para
     * reutilizar una fila existente el adaptador usa la fila que trajo de la
     * consulta, no una entidad construida acá.
     */
    public SegmentJpaEntity toNewEntity(Segment segment) {
        return new SegmentJpaEntity(
                null,
                segment.origin().value(),
                segment.destination().value(),
                segment.airline(),
                segment.departureAt());
    }
}
