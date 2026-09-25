package com.edteam.reservations.infrastructure.adapter.out.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.edteam.reservations.domain.model.Itinerary;
import com.edteam.reservations.domain.model.ItineraryId;
import com.edteam.reservations.domain.model.Passenger;
import com.edteam.reservations.domain.model.PassengerId;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationStatus;
import com.edteam.reservations.domain.model.Segment;
import com.edteam.reservations.domain.model.SegmentId;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.ItineraryJpaEntity;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.PassengerJpaEntity;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.ReservationJpaEntity;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.ReservationStatusJpa;
import com.edteam.reservations.infrastructure.adapter.out.persistence.entity.SegmentJpaEntity;
import com.edteam.reservations.support.JpaEntityFixtures;
import com.edteam.reservations.support.TestFixtures;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Mappers de persistencia")
class PersistenceMapperTest {

    private final SegmentMapper segmentMapper = new SegmentMapper();
    private final PassengerMapper passengerMapper = new PassengerMapper();
    private final ItineraryMapper itineraryMapper = new ItineraryMapper(segmentMapper);
    private final UserMapper userMapper = new UserMapper();
    private final ReservationMapper reservationMapper =
            new ReservationMapper(itineraryMapper, passengerMapper, userMapper);

    @Nested
    @DisplayName("SegmentMapper")
    class Segments {

        @Test
        @DisplayName("lleva la fila a dominio con su id")
        void mapsToDomain() {
            Segment segment =
                    segmentMapper.toDomain(JpaEntityFixtures.segment(7L, "EZE", "SCL", TestFixtures.DEPARTURE));

            assertThat(segment.id()).contains(SegmentId.of(7L));
            assertThat(segment.origin()).isEqualTo(TestFixtures.EZE);
            assertThat(segment.destination()).isEqualTo(TestFixtures.SCL);
            assertThat(segment.airline()).isEqualTo(TestFixtures.AIRLINE);
            assertThat(segment.departureAt()).isEqualTo(TestFixtures.DEPARTURE);
        }

        @Test
        @DisplayName("la entidad nueva va sin id, porque lo asigna la base")
        void mapsToNewEntityWithoutId() {
            SegmentJpaEntity entity = segmentMapper.toNewEntity(TestFixtures.directSegment());

            assertThat(entity.getId()).isNull();
            assertThat(entity.getOrigin()).isEqualTo("EZE");
            assertThat(entity.getDestination()).isEqualTo("SCL");
            assertThat(entity.getDepartureAt()).isEqualTo(TestFixtures.DEPARTURE);
        }

        @Test
        @DisplayName("ida y vuelta: dominio -> entidad -> dominio conserva los datos")
        void roundTripsThroughTheEntity() {
            SegmentJpaEntity entity = segmentMapper.toNewEntity(TestFixtures.directSegment());
            SegmentJpaEntity persisted =
                    JpaEntityFixtures.segment(1L, entity.getOrigin(), entity.getDestination(), entity.getDepartureAt());

            Segment result = segmentMapper.toDomain(persisted);

            assertThat(result.naturalKey())
                    .isEqualTo(TestFixtures.directSegment().naturalKey());
        }
    }

    @Nested
    @DisplayName("PassengerMapper")
    class Passengers {

        @Test
        @DisplayName("lleva la fila a dominio con su id y su documento")
        void mapsToDomain() {
            Passenger passenger = passengerMapper.toDomain(JpaEntityFixtures.passenger(3L, "Ana", "30123456"));

            assertThat(passenger.id()).contains(PassengerId.of(3L));
            assertThat(passenger.firstName()).isEqualTo("Ana");
            assertThat(passenger.documentNumber()).contains("30123456");
        }

        @Test
        @DisplayName("traduce el documento nulo de la columna a Optional vacío")
        void mapsNullDocumentToEmptyOptional() {
            Passenger passenger = passengerMapper.toDomain(JpaEntityFixtures.passenger(3L, "Ana", null));

            assertThat(passenger.documentNumber()).isEmpty();
        }

        @Test
        @DisplayName("traduce el Optional vacío del dominio a columna nula")
        void mapsEmptyOptionalToNullColumn() {
            PassengerJpaEntity entity = passengerMapper.toNewEntity(
                    Passenger.newPassenger("Ana", "Pérez", java.time.LocalDate.of(1990, 5, 20), null));

            assertThat(entity.getDocumentNumber()).isNull();
            assertThat(entity.getId()).isNull();
        }
    }

    @Nested
    @DisplayName("ItineraryMapper")
    class Itineraries {

