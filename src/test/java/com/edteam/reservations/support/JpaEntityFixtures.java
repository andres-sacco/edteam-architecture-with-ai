package com.edteam.reservations.support;

import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.ItineraryJpaEntity;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.PassengerJpaEntity;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.ReservationJpaEntity;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.ReservationStatusJpa;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.SegmentJpaEntity;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.UserJpaEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Entidades JPA ya "persistidas" para los tests de los mappers.
 *
 * <p>El id y la versión los genera la base ({@code BIGSERIAL} y
 * {@code @Version}), así que las entidades no tienen setters para ellos: es
 * correcto que producción no pueda asignarlos a mano. Para simular una fila ya
 * leída se usa {@link ReflectionTestUtils}, que es exactamente el caso de uso
 * para el que existe.
 */
public final class JpaEntityFixtures {

    private JpaEntityFixtures() {}

    public static SegmentJpaEntity segment(long id, String origin, String destination, Instant departureAt) {
        SegmentJpaEntity entity = new SegmentJpaEntity(null, origin, destination, TestFixtures.AIRLINE, departureAt);
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    public static PassengerJpaEntity passenger(long id, String firstName, String documentNumber) {
        PassengerJpaEntity entity =
                new PassengerJpaEntity(null, firstName, "Pérez", LocalDate.of(1990, 5, 20), documentNumber);
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    public static ItineraryJpaEntity itinerary(
            long id, String price, String currency, List<SegmentJpaEntity> segments) {
        ItineraryJpaEntity entity = new ItineraryJpaEntity(null, new BigDecimal(price), currency, segments);
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    /** Itinerario de un tramo EZE-SCL ya persistido. */
    public static ItineraryJpaEntity directItinerary(long id) {
        return itinerary(id, "1250.50", "USD", List.of(segment(100L, "EZE", "SCL", TestFixtures.DEPARTURE)));
    }

    /** Usuario ya persistido, con el id de referencia de los fixtures. */
    public static UserJpaEntity user() {
        UserJpaEntity entity = new UserJpaEntity(null, TestFixtures.USER_EMAIL, "Ana", "Pérez", TestFixtures.NOW);
        ReflectionTestUtils.setField(entity, "id", TestFixtures.USER_ID.value());
        return entity;
    }

    public static ReservationJpaEntity reservation(
            long id,
            int version,
            ReservationStatusJpa status,
            ItineraryJpaEntity itinerary,
            Set<PassengerJpaEntity> passengers,
            UUID idempotencyKey) {
        ReservationJpaEntity entity = new ReservationJpaEntity(
                user(), itinerary, status, TestFixtures.NOW, TestFixtures.NOW, idempotencyKey, passengers);
        ReflectionTestUtils.setField(entity, "id", id);
        ReflectionTestUtils.setField(entity, "version", version);
        return entity;
    }

    /** Reserva pendiente ya persistida, con un pasajero y un itinerario directo. */
    public static ReservationJpaEntity pendingReservation(long id, int version) {
        Set<PassengerJpaEntity> passengers = new LinkedHashSet<>(List.of(passenger(200L, "Ana", "30123456")));
        return reservation(
                id,
                version,
                ReservationStatusJpa.PENDIENTE,
                directItinerary(50L),
                passengers,
                TestFixtures.IDEMPOTENCY_KEY.value());
    }
}
