package com.edteam.reservations.application.service;

import com.edteam.reservations.application.port.in.ListReservationsQuery;
import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.application.query.ReservationSearchCriteria;
import com.edteam.reservations.application.query.ReservationSortBy;
import com.edteam.reservations.application.query.ResultPage;
import com.edteam.reservations.application.query.SortDirection;
import com.edteam.reservations.domain.access.Actor;
import com.edteam.reservations.domain.access.ReservationAccessDeniedException;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationStatus;
import com.edteam.reservations.domain.model.Email;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ListReservationsService")
class ListReservationsServiceTest {

    @Mock
    private ReservationRepositoryPort reservationRepository;

    private ListReservationsService service;

    @BeforeEach
    void setUp() {
        service = new ListReservationsService(reservationRepository);
    }

    private ResultPage<Reservation> list(ReservationSearchCriteria criteria, Actor actor) {
        return service.list(new ListReservationsQuery(criteria, actor));
    }

    @Test
    @DisplayName("delega el resto del criterio tal cual y devuelve la página del repositorio")
    void delegatesToRepository() {
        ReservationSearchCriteria criteria = new ReservationSearchCriteria(
                Optional.of(Email.of(TestFixtures.USER_EMAIL)), Set.of(ReservationStatus.PENDING),
                Optional.empty(), Optional.empty(), 1, 10,
                ReservationSortBy.FIRST_DEPARTURE_AT, SortDirection.ASC);
        ResultPage<Reservation> expected = new ResultPage<>(
                List.of(TestFixtures.storedReservation(0L)), 1, 10, 25L);
        when(reservationRepository.search(criteria)).thenReturn(expected);

        assertThat(list(criteria, TestFixtures.owner())).isSameAs(expected);
        verify(reservationRepository).search(criteria);
    }

    @Test
    @DisplayName("sin filtro de usuario, se le impone el suyo: nunca devuelve las reservas de todos")
    void narrowsTheUnfilteredListingToTheCaller() {
        // Es la mitigación de T-03. Antes, un GET /v1/reservations sin
        // parámetros devolvía las reservas de todo el sistema de a 100 por
        // página: la base entera, con los documentos de los pasajeros, en
        // minutos de scraping.
        when(reservationRepository.search(any())).thenReturn(ResultPage.empty(0, 20));

        list(ReservationSearchCriteria.unfiltered(), TestFixtures.owner());

        ArgumentCaptor<ReservationSearchCriteria> effective = ArgumentCaptor.captor();
        verify(reservationRepository).search(effective.capture());
        assertThat(effective.getValue().userEmail()).contains(Email.of(TestFixtures.USER_EMAIL));
    }

    @Test
    @DisplayName("pedir el listado de otro usuario es 403 y no llega al repositorio")
    void rejectsListingSomeoneElse() {
        ReservationSearchCriteria criteria = ReservationSearchCriteria.unfiltered()
                .restrictedTo(Optional.of(Email.of(TestFixtures.USER_EMAIL)));

        assertThatThrownBy(() -> list(criteria, TestFixtures.stranger()))
                .isInstanceOf(ReservationAccessDeniedException.class);

        verify(reservationRepository, never()).search(any());
    }

    @Test
    @DisplayName("backoffice sí puede filtrar por otro usuario")
    void backofficeCanListSomeoneElse() {
        ReservationSearchCriteria criteria = ReservationSearchCriteria.unfiltered()
                .restrictedTo(Optional.of(Email.of(TestFixtures.USER_EMAIL)));
        when(reservationRepository.search(any())).thenReturn(ResultPage.empty(0, 20));

        list(criteria, TestFixtures.backoffice());

        ArgumentCaptor<ReservationSearchCriteria> effective = ArgumentCaptor.captor();
        verify(reservationRepository).search(effective.capture());
        assertThat(effective.getValue().userEmail()).contains(Email.of(TestFixtures.USER_EMAIL));
    }

    @Test
    @DisplayName("backoffice sin filtro sí ve todo: es el privilegio, y es explícito")
    void backofficeCanListEverything() {
        when(reservationRepository.search(any())).thenReturn(ResultPage.empty(0, 20));

        list(ReservationSearchCriteria.unfiltered(), TestFixtures.backoffice());

        ArgumentCaptor<ReservationSearchCriteria> effective = ArgumentCaptor.captor();
        verify(reservationRepository).search(effective.capture());
        assertThat(effective.getValue().userEmail()).isEmpty();
    }

    @Test
    @DisplayName("una página vacía no es un error")
    void emptyPageIsNotAnError() {
        when(reservationRepository.search(any())).thenReturn(ResultPage.empty(9, 20));

        ResultPage<Reservation> page = list(ReservationSearchCriteria.unfiltered(), TestFixtures.owner());

        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
    }

    @Test
    @DisplayName("sin criterio o sin solicitante falla en el borde y no llega al repositorio")
    void rejectsIncompleteQuery() {
        assertThatThrownBy(() -> service.list(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ListReservationsQuery(null, TestFixtures.owner()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ListReservationsQuery(ReservationSearchCriteria.unfiltered(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("el criterio rechaza una paginación que no tiene sentido")
    void criteriaValidatesItsOwnInvariants() {
        assertThatThrownBy(() -> new ReservationSearchCriteria(
                Optional.empty(), Set.of(), Optional.empty(), Optional.empty(),
                -1, 20, ReservationSortBy.CREATED_AT, SortDirection.DESC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("página");

        assertThatThrownBy(() -> new ReservationSearchCriteria(
                Optional.empty(), Set.of(), Optional.empty(), Optional.empty(),
                0, ReservationSearchCriteria.MAX_PAGE_SIZE + 1,
                ReservationSortBy.CREATED_AT, SortDirection.DESC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tamaño de página");

        assertThatThrownBy(() -> new ReservationSearchCriteria(
                Optional.empty(), Set.of(),
                Optional.of(Instant.parse("2026-12-01T00:00:00Z")),
                Optional.of(Instant.parse("2026-10-01T00:00:00Z")),
                0, 20, ReservationSortBy.CREATED_AT, SortDirection.DESC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("departureFrom");
    }

    @Test
    @DisplayName("el total de páginas se redondea hacia arriba")
    void roundsTotalPagesUp() {
        assertThat(new ResultPage<>(List.of(), 0, 10, 0L).totalPages()).isZero();
        assertThat(new ResultPage<>(List.of(), 0, 10, 1L).totalPages()).isEqualTo(1);
        assertThat(new ResultPage<>(List.of(), 0, 10, 10L).totalPages()).isEqualTo(1);
        assertThat(new ResultPage<>(List.of(), 0, 10, 11L).totalPages()).isEqualTo(2);
    }
}
