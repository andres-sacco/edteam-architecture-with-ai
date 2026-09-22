package com.edteam.reservations.domain.model;

import com.edteam.reservations.domain.exception.InvalidReservationException;
import com.edteam.reservations.domain.exception.ItineraryAlreadyDepartedException;
import com.edteam.reservations.domain.exception.ReservationAlreadyCancelledException;
import com.edteam.reservations.domain.exception.ReservationNotModifiableException;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Reservation (agregado)")
class ReservationTest {

    @Nested
    @DisplayName("creación")
    class Creation {

        @Test
        @DisplayName("nace pendiente, sin id, en versión 0 y con las fechas de auditoría en el instante actual")
        void createsPendingReservation() {
            Reservation reservation = TestFixtures.newReservation();

            assertThat(reservation.id()).isEmpty();
            assertThat(reservation.status()).isEqualTo(ReservationStatus.PENDING);
            assertThat(reservation.status().isActive()).isTrue();
            assertThat(reservation.version()).isZero();
            assertThat(reservation.userId()).isEqualTo(TestFixtures.USER_ID);
            assertThat(reservation.idempotencyKey()).isEqualTo(TestFixtures.IDEMPOTENCY_KEY);
            assertThat(reservation.createdAt()).isEqualTo(TestFixtures.NOW);
            assertThat(reservation.updatedAt()).isEqualTo(TestFixtures.NOW);
            assertThat(reservation.passengers()).hasSize(1);
        }

        @Test
        @DisplayName("acepta varios pasajeros")
        void acceptsSeveralPassengers() {
            Reservation reservation = Reservation.create(
                    TestFixtures.USER_ID, TestFixtures.IDEMPOTENCY_KEY, TestFixtures.newItinerary(),
                    List.of(TestFixtures.newPassenger("Ana", "30123456"),
                            TestFixtures.newPassenger("Juan", "30999888")),
                    TestFixtures.NOW);

            assertThat(reservation.passengers()).hasSize(2);
        }

        @Test
        @DisplayName("rechaza una reserva sin pasajeros")
        void rejectsReservationWithoutPassengers() {
            assertThatThrownBy(() -> Reservation.create(
                    TestFixtures.USER_ID, TestFixtures.IDEMPOTENCY_KEY, TestFixtures.newItinerary(),
                    List.of(), TestFixtures.NOW))
                    .isInstanceOf(InvalidReservationException.class)
                    .hasMessageContaining("al menos un pasajero");
        }

        @Test
        @DisplayName("rechaza el mismo pasajero repetido")
        void rejectsDuplicatePassengers() {
            assertThatThrownBy(() -> Reservation.create(
                    TestFixtures.USER_ID, TestFixtures.IDEMPOTENCY_KEY, TestFixtures.newItinerary(),
                    List.of(TestFixtures.newPassenger("Ana", "30123456"),
                            TestFixtures.newPassenger("Otro Nombre", "30123456")),
                    TestFixtures.NOW))
                    .isInstanceOf(InvalidReservationException.class)
                    .hasMessageContaining("más de una vez");
        }

        @Test
        @DisplayName("rechaza un pasajero con fecha de nacimiento futura")
        void rejectsPassengerBornInTheFuture() {
            Passenger futuro = Passenger.newPassenger("Bebé", "Pérez", LocalDate.of(2026, 12, 1), "40000000");

            assertThatThrownBy(() -> Reservation.create(
                    TestFixtures.USER_ID, TestFixtures.IDEMPOTENCY_KEY, TestFixtures.newItinerary(),
                    List.of(futuro), TestFixtures.NOW))
                    .isInstanceOf(InvalidReservationException.class)
                    .hasMessageContaining("es futura");
        }

        @Test
        @DisplayName("rechaza reservar un itinerario cuyo primer tramo ya salió")
        void rejectsDepartedItinerary() {
            assertThatThrownBy(() -> Reservation.create(
                    TestFixtures.USER_ID, TestFixtures.IDEMPOTENCY_KEY, TestFixtures.departedItinerary(),
                    TestFixtures.newPassengers(), TestFixtures.NOW))
                    .isInstanceOf(ItineraryAlreadyDepartedException.class)
                    .hasMessageContaining("ya salió");
        }

