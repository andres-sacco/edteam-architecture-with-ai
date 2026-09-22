package com.edteam.reservations.infrastructure.adapter.out.persistence;

import com.edteam.reservations.application.query.ReservationSearchCriteria;
import com.edteam.reservations.application.query.ReservationSortBy;
import com.edteam.reservations.application.query.SortDirection;
import com.edteam.reservations.domain.model.Email;
import com.edteam.reservations.domain.model.ReservationStatus;
import com.edteam.reservations.infrastructure.cache.CacheKeys;
import com.edteam.reservations.infrastructure.cache.FailingCacheStore;
import com.edteam.reservations.infrastructure.cache.InMemoryCacheStore;
import com.edteam.reservations.support.MutableClock;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CachingReservationSearchQuery")
class CachingReservationSearchQueryTest {

    private static final Duration TTL = Duration.ofSeconds(45);

    @Mock
    private ReservationSearchQuery delegate;

    private MutableClock clock;
    private InMemoryCacheStore store;
    private CachingReservationSearchQuery query;

    @BeforeEach
    void setUp() {
        clock = MutableClock.at(TestFixtures.NOW);
        store = new InMemoryCacheStore(clock, 1_000);
        query = new CachingReservationSearchQuery(delegate, store, TTL);
    }

    private static ReservationSearchCriteria criteria(int page, int size, ReservationSortBy sortBy) {
        return new ReservationSearchCriteria(Optional.empty(), Set.of(), Optional.empty(), Optional.empty(),
                page, size, sortBy, SortDirection.DESC);
    }

    @Test
    @DisplayName("el segundo conteo con el mismo filtro no vuelve a la base")
    void cachesTheCount() {
        ReservationSearchCriteria criteria = ReservationSearchCriteria.unfiltered();
        when(delegate.count(criteria)).thenReturn(1_204L);

        assertThat(query.count(criteria)).isEqualTo(1_204L);
        assertThat(query.count(criteria)).isEqualTo(1_204L);

        verify(delegate, times(1)).count(criteria);
    }

    @Test
    @DisplayName("la clave ignora página, tamaño y orden: las páginas de un mismo filtro comparten una entrada")
    void sharesOneEntryAcrossPages() {
        when(delegate.count(org.mockito.ArgumentMatchers.any())).thenReturn(1_204L);

        assertThat(query.count(criteria(0, 20, ReservationSortBy.CREATED_AT))).isEqualTo(1_204L);
        assertThat(query.count(criteria(3, 50, ReservationSortBy.FIRST_DEPARTURE_AT))).isEqualTo(1_204L);

        verify(delegate, times(1)).count(org.mockito.ArgumentMatchers.any());
        assertThat(store.estimatedSize()).hasValue(1L);
    }

    @Test
    @DisplayName("filtros distintos son entradas distintas")
    void separatesDifferentFilters() {
        ReservationSearchCriteria unfiltered = ReservationSearchCriteria.unfiltered();
        ReservationSearchCriteria byUser = new ReservationSearchCriteria(
                Optional.of(Email.of(TestFixtures.USER_EMAIL)), Set.of(), Optional.empty(), Optional.empty(),
                0, 20, ReservationSortBy.CREATED_AT, SortDirection.DESC);

        when(delegate.count(unfiltered)).thenReturn(1_204L);
        when(delegate.count(byUser)).thenReturn(7L);

        assertThat(query.count(unfiltered)).isEqualTo(1_204L);
        assertThat(query.count(byUser)).isEqualTo(7L);
        assertThat(query.count(unfiltered)).isEqualTo(1_204L);

        verify(delegate, times(1)).count(unfiltered);
        verify(delegate, times(1)).count(byUser);
    }

    @Test
    @DisplayName("el mismo conjunto de estados en otro orden da la misma clave")
    void normalisesStatusOrder() {
        ReservationSearchCriteria one = withStatuses(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);
        ReservationSearchCriteria other = withStatuses(ReservationStatus.CONFIRMED, ReservationStatus.PENDING);

        assertThat(CachingReservationSearchQuery.keyOf(one))
                .isEqualTo(CachingReservationSearchQuery.keyOf(other));
    }

    @Test
    @DisplayName("el rango de fechas forma parte de la clave")
    void datesArePartOfTheKey() {
        ReservationSearchCriteria withRange = new ReservationSearchCriteria(
                Optional.empty(), Set.of(), Optional.of(Instant.parse("2026-01-01T00:00:00Z")),
                Optional.empty(), 0, 20, ReservationSortBy.CREATED_AT, SortDirection.DESC);

        assertThat(CachingReservationSearchQuery.keyOf(withRange))
                .isNotEqualTo(CachingReservationSearchQuery.keyOf(ReservationSearchCriteria.unfiltered()));
    }

