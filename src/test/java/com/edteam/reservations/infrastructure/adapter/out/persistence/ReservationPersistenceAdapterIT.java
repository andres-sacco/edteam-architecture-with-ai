package com.edteam.reservations.infrastructure.adapter.out.persistence;

import com.edteam.reservations.application.exception.ConcurrentUpdateException;
import com.edteam.reservations.application.exception.DuplicateReservationException;
import com.edteam.reservations.application.exception.UnknownUserException;
import com.edteam.reservations.domain.model.Email;
import com.edteam.reservations.domain.model.IdempotencyKey;
import com.edteam.reservations.domain.model.Itinerary;
import com.edteam.reservations.domain.model.Passenger;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationId;
import com.edteam.reservations.domain.model.ReservationStatus;
import com.edteam.reservations.domain.model.User;
import com.edteam.reservations.domain.model.UserId;
import com.edteam.reservations.support.AbstractPostgresIT;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReservationPersistenceAdapter (PostgreSQL)")
class ReservationPersistenceAdapterIT extends AbstractPostgresIT {

    @Autowired
    private ReservationPersistenceAdapter adapter;

    private User user;

    @BeforeEach
    void createUser() {
        user = User.of(UserId.of(insertUser(TestFixtures.USER_EMAIL)),
                Email.of(TestFixtures.USER_EMAIL), "Ana", "Pérez", TestFixtures.NOW);
    }

    private Reservation newReservation(IdempotencyKey key, Itinerary itinerary, List<Passenger> passengers) {
        return Reservation.create(user, key, itinerary, passengers, TestFixtures.NOW);
    }

    private Reservation newReservation(IdempotencyKey key) {
        return newReservation(key, TestFixtures.newItinerary(), TestFixtures.newPassengers());
    }

