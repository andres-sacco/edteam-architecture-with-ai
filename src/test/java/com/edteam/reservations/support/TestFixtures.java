package com.edteam.reservations.support;

import com.edteam.reservations.application.port.in.CreateReservationCommand;
import com.edteam.reservations.application.port.in.ItineraryData;
import com.edteam.reservations.application.port.in.PassengerData;
import com.edteam.reservations.application.port.in.SegmentData;
import com.edteam.reservations.application.port.in.UserData;
import com.edteam.reservations.domain.model.AirportCode;
import com.edteam.reservations.domain.model.IdempotencyKey;
import com.edteam.reservations.domain.model.Itinerary;
import com.edteam.reservations.domain.model.ItineraryId;
import com.edteam.reservations.domain.model.Money;
import com.edteam.reservations.domain.model.Passenger;
import com.edteam.reservations.domain.model.PassengerId;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationId;
import com.edteam.reservations.domain.model.ReservationStatus;
import com.edteam.reservations.domain.model.Email;
import com.edteam.reservations.domain.model.Segment;
import com.edteam.reservations.domain.model.SegmentId;
import com.edteam.reservations.domain.model.User;
import com.edteam.reservations.domain.model.UserId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Datos de prueba compartidos.
 *
 * <p>Centralizarlos evita que cada test repita la construcción del agregado y,
 * sobre todo, que un cambio en el modelo obligue a tocar veinte archivos.
 */
public final class TestFixtures {

    /** "Ahora" de referencia. Todos los tests trabajan con tiempo fijo. */
    public static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    public static final Instant DEPARTURE = NOW.plus(Duration.ofDays(20));
    public static final Instant CONNECTION_DEPARTURE = DEPARTURE.plus(Duration.ofHours(6));

    public static final AirportCode EZE = AirportCode.of("EZE");
    public static final AirportCode SCL = AirportCode.of("SCL");
    public static final AirportCode MAD = AirportCode.of("MAD");
    public static final AirportCode GRU = AirportCode.of("GRU");

    public static final String AIRLINE = "AEROLINEAS ARGENTINAS";

    public static final UserId USER_ID = UserId.of(1L);
    public static final String USER_EMAIL = "ana.perez@example.com";
    public static final ReservationId RESERVATION_ID = ReservationId.of(10L);
    public static final IdempotencyKey IDEMPOTENCY_KEY =
            IdempotencyKey.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));

    private TestFixtures() {
    }

    public static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    public static Money price() {
        return Money.of("1250.50", "USD");
    }

    public static Segment newSegment(AirportCode origin, AirportCode destination, Instant departureAt) {
        return Segment.newSegment(origin, destination, AIRLINE, departureAt);
    }

    public static Segment existingSegment(long id, AirportCode origin, AirportCode destination, Instant departureAt) {
        return Segment.existing(SegmentId.of(id), origin, destination, AIRLINE, departureAt);
    }

    /** Vuelo directo EZE-SCL, todavía sin salir. */
    public static Segment directSegment() {
        return newSegment(EZE, SCL, DEPARTURE);
    }

    /** Itinerario de un tramo, sin persistir. */
    public static Itinerary newItinerary() {
        return Itinerary.newItinerary(price(), List.of(directSegment()));
    }

    /** Itinerario con escala: EZE-SCL y SCL-MAD, sin persistir. */
    public static Itinerary connectingItinerary() {
        return Itinerary.newItinerary(price(), List.of(
                newSegment(EZE, SCL, DEPARTURE),
                newSegment(SCL, MAD, CONNECTION_DEPARTURE)));
    }

    /** Itinerario ya persistido, de un tramo. */
    public static Itinerary existingItinerary(long id) {
        return Itinerary.existing(ItineraryId.of(id), price(), List.of(existingSegment(100L, EZE, SCL, DEPARTURE)));
    }

    /** Itinerario cuyo primer tramo ya salió respecto de {@link #NOW}. */
    public static Itinerary departedItinerary() {
        return Itinerary.newItinerary(price(),
                List.of(newSegment(EZE, SCL, NOW.minus(Duration.ofDays(1)))));
    }

    public static Passenger newPassenger() {
        return Passenger.newPassenger("Ana", "Pérez", LocalDate.of(1990, 5, 20), "30123456");
    }

    public static Passenger newPassenger(String firstName, String document) {
        return Passenger.newPassenger(firstName, "Pérez", LocalDate.of(1990, 5, 20), document);
    }

    public static Passenger existingPassenger(long id) {
        return Passenger.existing(PassengerId.of(id), "Ana", "Pérez", LocalDate.of(1990, 5, 20), "30123456");
    }

    public static List<Passenger> newPassengers() {
        return List.of(newPassenger());
    }

    // ---------------------------------------------------------------------
    // Datos de entrada de los casos de uso
    // ---------------------------------------------------------------------

    public static SegmentData segmentData(AirportCode origin, AirportCode destination, Instant departureAt) {
        return new SegmentData(origin.value(), destination.value(), AIRLINE, departureAt);
    }

    /** Itinerario de entrada, un tramo EZE-SCL. */
    public static ItineraryData itineraryData() {
        return new ItineraryData(new java.math.BigDecimal("1250.50"), "USD",
                List.of(segmentData(EZE, SCL, DEPARTURE)));
    }

    /** Itinerario de entrada con escala, EZE-SCL-MAD. */
    public static ItineraryData connectingItineraryData() {
        return new ItineraryData(new java.math.BigDecimal("1980.00"), "USD",
                List.of(segmentData(EZE, SCL, DEPARTURE), segmentData(SCL, MAD, CONNECTION_DEPARTURE)));
    }

    /** Datos de entrada del usuario que reserva. */
    public static UserData userData() {
        return new UserData(USER_EMAIL, "Ana", "Pérez");
    }

    /** Usuario ya persistido, con el id de referencia. */
    public static User storedUser() {
        return User.of(USER_ID, Email.of(USER_EMAIL), "Ana", "Pérez", NOW);
    }

    public static List<PassengerData> passengerData() {
        return List.of(new PassengerData("Ana", "Pérez", LocalDate.of(1990, 5, 20), "30123456"));
    }

    public static CreateReservationCommand createCommand() {
        return new CreateReservationCommand(
                userData(), IDEMPOTENCY_KEY.value().toString(), itineraryData(), passengerData());
    }

    /** Reserva nueva, sin id ni versión, en estado PENDING. */
    public static Reservation newReservation() {
        return Reservation.create(storedUser(), IDEMPOTENCY_KEY, newItinerary(), newPassengers(), NOW);
    }

    /** Reserva ya persistida, con la versión indicada. */
    public static Reservation storedReservation(long version) {
        return storedReservation(version, ReservationStatus.PENDING);
    }

    /** Reserva ya persistida, con la versión y el estado indicados. */
    public static Reservation storedReservation(long version, ReservationStatus status) {
        return Reservation.rehydrate(
                RESERVATION_ID,
                storedUser(),
                IDEMPOTENCY_KEY,
                existingItinerary(50L),
                List.of(existingPassenger(200L)),
                status,
                NOW,
                NOW,
                version);
    }
}
