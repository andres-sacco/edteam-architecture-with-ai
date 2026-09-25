package com.edteam.reservations.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.edteam.reservations.application.port.in.CreateReservationResult;
import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.application.port.out.AuditTrailPort;
import com.edteam.reservations.application.port.out.EventOutboxPort;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.application.port.out.UserRepositoryPort;
import com.edteam.reservations.support.TestFixtures;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Ninguna escritura se repite sin una clave que la proteja, y la clave protege
 * también al proveedor.
 *
 * <p>La {@code Idempotency-Key} ya impedía la reserva doble. Lo que no
 * impedía era la <strong>amplificación</strong>: un cliente que corta a los
 * treinta segundos y reintenta disparaba otra tanda entera de consultas contra
 * un catálogo ya degradado, mientras el pedido anterior seguía corriendo. La
 * clave protegía la base, no al tercero — y con un catálogo lento eso es
 * justamente lo que convierte su degradación en su caída.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("La clave de idempotencia protege la escritura y también la carga")
class IdempotentWriteProtectionTest {

    @Mock
    private ReservationRepositoryPort reservationRepository;

    @Mock
    private UserRepositoryPort userRepository;

    @Mock
    private AirportCatalogPort airportCatalog;

    @Mock
    private EventOutboxPort eventOutbox;

    @Mock
    private AuditTrailPort auditTrail;

    private CreateReservationService service;

    @BeforeEach
    void setUp() {
        ItineraryAssembler assembler = new ItineraryAssembler();
        service = new CreateReservationService(
                assembler,
                new AirportExistenceValidator(airportCatalog),
                new ReservationIdempotencyLookup(userRepository, reservationRepository),
                new CreateReservationTransaction(
                        reservationRepository, userRepository, assembler, eventOutbox, auditTrail),
                TestFixtures.fixedClock());
        lenient().when(airportCatalog.unknown(anyCollection())).thenReturn(Set.of());
    }

    @Test
    @DisplayName("un reintento con la misma clave NO vuelve a consultar el catálogo")
    void aRetryWithTheSameKeyDoesNotHitTheCatalogAgain() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.of(TestFixtures.storedUser()));
        when(reservationRepository.findByIdempotencyKey(TestFixtures.USER_ID, TestFixtures.IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(TestFixtures.storedReservation(1L)));

        CreateReservationResult result = service.create(TestFixtures.createCommand());

        assertThat(result.created()).isFalse();
        verifyNoInteractions(airportCatalog);
        verify(reservationRepository, never()).save(any());
        verifyNoInteractions(eventOutbox);
    }

    @Test
    @DisplayName("la clave se resuelve ANTES de validar: si ya existe, no hay nada que validar")
    void theKeyIsResolvedBeforeValidating() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.of(TestFixtures.storedUser()));
        when(reservationRepository.findByIdempotencyKey(TestFixtures.USER_ID, TestFixtures.IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(TestFixtures.storedReservation(1L)));

        service.create(TestFixtures.createCommand());

        // El orden ES la mitigación: convierte el reintento del usuario en una
        // consulta por índice en lugar de once llamadas HTTP.
        verify(reservationRepository).findByIdempotencyKey(TestFixtures.USER_ID, TestFixtures.IDEMPOTENCY_KEY);
        verifyNoInteractions(airportCatalog);
    }

    @Test
    @DisplayName("un pedido nuevo sí valida: el atajo no se come el camino normal")
    void aFreshRequestStillValidates() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.of(TestFixtures.storedUser()));
        when(reservationRepository.findByIdempotencyKey(TestFixtures.USER_ID, TestFixtures.IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(userRepository.findOrRegister(any())).thenReturn(TestFixtures.storedUser());
        when(reservationRepository.save(any()))
                .thenAnswer(invocation -> invocation
                        .<com.edteam.reservations.domain.model.Reservation>getArgument(0)
                        .withId(TestFixtures.RESERVATION_ID));

        assertThat(service.create(TestFixtures.createCommand()).created()).isTrue();

        verify(airportCatalog).unknown(anyCollection());
    }

    @Test
    @DisplayName("la transacción vuelve a chequear la clave: entre la lectura y la escritura hay una carrera")
    void theTransactionChecksTheKeyAgain() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.of(TestFixtures.storedUser()));
        when(reservationRepository.findByIdempotencyKey(TestFixtures.USER_ID, TestFixtures.IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty(), Optional.of(TestFixtures.storedReservation(1L)));

        CreateReservationResult result = service.create(TestFixtures.createCommand());

        // El pre-chequeo es una optimización, no la garantía: la garantía
        // sigue siendo la del UNIQUE de la base resuelto dentro de la
        // transacción. Si sólo estuviera el de afuera, dos pedidos simultáneos
        // con la misma clave crearían dos reservas.
        assertThat(result.created()).isFalse();
        verify(reservationRepository, never()).save(any());
    }
}
