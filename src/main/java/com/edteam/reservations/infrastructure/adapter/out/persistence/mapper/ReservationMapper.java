package com.edteam.reservations.infrastructure.adapter.out.persistence.mapper;

import com.edteam.reservations.domain.model.IdempotencyKey;
import com.edteam.reservations.domain.model.Passenger;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationId;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.ItineraryJpaEntity;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.PassengerJpaEntity;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.ReservationJpaEntity;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.ReservationStatusJpa;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.UserJpaEntity;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Traduce la reserva completa entre el dominio y JPA.
 *
 * <p>Es el mapper del agregado: arma la reserva con su itinerario y sus
 * pasajeros, y por eso delega en {@link ItineraryMapper} y
 * {@link PassengerMapper} en lugar de repetir esa traducción.
 */
@Component
public class ReservationMapper {

    private final ItineraryMapper itineraryMapper;
    private final PassengerMapper passengerMapper;
    private final UserMapper userMapper;

    public ReservationMapper(ItineraryMapper itineraryMapper, PassengerMapper passengerMapper, UserMapper userMapper) {
        this.itineraryMapper = Objects.requireNonNull(itineraryMapper);
        this.passengerMapper = Objects.requireNonNull(passengerMapper);
        this.userMapper = Objects.requireNonNull(userMapper);
    }

    public Reservation toDomain(ReservationJpaEntity entity) {
        // Los pasajeros van por un Set: se ordenan por id para que el agregado
        // se reconstruya siempre igual y los tests no dependan del orden en que
        // la base devuelva las filas.
        List<Passenger> passengers = entity.getPassengers().stream()
                .sorted(Comparator.comparing(PassengerJpaEntity::getId))
                .map(passengerMapper::toDomain)
                .toList();

        return Reservation.rehydrate(
                ReservationId.of(entity.getId()),
                userMapper.toDomain(entity.getUser()),
                IdempotencyKey.of(entity.getIdempotencyKey()),
                itineraryMapper.toDomain(entity.getItinerary()),
                passengers,
                entity.getStatus().toDomain(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion());
    }

    /**
     * Crea la entidad para una reserva nueva, con el itinerario y los pasajeros
     * ya resueltos y persistidos por el adaptador.
     *
     * <p>Ni el id ni la versión se asignan acá: son de la base
     * ({@code BIGSERIAL} y {@code @Version}).
     */
    public ReservationJpaEntity toNewEntity(
            Reservation reservation,
            UserJpaEntity user,
            ItineraryJpaEntity itinerary,
            List<PassengerJpaEntity> passengers) {
        Set<PassengerJpaEntity> uniquePassengers = new LinkedHashSet<>(passengers);
        return new ReservationJpaEntity(
                user,
                itinerary,
                ReservationStatusJpa.fromDomain(reservation.status()),
                reservation.createdAt(),
                reservation.updatedAt(),
                reservation.idempotencyKey().value(),
                uniquePassengers);
    }
}