    @Test
    @DisplayName("inserta la reserva y le asigna id y versión 0")
    void insertsReservation() {
        Reservation saved = inTransaction(() -> adapter.save(newReservation(IdempotencyKey.newKey())));

        assertThat(saved.id()).isPresent();
        assertThat(saved.version()).isZero();
        assertThat(saved.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(countRows("reserva")).isEqualTo(1L);
        assertThat(countRows("itinerario")).isEqualTo(1L);
        assertThat(countRows("segmento")).isEqualTo(1L);
        assertThat(countRows("pasajero")).isEqualTo(1L);
        assertThat(countRows("reserva_pasajero")).isEqualTo(1L);
    }

    @Test
    @DisplayName("la lectura reconstruye el agregado completo, con las fechas intactas")
    void readsBackTheWholeAggregate() {
        IdempotencyKey key = IdempotencyKey.newKey();
        Reservation saved = inTransaction(() -> adapter.save(newReservation(key)));

        Optional<Reservation> found = inTransaction(() -> adapter.findById(saved.requireId()));

        assertThat(found).isPresent();
        Reservation reservation = found.get();
        assertThat(reservation.userId()).isEqualTo(user.requireId());
        assertThat(reservation.user().email()).isEqualTo(user.email());
        assertThat(reservation.idempotencyKey()).isEqualTo(key);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(reservation.passengers()).singleElement()
                .satisfies(passenger -> {
                    assertThat(passenger.documentNumber()).contains("30123456");
                    assertThat(passenger.birthDate()).isEqualTo(LocalDate.of(1990, 5, 20));
                });
        assertThat(reservation.itinerary().price()).isEqualTo(TestFixtures.price());
        // Las columnas son TIMESTAMP sin zona: si la conversión a UTC no fuera
        // consistente, el Instant volvería corrido.
        assertThat(reservation.itinerary().firstDeparture()).isEqualTo(TestFixtures.DEPARTURE);
        assertThat(reservation.createdAt()).isEqualTo(TestFixtures.NOW);
    }

    @Test
    @DisplayName("conserva el orden de los tramos en itinerario_segmento.orden")
    void preservesSegmentOrder() {
        Reservation saved = inTransaction(() -> adapter.save(newReservation(
                IdempotencyKey.newKey(), TestFixtures.connectingItinerary(), TestFixtures.newPassengers())));

        Reservation found = inTransaction(() -> adapter.findById(saved.requireId())).orElseThrow();

        assertThat(found.itinerary().segments()).hasSize(2);
        assertThat(found.itinerary().origin()).isEqualTo(TestFixtures.EZE);
        assertThat(found.itinerary().destination()).isEqualTo(TestFixtures.MAD);
        assertThat(jdbcTemplate.queryForList(
                "SELECT orden FROM itinerario_segmento ORDER BY orden", Integer.class))
                .containsExactly(0, 1);
    }

    @Test
    @DisplayName("guarda las fechas en UTC, sin importar la zona del servidor")
    void storesTimestampsInUtc() {
        inTransaction(() -> adapter.save(newReservation(IdempotencyKey.newKey())));

        // La columna es TIMESTAMP sin zona: lo que se guarda tiene que ser el
        // reloj UTC del Instant. Si el itinerario se insertara por un camino
        // (Hibernate) y el segmento por otro (consulta nativa) con conversiones
        // distintas, la clave natural del segmento no volvería a coincidir y se
        // duplicarían filas.
        assertThat(jdbcTemplate.queryForObject("SELECT fecha_vuelo::text FROM segmento", String.class))
                .isEqualTo("2026-10-21 12:00:00");
        assertThat(jdbcTemplate.queryForObject("SELECT fecha_creacion::text FROM reserva", String.class))
                .isEqualTo("2026-10-01 12:00:00");
    }

    @Test
    @DisplayName("busca por clave de idempotencia")
    void findsByIdempotencyKey() {
        IdempotencyKey key = IdempotencyKey.newKey();
        Reservation saved = inTransaction(() -> adapter.save(newReservation(key)));

        assertThat(inTransaction(() -> adapter.findByIdempotencyKey(key)))
                .get()
                .satisfies(found -> assertThat(found.requireId()).isEqualTo(saved.requireId()));
        assertThat(inTransaction(() -> adapter.findByIdempotencyKey(IdempotencyKey.newKey()))).isEmpty();
    }

    @Test
    @DisplayName("devuelve vacío si el id no existe")
    void returnsEmptyForUnknownId() {
        assertThat(inTransaction(() -> adapter.findById(ReservationId.of(999_999L)))).isEmpty();
    }

    @Test
    @DisplayName("reutiliza el segmento cuando dos reservas comparten el mismo vuelo")
    void reusesSharedSegments() {
        inTransaction(() -> adapter.save(newReservation(IdempotencyKey.newKey())));
        inTransaction(() -> adapter.save(newReservation(
                IdempotencyKey.newKey(), TestFixtures.newItinerary(),
                List.of(TestFixtures.newPassenger("Juan", "30999888")))));

        assertThat(countRows("reserva")).isEqualTo(2L);
        // Dos itinerarios distintos (no tienen clave natural)...
        assertThat(countRows("itinerario")).isEqualTo(2L);
        // ...pero un único segmento compartido.
        assertThat(countRows("segmento")).isEqualTo(1L);
        assertThat(countRows("itinerario_segmento")).isEqualTo(2L);
    }

    @Test
    @DisplayName("reutiliza el pasajero cuando vuelve a viajar, por su documento")
    void reusesPassengerByDocument() {
        inTransaction(() -> adapter.save(newReservation(IdempotencyKey.newKey())));
        Reservation segunda = inTransaction(() -> adapter.save(newReservation(
                IdempotencyKey.newKey(), TestFixtures.connectingItinerary(), TestFixtures.newPassengers())));

        assertThat(countRows("pasajero")).isEqualTo(1L);
        assertThat(countRows("reserva_pasajero")).isEqualTo(2L);
        assertThat(segunda.passengers()).singleElement()
                .satisfies(passenger -> assertThat(passenger.id()).isPresent());
    }

    @Test
    @DisplayName("un pasajero sin documento no se puede deduplicar: se inserta cada vez")
    void insertsPassengerWithoutDocumentEveryTime() {
        Passenger sinDocumento = Passenger.newPassenger("Ana", "Pérez", LocalDate.of(1990, 5, 20), null);

        inTransaction(() -> adapter.save(newReservation(
                IdempotencyKey.newKey(), TestFixtures.newItinerary(), List.of(sinDocumento))));
        inTransaction(() -> adapter.save(newReservation(
                IdempotencyKey.newKey(), TestFixtures.connectingItinerary(), List.of(sinDocumento))));

        assertThat(countRows("pasajero")).isEqualTo(2L);
    }

    @Test
    @DisplayName("guarda una reserva con varios pasajeros")
    void savesReservationWithSeveralPassengers() {
        Reservation saved = inTransaction(() -> adapter.save(newReservation(
                IdempotencyKey.newKey(), TestFixtures.newItinerary(),
                List.of(TestFixtures.newPassenger("Ana", "30123456"),
                        TestFixtures.newPassenger("Juan", "30999888")))));

        Reservation found = inTransaction(() -> adapter.findById(saved.requireId())).orElseThrow();

        assertThat(found.passengers()).hasSize(2);
        assertThat(countRows("reserva_pasajero")).isEqualTo(2L);
    }

    @Test
    @DisplayName("el UNIQUE de idempotency_key impide la reserva duplicada")
    void rejectsDuplicateIdempotencyKey() {
        IdempotencyKey key = IdempotencyKey.newKey();
        inTransaction(() -> adapter.save(newReservation(key)));

        assertThatThrownBy(() -> inTransaction(() -> adapter.save(newReservation(key))))
                .isInstanceOf(DuplicateReservationException.class)
                .hasMessageContaining(key.toString());

        assertThat(countRows("reserva")).isEqualTo(1L);
    }

    @Test
    @DisplayName("la clave foránea del usuario se traduce a UnknownUserException")
    void translatesUnknownUser() {
        // Un usuario con id asignado pero cuya fila no existe: es lo que pasaría
        // si lo borraran entre el alta y la escritura de la reserva.
        Reservation huerfana = Reservation.create(
                User.of(UserId.of(999_999L), Email.of("fantasma@example.com"), "Fantasma", "Sin Fila",
                        TestFixtures.NOW),
                IdempotencyKey.newKey(), TestFixtures.newItinerary(),
                TestFixtures.newPassengers(), TestFixtures.NOW);

        assertThatThrownBy(() -> inTransaction(() -> adapter.save(huerfana)))
                .isInstanceOf(UnknownUserException.class)
                .hasMessageContaining("999999");

        assertThat(countRows("reserva")).isZero();
    }

    @Test
    @DisplayName("la actualización incrementa la versión y persiste el nuevo estado")
    void updateIncrementsVersion() {
        Reservation saved = inTransaction(() -> adapter.save(newReservation(IdempotencyKey.newKey())));

        Reservation confirmada = inTransaction(() -> adapter.save(saved.confirm(TestFixtures.NOW)));

        assertThat(confirmada.version()).isEqualTo(1L);
        assertThat(confirmada.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(jdbcTemplate.queryForObject("SELECT estado FROM reserva", String.class)).isEqualTo("CONFIRMADA");
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM reserva", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("al cambiar de itinerario crea uno nuevo y reutiliza los segmentos que ya estaban")
    void updateWithNewItineraryReusesSegments() {
        Reservation saved = inTransaction(() -> adapter.save(newReservation(IdempotencyKey.newKey())));

        Reservation modificada = inTransaction(() ->
                adapter.save(saved.changeItinerary(TestFixtures.connectingItinerary(), TestFixtures.NOW)));

        assertThat(modificada.itinerary().destination()).isEqualTo(TestFixtures.MAD);
        assertThat(countRows("itinerario")).isEqualTo(2L);
        // EZE-SCL ya existía; sólo se agrega SCL-MAD.
        assertThat(countRows("segmento")).isEqualTo(2L);

        Reservation found = inTransaction(() -> adapter.findById(saved.requireId())).orElseThrow();
        assertThat(found.itinerary().segments()).hasSize(2);
    }

    @Test
    @DisplayName("cancelar es una baja lógica: la fila sigue con sus pasajeros")
    void cancellationKeepsTheRow() {
        Reservation saved = inTransaction(() -> adapter.save(newReservation(IdempotencyKey.newKey())));

        inTransaction(() -> adapter.save(saved.cancel(TestFixtures.NOW)));

        assertThat(countRows("reserva")).isEqualTo(1L);
        assertThat(countRows("reserva_pasajero")).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT estado FROM reserva", String.class)).isEqualTo("CANCELADA");
    }

    @Test
    @DisplayName("rechaza guardar sobre una versión desactualizada")
    void rejectsStaleVersion() {
        Reservation saved = inTransaction(() -> adapter.save(newReservation(IdempotencyKey.newKey())));
        inTransaction(() -> adapter.save(saved.confirm(TestFixtures.NOW)));

        // 'saved' sigue en versión 0: es la lectura vieja de otro proceso.
        assertThatThrownBy(() -> inTransaction(() -> adapter.save(saved.cancel(TestFixtures.NOW))))
                .isInstanceOf(ConcurrentUpdateException.class)
                .hasMessageContaining("versión esperada 0, actual 1");

        assertThat(jdbcTemplate.queryForObject("SELECT estado FROM reserva", String.class)).isEqualTo("CONFIRMADA");
    }

    @Test
    @Timeout(60)
    @DisplayName("con escrituras concurrentes sobre la misma reserva, sólo una gana")
    void allowsOnlyOneConcurrentWriter() throws Exception {
        int writers = 4;
        Reservation saved = inTransaction(() -> adapter.save(newReservation(IdempotencyKey.newKey())));
        CyclicBarrier startTogether = new CyclicBarrier(writers);

        List<Callable<Boolean>> attempts = IntStream.range(0, writers)
                .<Callable<Boolean>>mapToObj(index -> () -> {
                    startTogether.await();
                    try {
                        // Todos parten de la versión 0: es el escenario de dos
                        // usuarios modificando la misma reserva a la vez.
                        inTransaction(() -> adapter.save(saved.confirm(TestFixtures.NOW)));
                        return true;
                    } catch (ConcurrentUpdateException e) {
                        return false;
                    }
                })
                .toList();

        long winners;
        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            List<Future<Boolean>> results = pool.invokeAll(attempts);
            winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    winners++;
                }
            }
        }

        assertThat(winners).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM reserva", Integer.class)).isEqualTo(1);
    }

    @Test
    @Timeout(60)
    @DisplayName("dos reservas simultáneas del mismo vuelo no duplican el segmento")
    void concurrentReservationsShareTheSameSegmentRow() throws Exception {
        int writers = 4;
        CyclicBarrier startTogether = new CyclicBarrier(writers);

        List<Callable<Reservation>> attempts = IntStream.range(0, writers)
                .<Callable<Reservation>>mapToObj(index -> () -> {
                    startTogether.await();
                    return inTransaction(() -> adapter.save(newReservation(
                            IdempotencyKey.newKey(), TestFixtures.newItinerary(),
                            List.of(TestFixtures.newPassenger("Pasajero" + index, "DOC" + index)))));
                })
                .toList();

        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            for (Future<Reservation> result : pool.invokeAll(attempts)) {
                // Ninguna de las reservas debe fallar por la carrera sobre el segmento.
                assertThat(result.get().id()).isPresent();
            }
        }

        assertThat(countRows("reserva")).isEqualTo(writers);
        assertThat(countRows("segmento")).isEqualTo(1L);
    }
}
