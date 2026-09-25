package com.edteam.reservations.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.edteam.reservations.domain.exception.InvalidItineraryException;
import com.edteam.reservations.support.TestFixtures;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Itinerary")
class ItineraryTest {

    @Test
    @DisplayName("expone origen, destino y salida del viaje completo")
    void exposesTripEndpoints() {
        Itinerary itinerary = TestFixtures.connectingItinerary();

        assertThat(itinerary.origin()).isEqualTo(TestFixtures.EZE);
        assertThat(itinerary.destination()).isEqualTo(TestFixtures.MAD);
        assertThat(itinerary.firstDeparture()).isEqualTo(TestFixtures.DEPARTURE);
        assertThat(itinerary.segments()).hasSize(2);
    }

    @Test
    @DisplayName("devuelve todos los aeropuertos que toca, incluidas las escalas")
    void listsEveryAirport() {
        assertThat(TestFixtures.connectingItinerary().airports())
                .containsExactlyInAnyOrder(TestFixtures.EZE, TestFixtures.SCL, TestFixtures.MAD);
    }

    @Test
    @DisplayName("el resumen para las notificaciones refleja el viaje")
    void buildsSummary() {
        ItinerarySummary summary = TestFixtures.connectingItinerary().summary();

        assertThat(summary.origin()).isEqualTo(TestFixtures.EZE);
        assertThat(summary.destination()).isEqualTo(TestFixtures.MAD);
        assertThat(summary.firstDeparture()).isEqualTo(TestFixtures.DEPARTURE);
        assertThat(summary.segmentCount()).isEqualTo(2);
        assertThat(summary.price()).isEqualTo(TestFixtures.price());
        assertThat(summary.hasConnections()).isTrue();
        assertThat(TestFixtures.newItinerary().summary().hasConnections()).isFalse();
    }

    @Test
    @DisplayName("rechaza un itinerario sin segmentos")
    void rejectsEmptyItinerary() {
        assertThatThrownBy(() -> Itinerary.newItinerary(TestFixtures.price(), List.of()))
                .isInstanceOf(InvalidItineraryException.class)
                .hasMessageContaining("al menos un segmento");
    }

    @Test
    @DisplayName("rechaza segmentos que no se encadenan")
    void rejectsUnchainedSegments() {
        assertThatThrownBy(() -> Itinerary.newItinerary(
                        TestFixtures.price(),
                        List.of(
                                TestFixtures.newSegment(TestFixtures.EZE, TestFixtures.SCL, TestFixtures.DEPARTURE),
                                TestFixtures.newSegment(
                                        TestFixtures.GRU, TestFixtures.MAD, TestFixtures.CONNECTION_DEPARTURE))))
                .isInstanceOf(InvalidItineraryException.class)
                .hasMessageContaining("no se encadenan");
    }

    @Test
    @DisplayName("acepta un ida y vuelta, que también se encadena")
    void acceptsRoundTrip() {
        Itinerary roundTrip = Itinerary.newItinerary(
                TestFixtures.price(),
                List.of(
                        TestFixtures.newSegment(TestFixtures.EZE, TestFixtures.SCL, TestFixtures.DEPARTURE),
                        TestFixtures.newSegment(
                                TestFixtures.SCL, TestFixtures.EZE, TestFixtures.DEPARTURE.plus(Duration.ofDays(7)))));

        assertThat(roundTrip.origin()).isEqualTo(TestFixtures.EZE);
        assertThat(roundTrip.destination()).isEqualTo(TestFixtures.EZE);
    }

    @Test
    @DisplayName("rechaza segmentos fuera de orden cronológico")
    void rejectsSegmentsOutOfChronologicalOrder() {
        assertThatThrownBy(() -> Itinerary.newItinerary(
                        TestFixtures.price(),
                        List.of(
                                TestFixtures.newSegment(
                                        TestFixtures.EZE, TestFixtures.SCL, TestFixtures.CONNECTION_DEPARTURE),
                                TestFixtures.newSegment(TestFixtures.SCL, TestFixtures.MAD, TestFixtures.DEPARTURE))))
                .isInstanceOf(InvalidItineraryException.class)
                .hasMessageContaining("orden cronológico");
    }

    @Test
    @DisplayName("rechaza el mismo segmento repetido")
    void rejectsDuplicateSegments() {
        assertThatThrownBy(() -> Itinerary.newItinerary(
                        TestFixtures.price(), List.of(TestFixtures.directSegment(), TestFixtures.directSegment())))
                .isInstanceOf(InvalidItineraryException.class)
                .hasMessageContaining("más de una vez");
    }

    @Test
    @DisplayName("exige precio, segmentos e id explícito")
    void rejectsNulls() {
        assertThatNullPointerException()
                .isThrownBy(() -> Itinerary.newItinerary(null, List.of(TestFixtures.directSegment())));
        assertThatNullPointerException().isThrownBy(() -> Itinerary.newItinerary(TestFixtures.price(), null));
        assertThatNullPointerException()
                .isThrownBy(() -> new Itinerary(null, TestFixtures.price(), List.of(TestFixtures.directSegment())));
    }

    @Test
    @DisplayName("hasDeparted mira el primer tramo")
    void departureLooksAtTheFirstSegment() {
        Itinerary itinerary = TestFixtures.connectingItinerary();

        assertThat(itinerary.hasDeparted(TestFixtures.NOW)).isFalse();
        assertThat(itinerary.hasDeparted(TestFixtures.DEPARTURE)).isTrue();
        // Ya salió el primer tramo aunque falte la escala.
        assertThat(itinerary.hasDeparted(TestFixtures.CONNECTION_DEPARTURE.minusSeconds(1)))
                .isTrue();
    }

    @Test
    @DisplayName("la lista de segmentos es inmutable desde afuera")
    void segmentsAreImmutable() {
        Itinerary itinerary = TestFixtures.newItinerary();

        assertThatThrownBy(() -> itinerary.segments().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