        @Test
        @DisplayName("exige usuario, clave de idempotencia, itinerario, pasajeros e instante")
        void rejectsNulls() {
            assertThatNullPointerException().isThrownBy(() -> Reservation.create(
                    null, TestFixtures.IDEMPOTENCY_KEY, TestFixtures.newItinerary(),
                    TestFixtures.newPassengers(), TestFixtures.NOW));
            assertThatNullPointerException().isThrownBy(() -> Reservation.create(
                    TestFixtures.USER_ID, null, TestFixtures.newItinerary(),
                    TestFixtures.newPassengers(), TestFixtures.NOW));
            assertThatNullPointerException().isThrownBy(() -> Reservation.create(
                    TestFixtures.USER_ID, TestFixtures.IDEMPOTENCY_KEY, null,
                    TestFixtures.newPassengers(), TestFixtures.NOW));
            assertThatNullPointerException().isThrownBy(() -> Reservation.create(
                    TestFixtures.USER_ID, TestFixtures.IDEMPOTENCY_KEY, TestFixtures.newItinerary(),
                    null, TestFixtures.NOW));
            assertThatNullPointerException().isThrownBy(() -> Reservation.create(
                    TestFixtures.USER_ID, TestFixtures.IDEMPOTENCY_KEY, TestFixtures.newItinerary(),
                    TestFixtures.newPassengers(), null));
        }
    }

    @Nested
    @DisplayName("confirmación")
    class Confirmation {

        @Test
        @DisplayName("pasa de PENDING a CONFIRMED y actualiza updatedAt")
        void confirmsPendingReservation() {
            Reservation pendiente = TestFixtures.storedReservation(2L);
            Instant later = TestFixtures.NOW.plus(Duration.ofHours(1));

            Reservation confirmada = pendiente.confirm(later);

            assertThat(confirmada.status()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(confirmada.updatedAt()).isEqualTo(later);
            assertThat(confirmada.createdAt()).isEqualTo(pendiente.createdAt());
            // La versión la incrementa la persistencia, no el dominio.
            assertThat(confirmada.version()).isEqualTo(2L);
            assertThat(pendiente.status()).isEqualTo(ReservationStatus.PENDING);
        }

        @Test
        @DisplayName("rechaza confirmar una reserva que no está pendiente")
        void rejectsNonPendingReservation() {
            assertThatThrownBy(() ->
                    TestFixtures.storedReservation(0L, ReservationStatus.CONFIRMED).confirm(TestFixtures.NOW))
                    .isInstanceOf(ReservationNotModifiableException.class)
                    .hasMessageContaining("CONFIRMED");

            assertThatThrownBy(() ->
                    TestFixtures.storedReservation(0L, ReservationStatus.CANCELLED).confirm(TestFixtures.NOW))
                    .isInstanceOf(ReservationNotModifiableException.class)
                    .hasMessageContaining("CANCELLED");
        }

        @Test
        @DisplayName("rechaza confirmar cuando el itinerario ya arrancó")
        void rejectsAfterDeparture() {
            assertThatThrownBy(() -> TestFixtures.storedReservation(0L).confirm(TestFixtures.DEPARTURE))
                    .isInstanceOf(ItineraryAlreadyDepartedException.class)
                    .hasMessageContaining("confirmar");
        }
    }

    @Nested
    @DisplayName("modificación")
    class Modification {

        private final Itinerary nuevoItinerario = TestFixtures.connectingItinerary();

