package com.edteam.reservations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.edteam.reservations.application.outbox.OutboxDispatchResult;
import com.edteam.reservations.application.port.in.CancelReservationCommand;
import com.edteam.reservations.application.port.in.CancelReservationUseCase;
import com.edteam.reservations.application.port.in.ConfirmReservationCommand;
import com.edteam.reservations.application.port.in.ConfirmReservationUseCase;
import com.edteam.reservations.application.port.in.CreateReservationCommand;
import com.edteam.reservations.application.port.in.CreateReservationResult;
import com.edteam.reservations.application.port.in.CreateReservationUseCase;
import com.edteam.reservations.application.port.in.DispatchPendingNotificationsUseCase;
import com.edteam.reservations.application.port.in.GetReservationQuery;
import com.edteam.reservations.application.port.in.GetReservationUseCase;
import com.edteam.reservations.application.port.in.ModifyReservationCommand;
import com.edteam.reservations.application.port.in.ModifyReservationUseCase;
import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationStatus;
import com.edteam.reservations.infrastructure.adapter.in.scheduling.OutboxDispatchScheduler;
import com.edteam.reservations.support.AbstractPostgresIT;
import com.edteam.reservations.support.TestFixtures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * Verifica que la aplicación levante y que el flujo completo funcione con los
 * adaptadores reales: PostgreSQL con el esquema creado por Flyway.
 *
 * <p>Es el test que responde "¿funciona?": los unitarios prueban cada pieza
 * aislada, y este prueba que el contexto se arme —incluida la validación de que
 * el mapeo JPA coincide con las tablas de la migración— y que crear, consultar,
 * confirmar, modificar y cancelar una reserva encaje de punta a punta.
 */
@DisplayName("Aplicación de reservas (PostgreSQL)")
class ReservationsApplicationIT extends AbstractPostgresIT {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private CreateReservationUseCase createReservation;

    @Autowired
    private GetReservationUseCase getReservation;

    @Autowired
    private ConfirmReservationUseCase confirmReservation;

    @Autowired
    private ModifyReservationUseCase modifyReservation;

    @Autowired
    private CancelReservationUseCase cancelReservation;

    @Autowired
    private DispatchPendingNotificationsUseCase dispatchNotifications;

    @Autowired
    private Clock clock;

    private CreateReservationCommand createCommand(Instant departure) {
        return new CreateReservationCommand(
                TestFixtures.owner(),
                UUID.randomUUID().toString(),
                new com.edteam.reservations.application.port.in.ItineraryData(
                        new java.math.BigDecimal("1250.50"),
                        "USD",
                        List.of(TestFixtures.segmentData(TestFixtures.EZE, TestFixtures.SCL, departure))),
                TestFixtures.passengerData());
    }

