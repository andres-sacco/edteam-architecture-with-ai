package com.edteam.reservations.application.service;

import com.edteam.reservations.application.audit.AuditAction;
import com.edteam.reservations.application.audit.AuditEntry;
import com.edteam.reservations.application.audit.AuditOutcome;
import com.edteam.reservations.application.exception.ReservationNotFoundException;
import com.edteam.reservations.application.port.in.GetReservationQuery;
import com.edteam.reservations.application.port.out.AuditTrailPort;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetReservationService")
class GetReservationServiceTest {

    @Mock
    private ReservationRepositoryPort reservationRepository;

    @Mock
    private AuditTrailPort auditTrail;

    private GetReservationService service;

    @BeforeEach
    void setUp() {
        service = new GetReservationService(reservationRepository, auditTrail, TestFixtures.fixedClock());
    }

    private Reservation get(com.edteam.reservations.domain.access.Actor actor) {
        return service.get(new GetReservationQuery(TestFixtures.RESERVATION_ID, actor));
    }

    @Test
    @DisplayName("el titular ve su reserva")
    void returnsReservationToItsOwner() {
        Reservation stored = TestFixtures.storedReservation(3L);
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID)).thenReturn(Optional.of(stored));

        assertThat(get(TestFixtures.owner())).isSameAs(stored);
        verifyNoInteractions(auditTrail);
    }

    @Test
    @DisplayName("falla con ReservationNotFoundException si no existe")
    void failsWhenNotFound() {
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> get(TestFixtures.owner()))
                .isInstanceOf(ReservationNotFoundException.class)
                .hasMessageContaining("10");
    }

    @Test
    @DisplayName("una reserva ajena falla exactamente igual que una inexistente")
    void aForeignReservationIsIndistinguishableFromAMissingOne() {
        // El corazón de la mitigación de T-02. Si acá saliera una excepción
        // distinta —y por lo tanto un 403 en lugar de un 404—, recorrer los
        // identificadores diría cuántas reservas hay y cuáles están ocupadas,
        // que es la mitad de la enumeración que se quiso cerrar.
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID))
                .thenReturn(Optional.of(TestFixtures.storedReservation(3L)));

        assertThatThrownBy(() -> get(TestFixtures.stranger()))
                .isInstanceOf(ReservationNotFoundException.class)
                .hasMessageContaining("10");
    }

    @Test
    @DisplayName("el intento sobre una reserva ajena queda auditado aunque la respuesta sea un 404")
    void auditsTheDeniedAttempt() {
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID))
                .thenReturn(Optional.of(TestFixtures.storedReservation(3L)));

        assertThatThrownBy(() -> get(TestFixtures.stranger()))
                .isInstanceOf(ReservationNotFoundException.class);

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.captor();
        verify(auditTrail).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(AuditAction.RESERVATION_ACCESS_DENIED);
        assertThat(entry.getValue().outcome()).isEqualTo(AuditOutcome.DENIED);
        assertThat(entry.getValue().actor().value()).isEqualTo(TestFixtures.OTHER_USER_EMAIL);
        assertThat(entry.getValue().reservationId()).isEqualTo("10");
        assertThat(entry.getValue().resourceVersion()).isEmpty();
    }

    @Test
    @DisplayName("una reserva que no existe no genera auditoría: no hay intento de acceso indebido que registrar")
    void doesNotAuditAPlainMiss() {
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> get(TestFixtures.owner()))
                .isInstanceOf(ReservationNotFoundException.class);

        verify(auditTrail, never()).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("backoffice sí alcanza una reserva ajena")
    void backofficeReachesForeignReservations() {
        Reservation stored = TestFixtures.storedReservation(3L);
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID)).thenReturn(Optional.of(stored));

        assertThat(get(TestFixtures.backoffice())).isSameAs(stored);
    }

    @Test
    @DisplayName("rechaza un pedido sin id o sin solicitante")
    void rejectsIncompleteQuery() {
        assertThatThrownBy(() -> service.get(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GetReservationQuery(null, TestFixtures.owner()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GetReservationQuery(TestFixtures.RESERVATION_ID, null))
                .isInstanceOf(NullPointerException.class);
    }
}