        @Test
        @DisplayName("cambia el itinerario, conserva estado, createdAt y versión")
        void changesItinerary() {
            Reservation original = TestFixtures.storedReservation(4L, ReservationStatus.CONFIRMED);
            Instant later = TestFixtures.NOW.plus(Duration.ofDays(1));

            Reservation modificada = original.changeItinerary(nuevoItinerario, later);

            assertThat(modificada.itinerary()).isEqualTo(nuevoItinerario);
            assertThat(modificada.itinerary().destination()).isEqualTo(TestFixtures.MAD);
            assertThat(modificada.updatedAt()).isEqualTo(later);
            assertThat(modificada.createdAt()).isEqualTo(original.createdAt());
            assertThat(modificada.status()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(modificada.version()).isEqualTo(4L);
        }

        @Test
        @DisplayName("se puede modificar una reserva pendiente")
        void allowsModifyingPendingReservation() {
            assertThat(TestFixtures.storedReservation(0L).changeItinerary(nuevoItinerario, TestFixtures.NOW).status())
                    .isEqualTo(ReservationStatus.PENDING);
        }

        @Test
        @DisplayName("no modifica la instancia original")
        void doesNotMutateOriginal() {
            Reservation original = TestFixtures.storedReservation(0L);

            original.changeItinerary(nuevoItinerario, TestFixtures.NOW);

            assertThat(original.itinerary().destination()).isEqualTo(TestFixtures.SCL);
        }

        @Test
        @DisplayName("rechaza modificar una reserva cancelada")
        void rejectsCancelledReservation() {
            assertThatThrownBy(() -> TestFixtures.storedReservation(0L, ReservationStatus.CANCELLED)
                    .changeItinerary(nuevoItinerario, TestFixtures.NOW))
                    .isInstanceOf(ReservationNotModifiableException.class)
                    .hasMessageContaining("CANCELLED");
        }

        @Test
        @DisplayName("rechaza modificar una reserva cuyo itinerario ya arrancó")
        void rejectsAlreadyDepartedReservation() {
            Instant afterDeparture = TestFixtures.DEPARTURE.plus(Duration.ofHours(1));

            assertThatThrownBy(() ->
                    TestFixtures.storedReservation(0L).changeItinerary(nuevoItinerario, afterDeparture))
                    .isInstanceOf(ItineraryAlreadyDepartedException.class)
                    .hasMessageContaining("modificar");
        }

        @Test
        @DisplayName("rechaza cambiar a un itinerario que ya salió")
        void rejectsDepartedNewItinerary() {
            assertThatThrownBy(() -> TestFixtures.storedReservation(0L)
                    .changeItinerary(TestFixtures.departedItinerary(), TestFixtures.NOW))
                    .isInstanceOf(ItineraryAlreadyDepartedException.class)
                    .hasMessageContaining("ya salió");
        }
    }

    @Nested
    @DisplayName("cancelación")
    class Cancellation {

        @Test
        @DisplayName("pasa a CANCELLED conservando el resto del estado")
        void cancels() {
            Reservation original = TestFixtures.storedReservation(2L, ReservationStatus.CONFIRMED);
            Instant later = TestFixtures.NOW.plus(Duration.ofHours(5));

            Reservation cancelada = original.cancel(later);

            assertThat(cancelada.status()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(cancelada.status().isActive()).isFalse();
            assertThat(cancelada.updatedAt()).isEqualTo(later);
            assertThat(cancelada.itinerary()).isEqualTo(original.itinerary());
            assertThat(cancelada.passengers()).isEqualTo(original.passengers());
            assertThat(cancelada.version()).isEqualTo(2L);
            assertThat(original.status()).isEqualTo(ReservationStatus.CONFIRMED);
        }

        @Test
        @DisplayName("se puede cancelar una reserva pendiente")
        void cancelsPendingReservation() {
            assertThat(TestFixtures.storedReservation(0L).cancel(TestFixtures.NOW).status())
                    .isEqualTo(ReservationStatus.CANCELLED);
        }

        @Test
        @DisplayName("rechaza cancelar dos veces")
        void rejectsDoubleCancellation() {
            assertThatThrownBy(() ->
                    TestFixtures.storedReservation(0L, ReservationStatus.CANCELLED).cancel(TestFixtures.NOW))
                    .isInstanceOf(ReservationAlreadyCancelledException.class)
                    .hasMessageContaining("10");
        }

        @Test
        @DisplayName("rechaza cancelar una reserva cuyo itinerario ya arrancó")
        void rejectsAfterDeparture() {
            assertThatThrownBy(() -> TestFixtures.storedReservation(0L).cancel(TestFixtures.DEPARTURE))
                    .isInstanceOf(ItineraryAlreadyDepartedException.class)
                    .hasMessageContaining("cancelar");
        }
    }

    @Nested
    @DisplayName("identidad, reconstrucción y versionado")
    class IdentityAndVersioning {

        @Test
        @DisplayName("requireId falla mientras la reserva no esté persistida")
        void requireIdFailsBeforePersisting() {
            Reservation nueva = TestFixtures.newReservation();

            assertThatThrownBy(nueva::requireId)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("todavía no tiene id");
        }

        @Test
        @DisplayName("withId asigna el id que devolvió la base")
        void withIdAssignsGeneratedId() {
            Reservation guardada = TestFixtures.newReservation().withId(ReservationId.of(42L));

            assertThat(guardada.id()).contains(ReservationId.of(42L));
            assertThat(guardada.requireId().value()).isEqualTo(42L);
        }