    @Test
    @DisplayName("el contexto levanta con un único adaptador por puerto y el mapeo valida contra el esquema")
    void contextLoads() {
        assertThat(context.getBeanNamesForType(CreateReservationUseCase.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ReservationRepositoryPort.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(AirportCatalogPort.class)).hasSize(1);
        // Apagado por la property de los tests de integración.
        assertThat(context.getBeanNamesForType(OutboxDispatchScheduler.class)).isEmpty();
    }

    @Test
    @DisplayName("Flyway dejó el esquema del modelo de datos")
    void flywayCreatedTheSchema() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """, String.class))
                .contains(
                        "usuario",
                        "pasajero",
                        "segmento",
                        "itinerario",
                        "itinerario_segmento",
                        "reserva",
                        "reserva_pasajero",
                        "flyway_schema_history");
    }

    @Test
    @DisplayName("crea, consulta, confirma, modifica y cancela una reserva de punta a punta")
    void runsTheFullReservationFlow() {
        Instant departure = clock.instant().plus(Duration.ofDays(30));

        Reservation created = createReservation.create(createCommand(departure)).reservation();
        assertThat(created.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(created.version()).isZero();
        assertThat(created.id()).isPresent();

        Reservation found = getReservation.get(new GetReservationQuery(created.requireId(), TestFixtures.owner()));
        assertThat(found.requireId()).isEqualTo(created.requireId());
        assertThat(found.itinerary().origin()).isEqualTo(TestFixtures.EZE);
        assertThat(found.passengers()).hasSize(1);

        Reservation confirmed = confirmReservation.confirm(
                new ConfirmReservationCommand(created.requireId().value(), found.version(), TestFixtures.owner()));
        assertThat(confirmed.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(confirmed.version()).isEqualTo(1L);

        Instant newDeparture = departure.plus(Duration.ofDays(2));
        Reservation modified = modifyReservation.modify(new ModifyReservationCommand(
                created.requireId().value(),
                confirmed.version(),
                new com.edteam.reservations.application.port.in.ItineraryData(
                        new java.math.BigDecimal("1980.00"),
                        "USD",
                        List.of(
                                TestFixtures.segmentData(TestFixtures.EZE, TestFixtures.SCL, newDeparture),
                                TestFixtures.segmentData(
                                        TestFixtures.SCL, TestFixtures.MAD, newDeparture.plus(Duration.ofHours(6))))),
                TestFixtures.owner()));
        assertThat(modified.itinerary().destination()).isEqualTo(TestFixtures.MAD);
        assertThat(modified.itinerary().segments()).hasSize(2);
        assertThat(modified.version()).isEqualTo(2L);

        Reservation cancelled = cancelReservation.cancel(
                new CancelReservationCommand(created.requireId().value(), modified.version(), TestFixtures.owner()));
        assertThat(cancelled.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(cancelled.version()).isEqualTo(3L);

        assertThat(getReservation
                        .get(new GetReservationQuery(created.requireId(), TestFixtures.owner()))
                        .status())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(countRows("reserva")).isEqualTo(1L);
    }

    @Test
    @DisplayName("un reintento con la misma clave de idempotencia devuelve la misma reserva")
    void isIdempotentOnRetry() {
        CreateReservationCommand command = createCommand(clock.instant().plus(Duration.ofDays(40)));

        CreateReservationResult primera = createReservation.create(command);
        CreateReservationResult reintento = createReservation.create(command);

        assertThat(primera.created()).isTrue();
        // El reintento no da de alta nada: es lo que el adaptador traduce a 200 en lugar de 201.
        assertThat(reintento.created()).isFalse();
        assertThat(reintento.reservation().requireId())
                .isEqualTo(primera.reservation().requireId());
        assertThat(countRows("reserva")).isEqualTo(1L);
        // Una sola notificación: la del alta, no una por intento.
        assertThat(outboxTypes("PENDING")).containsExactly("reservation.created");
    }

    @Test
    @DisplayName("dos reservas con la misma clave no pueden coexistir")
    void rejectsDuplicateIdempotencyKeyFromAnotherPayload() {
        Instant departure = clock.instant().plus(Duration.ofDays(50));
        CreateReservationCommand command = createCommand(departure);
        createReservation.create(command);

        // Misma clave, otro contenido: sigue siendo el mismo intento para el cliente,
        // así que se devuelve la reserva ya creada en lugar de una nueva.
        CreateReservationCommand mismaClaveOtroVuelo = new CreateReservationCommand(
                TestFixtures.owner(),
                command.idempotencyKey(),
                new com.edteam.reservations.application.port.in.ItineraryData(
                        new java.math.BigDecimal("999.00"),
                        "USD",
                        List.of(TestFixtures.segmentData(TestFixtures.EZE, TestFixtures.GRU, departure))),
                TestFixtures.passengerData());

        assertThat(createReservation
                        .create(mismaClaveOtroVuelo)
                        .reservation()
                        .itinerary()
                        .destination())
                .isEqualTo(TestFixtures.SCL);
        assertThat(countRows("reserva")).isEqualTo(1L);
    }

    @Test
    @DisplayName("da de alta al usuario cuando es su primera reserva")
    void registersTheUserOnTheirFirstReservation() {
        assertThat(countRows("usuario")).isZero();

        Reservation created = createReservation
                .create(createCommand(clock.instant().plus(Duration.ofDays(10))))
                .reservation();

        assertThat(countRows("usuario")).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT email FROM usuario WHERE id = ?",
                        String.class,
                        created.userId().value()))
                .isEqualTo(TestFixtures.USER_EMAIL);
    }

    @Test
    @DisplayName("la segunda reserva del mismo email reutiliza el usuario en vez de duplicarlo")
    void reusesTheUserAcrossReservations() {
        Reservation primera = createReservation
                .create(createCommand(clock.instant().plus(Duration.ofDays(10))))
                .reservation();
        Reservation segunda = createReservation
                .create(createCommand(clock.instant().plus(Duration.ofDays(20))))
                .reservation();

        assertThat(segunda.userId()).isEqualTo(primera.userId());
        assertThat(countRows("usuario")).isEqualTo(1L);
        assertThat(countRows("reserva")).isEqualTo(2L);
    }

    @Test
    @DisplayName("reservar no le pisa el perfil al usuario que ya existe")
    void doesNotOverwriteAnExistingProfile() {
        long existente = insertUser(TestFixtures.USER_EMAIL);

        Reservation created = createReservation
                .create(createCommand(clock.instant().plus(Duration.ofDays(10))))
                .reservation();

        assertThat(created.userId().value()).isEqualTo(existente);
        assertThat(countRows("usuario")).isEqualTo(1L);
    }

    @Test
    @DisplayName("las operaciones dejan su notificación en el outbox y el despachador las envía")
    void notifiesEveryRelevantOperation() {
        Instant departure = clock.instant().plus(Duration.ofDays(45));

        Reservation created = createReservation.create(createCommand(departure)).reservation();
        Reservation confirmed = confirmReservation.confirm(
                new ConfirmReservationCommand(created.requireId().value(), created.version(), TestFixtures.owner()));
        cancelReservation.cancel(
                new CancelReservationCommand(created.requireId().value(), confirmed.version(), TestFixtures.owner()));

        // Los tres hechos están en la TABLA, no en el heap: sobreviven a un
        // reinicio del proceso y son los mismos para todas las instancias.
        assertThat(outboxTypes("PENDING"))
                .containsExactly("reservation.created", "reservation.confirmed", "reservation.cancelled");

        OutboxDispatchResult result = dispatchNotifications.dispatchPending(50);

        assertThat(result.dispatched()).isGreaterThanOrEqualTo(3);
        assertThat(result.failed()).isZero();
        assertThat(countOutbox("PENDING")).isZero();
        assertThat(countOutbox("DISPATCHED")).isEqualTo(3L);
    }

    @Test
    @DisplayName("si la reserva no existe, la consulta falla con el error de negocio")
    void failsForUnknownReservation() {
        assertThatThrownBy(() -> getReservation.get(new GetReservationQuery(
                        com.edteam.reservations.domain.model.ReservationId.of(999_999L), TestFixtures.owner())))
                .isInstanceOf(com.edteam.reservations.application.exception.ReservationNotFoundException.class);
    }

    @Test
    @DisplayName("una modificación con versión vieja se rechaza con 409 de negocio")
    void rejectsStaleVersion() {
        Reservation created = createReservation
                .create(createCommand(clock.instant().plus(Duration.ofDays(60))))
                .reservation();
        confirmReservation.confirm(
                new ConfirmReservationCommand(created.requireId().value(), 0L, TestFixtures.owner()));

        assertThatThrownBy(() -> cancelReservation.cancel(
                        new CancelReservationCommand(created.requireId().value(), 0L, TestFixtures.owner())))
                .isInstanceOf(com.edteam.reservations.application.exception.ConcurrentUpdateException.class);
    }
}