        @Test
        @DisplayName("lleva la fila a dominio con precio, moneda y segmentos en orden")
        void mapsToDomain() {
            ItineraryJpaEntity entity = JpaEntityFixtures.itinerary(
                    9L,
                    "1980.00",
                    "USD",
                    List.of(
                            JpaEntityFixtures.segment(1L, "EZE", "SCL", TestFixtures.DEPARTURE),
                            JpaEntityFixtures.segment(2L, "SCL", "MAD", TestFixtures.CONNECTION_DEPARTURE)));

            Itinerary itinerary = itineraryMapper.toDomain(entity);

            assertThat(itinerary.id()).contains(ItineraryId.of(9L));
            assertThat(itinerary.price().amount()).isEqualByComparingTo("1980.00");
            assertThat(itinerary.price().currency()).isEqualTo("USD");
            assertThat(itinerary.segments()).hasSize(2);
            assertThat(itinerary.origin()).isEqualTo(TestFixtures.EZE);
            assertThat(itinerary.destination()).isEqualTo(TestFixtures.MAD);
        }

        @Test
        @DisplayName("la entidad nueva toma los segmentos ya resueltos, en el orden recibido")
        void mapsToNewEntityWithResolvedSegments() {
            List<SegmentJpaEntity> resolved = List.of(
                    JpaEntityFixtures.segment(1L, "EZE", "SCL", TestFixtures.DEPARTURE),
                    JpaEntityFixtures.segment(2L, "SCL", "MAD", TestFixtures.CONNECTION_DEPARTURE));

            ItineraryJpaEntity entity = itineraryMapper.toNewEntity(TestFixtures.connectingItinerary(), resolved);

            assertThat(entity.getId()).isNull();
            assertThat(entity.getPrice()).isEqualByComparingTo("1250.50");
            assertThat(entity.getSegments()).containsExactlyElementsOf(resolved);
        }
    }

    @Nested
    @DisplayName("ReservationMapper")
    class Reservations {

        @Test
        @DisplayName("reconstruye el agregado completo desde la fila")
        void mapsToDomain() {
            Reservation reservation = reservationMapper.toDomain(JpaEntityFixtures.pendingReservation(10L, 4));

            assertThat(reservation.requireId()).isEqualTo(TestFixtures.RESERVATION_ID);
            assertThat(reservation.userId()).isEqualTo(TestFixtures.USER_ID);
            assertThat(reservation.idempotencyKey()).isEqualTo(TestFixtures.IDEMPOTENCY_KEY);
            assertThat(reservation.status()).isEqualTo(ReservationStatus.PENDING);
            assertThat(reservation.version()).isEqualTo(4L);
            assertThat(reservation.createdAt()).isEqualTo(TestFixtures.NOW);
            assertThat(reservation.itinerary().id()).contains(ItineraryId.of(50L));
            assertThat(reservation.passengers())
                    .singleElement()
                    .satisfies(passenger -> assertThat(passenger.id()).contains(PassengerId.of(200L)));
        }

        @Test
        @DisplayName("ordena los pasajeros por id, para que el agregado se reconstruya siempre igual")
        void ordersPassengersDeterministically() {
            Set<PassengerJpaEntity> desordenados = new LinkedHashSet<>(List.of(
                    JpaEntityFixtures.passenger(300L, "Zoe", "30999888"),
                    JpaEntityFixtures.passenger(100L, "Ana", "30123456")));
            ReservationJpaEntity entity = JpaEntityFixtures.reservation(
                    10L,
                    0,
                    ReservationStatusJpa.PENDIENTE,
                    JpaEntityFixtures.directItinerary(50L),
                    desordenados,
                    TestFixtures.IDEMPOTENCY_KEY.value());

            Reservation reservation = reservationMapper.toDomain(entity);

            assertThat(reservation.passengers())
                    .extracting(Passenger::firstName)
                    .containsExactly("Ana", "Zoe");
        }

        @Test
        @DisplayName("la entidad nueva no lleva id ni versión: son de la base")
        void mapsToNewEntityWithoutGeneratedValues() {
            ReservationJpaEntity entity = reservationMapper.toNewEntity(
                    TestFixtures.newReservation(),
                    JpaEntityFixtures.user(),
                    JpaEntityFixtures.directItinerary(50L),
                    List.of(JpaEntityFixtures.passenger(200L, "Ana", "30123456")));

            assertThat(entity.getId()).isNull();
            assertThat(entity.getVersion()).isNull();
            assertThat(entity.getUser().getEmail()).isEqualTo(TestFixtures.USER_EMAIL);
            assertThat(entity.getIdempotencyKey()).isEqualTo(TestFixtures.IDEMPOTENCY_KEY.value());
            assertThat(entity.getStatus()).isEqualTo(ReservationStatusJpa.PENDIENTE);
            assertThat(entity.getPassengers()).hasSize(1);
        }

        @Test
        @DisplayName("traduce los tres estados en los dos sentidos")
        void mapsEveryStatus() {
            for (ReservationStatus status : ReservationStatus.values()) {
                assertThat(ReservationStatusJpa.fromDomain(status).toDomain()).isEqualTo(status);
            }
            assertThat(ReservationStatusJpa.PENDIENTE.toDomain()).isEqualTo(ReservationStatus.PENDING);
            assertThat(ReservationStatusJpa.CONFIRMADA.toDomain()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(ReservationStatusJpa.CANCELADA.toDomain()).isEqualTo(ReservationStatus.CANCELLED);
        }
    }
}