        @Test
        @DisplayName("rehydrate exige id y al menos un pasajero")
        void rehydrateRequiresIdAndPassengers() {
            assertThatNullPointerException().isThrownBy(() -> Reservation.rehydrate(
                    null, TestFixtures.USER_ID, TestFixtures.IDEMPOTENCY_KEY, TestFixtures.newItinerary(),
                    TestFixtures.newPassengers(), ReservationStatus.PENDING,
                    TestFixtures.NOW, TestFixtures.NOW, 0L));

            assertThatThrownBy(() -> Reservation.rehydrate(
                    TestFixtures.RESERVATION_ID, TestFixtures.USER_ID, TestFixtures.IDEMPOTENCY_KEY,
                    TestFixtures.newItinerary(), List.of(), ReservationStatus.PENDING,
                    TestFixtures.NOW, TestFixtures.NOW, 0L))
                    .isInstanceOf(InvalidReservationException.class)
                    .hasMessageContaining("no tiene pasajeros");
        }

        @Test
        @DisplayName("rehydrate no aplica las reglas de creación, para poder leer reservas históricas")
        void rehydrateSkipsCreationRules() {
            Reservation historica = Reservation.rehydrate(
                    TestFixtures.RESERVATION_ID, TestFixtures.USER_ID, TestFixtures.IDEMPOTENCY_KEY,
                    TestFixtures.departedItinerary(), TestFixtures.newPassengers(),
                    ReservationStatus.CONFIRMED, TestFixtures.NOW, TestFixtures.NOW, 3L);

            assertThat(historica.itinerary().hasDeparted(TestFixtures.NOW)).isTrue();
            assertThat(historica.version()).isEqualTo(3L);
        }

        @Test
        @DisplayName("withVersion refleja la versión almacenada")
        void withVersionKeepsRestOfState() {
            Reservation versionada = TestFixtures.storedReservation(1L).withVersion(3L);

            assertThat(versionada.version()).isEqualTo(3L);
            assertThat(versionada.status()).isEqualTo(ReservationStatus.PENDING);
        }

        @Test
        @DisplayName("rechaza versiones negativas")
        void rejectsNegativeVersion() {
            assertThatThrownBy(() -> TestFixtures.storedReservation(0L).withVersion(-1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("withPassengers reemplaza los pasajeros por los ya persistidos")
        void withPassengersReplacesResolvedPassengers() {
            Reservation nueva = TestFixtures.newReservation();

            Reservation resuelta = nueva.withPassengers(List.of(TestFixtures.existingPassenger(7L)));

            assertThat(resuelta.passengers()).singleElement()
                    .satisfies(passenger -> assertThat(passenger.id()).contains(PassengerId.of(7L)));
        }

        @Test
        @DisplayName("withPassengers exige la misma cantidad de pasajeros")
        void withPassengersValidatesSize() {
            Reservation nueva = TestFixtures.newReservation();

            assertThatThrownBy(() -> nueva.withPassengers(List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mismo tamaño");
        }

        @Test
        @DisplayName("withItinerary reemplaza el itinerario por el ya persistido")
        void withItineraryReplacesPersistedItinerary() {
            Reservation resuelta = TestFixtures.newReservation().withItinerary(TestFixtures.existingItinerary(9L));

            assertThat(resuelta.itinerary().id()).contains(ItineraryId.of(9L));
        }

        @Test
        @DisplayName("la identidad es la clave de idempotencia, que existe desde antes de persistir")
        void identityIsTheIdempotencyKey() {
            Reservation nueva = TestFixtures.newReservation();
            Reservation guardada = nueva.withId(ReservationId.of(42L));
            Reservation cancelada = guardada.cancel(TestFixtures.NOW);

            assertThat(nueva).isEqualTo(guardada).isEqualTo(cancelada).hasSameHashCodeAs(guardada);
            assertThat(nueva).isNotEqualTo(Reservation.create(
                    TestFixtures.USER_ID, IdempotencyKey.newKey(), TestFixtures.newItinerary(),
                    TestFixtures.newPassengers(), TestFixtures.NOW));
        }

        @Test
        @DisplayName("la lista de pasajeros es inmutable desde afuera")
        void passengersAreImmutable() {
            Reservation reservation = TestFixtures.newReservation();

            assertThatThrownBy(() -> reservation.passengers().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
