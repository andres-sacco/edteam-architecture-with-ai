package com.edteam.reservations.infrastructure.adapter.out.persistence.mapper;

import com.edteam.reservations.domain.model.Passenger;
import com.edteam.reservations.domain.model.PassengerId;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.PassengerJpaEntity;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Traduce pasajeros entre el dominio y JPA. */
@Component
public class PassengerMapper {

    public Passenger toDomain(PassengerJpaEntity entity) {
        return new Passenger(
                Optional.of(PassengerId.of(entity.getId())),
                entity.getFirstName(),
                entity.getLastName(),
                entity.getBirthDate(),
                Optional.ofNullable(entity.getDocumentNumber()));
    }

    /** Crea la entidad para un pasajero nuevo; el id lo asigna la base. */
    public PassengerJpaEntity toNewEntity(Passenger passenger) {
        return new PassengerJpaEntity(
                null,
                passenger.firstName(),
                passenger.lastName(),
                passenger.birthDate(),
                passenger.documentNumber().orElse(null));
    }
}