    @Test
    @DisplayName("vuelve a la base cuando vence el TTL")
    void expiresAfterTtl() {
        ReservationSearchCriteria criteria = ReservationSearchCriteria.unfiltered();
        when(delegate.count(criteria)).thenReturn(1_204L, 1_205L);

        assertThat(query.count(criteria)).isEqualTo(1_204L);
        clock.advance(TTL.minusSeconds(1));
        assertThat(query.count(criteria)).isEqualTo(1_204L);

        clock.advance(Duration.ofSeconds(1));
        assertThat(query.count(criteria)).isEqualTo(1_205L);
        verify(delegate, times(2)).count(criteria);
    }

    @Test
    @DisplayName("un total de cero no se cachea: una página vacía vieja escondería reservas reales")
    void doesNotCacheZero() {
        ReservationSearchCriteria criteria = ReservationSearchCriteria.unfiltered();
        when(delegate.count(criteria)).thenReturn(0L, 1L);

        assertThat(query.count(criteria)).isZero();
        assertThat(query.count(criteria))
                .as("la reserva recién creada se ve en el acto, sin esperar el TTL")
                .isEqualTo(1L);

        // La segunda lectura sí devolvió un total útil, y ésa sí se guarda.
        verify(delegate, times(2)).count(criteria);
    }

    @Test
    @DisplayName("los ids de la página nunca se cachean: son cardinalidad alta y llevan a los cuerpos con PII")
    void neverCachesThePage() {
        ReservationSearchCriteria criteria = ReservationSearchCriteria.unfiltered();
        when(delegate.findPageOfIds(criteria)).thenReturn(List.of(1L, 2L, 3L));

        assertThat(query.findPageOfIds(criteria)).containsExactly(1L, 2L, 3L);
        assertThat(query.findPageOfIds(criteria)).containsExactly(1L, 2L, 3L);

        verify(delegate, times(2)).findPageOfIds(criteria);
        assertThat(store.estimatedSize()).hasValue(0L);
    }

    @Test
    @DisplayName("lo cacheado es un número y nada más")
    void storesOnlyANumber() {
        ReservationSearchCriteria criteria = ReservationSearchCriteria.unfiltered();
        when(delegate.count(criteria)).thenReturn(1_204L);

        query.count(criteria);

        assertThat(store.get(CachingReservationSearchQuery.keyOf(criteria))).contains("1204");
    }

    @Test
    @DisplayName("la clave no expone el email del filtro: va resumido")
    void keyDoesNotLeakTheEmail() {
        ReservationSearchCriteria byUser = new ReservationSearchCriteria(
                Optional.of(Email.of(TestFixtures.USER_EMAIL)), Set.of(), Optional.empty(), Optional.empty(),
                0, 20, ReservationSortBy.CREATED_AT, SortDirection.DESC);

        String key = CachingReservationSearchQuery.keyOf(byUser);

        assertThat(key).startsWith(CacheKeys.RESERVATION_COUNT_PREFIX).doesNotContain(TestFixtures.USER_EMAIL);
    }

    @Test
    @DisplayName("con el cache caído responde igual, contra la base")
    void degradesWhenTheCacheIsDown() {
        ReservationSearchCriteria criteria = ReservationSearchCriteria.unfiltered();
        FailingCacheStore broken = new FailingCacheStore();
        CachingReservationSearchQuery degraded = new CachingReservationSearchQuery(delegate, broken, TTL);
        when(delegate.count(criteria)).thenReturn(1_204L);

        assertThat(degraded.count(criteria)).isEqualTo(1_204L);
        assertThat(degraded.count(criteria)).isEqualTo(1_204L);

        verify(delegate, times(2)).count(criteria);
    }

    @Test
    @DisplayName("un valor ilegible en el cache es un miss, no un error")
    void treatsCorruptValueAsMiss() {
        ReservationSearchCriteria criteria = ReservationSearchCriteria.unfiltered();
        store.put(CachingReservationSearchQuery.keyOf(criteria), "no-es-un-numero", TTL);
        when(delegate.count(criteria)).thenReturn(1_204L);

        assertThat(query.count(criteria)).isEqualTo(1_204L);
        verify(delegate, times(1)).count(criteria);
    }

    @Test
    @DisplayName("exige delegado, almacén y TTL positivo")
    void validatesConstructorArguments() {
        assertThatThrownBy(() -> new CachingReservationSearchQuery(null, store, TTL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CachingReservationSearchQuery(delegate, null, TTL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CachingReservationSearchQuery(delegate, store, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CachingReservationSearchQuery(delegate, store, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);

        verify(delegate, never()).count(org.mockito.ArgumentMatchers.any());
    }

    private static ReservationSearchCriteria withStatuses(ReservationStatus... statuses) {
        return new ReservationSearchCriteria(Optional.empty(), Set.of(statuses), Optional.empty(), Optional.empty(),
                0, 20, ReservationSortBy.CREATED_AT, SortDirection.DESC);
    }
}
