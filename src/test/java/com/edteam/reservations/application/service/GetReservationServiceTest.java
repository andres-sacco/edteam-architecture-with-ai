package com.edteam.reservations.application.service;

import com.edteam.reservations.application.exception.ReservationNotFoundException;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetReservationService")
class GetReservationServiceTest {

    @Mock
    private ReservationRepositoryPort reservationRepository;

    private GetReservationService service;

    @BeforeEach
    void setUp() {
        service = new GetReservationService(reservationRepository);
    }

    @Test
    @DisplayName("devuelve la reserva encontrada")
    void returnsReservation() {
        Reservation stored = TestFixtures.storedReservation(3L);
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID)).thenReturn(Optional.of(stored));

        assertThat(service.getById(TestFixtures.RESERVATION_ID)).isSameAs(stored);
    }

    @Test
    @DisplayName("falla con ReservationNotFoundException si no existe")
    void failsWhenNotFound() {
        when(reservationRepository.findById(TestFixtures.RESERVATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(TestFixtures.RESERVATION_ID))
                .isInstanceOf(ReservationNotFoundException.class)
                .hasMessageContaining("10");
    }

    @Test
    @DisplayName("rechaza un id nulo")
    void rejectsNullId() {
        assertThatThrownBy(() -> service.getById(null)).isInstanceOf(NullPointerException.class);
    }
}
