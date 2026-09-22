package com.edteam.reservations.application.service;

import com.edteam.reservations.application.port.in.ItineraryData;
import com.edteam.reservations.application.port.in.PassengerData;
import com.edteam.reservations.domain.exception.InvalidAirportCodeException;
import com.edteam.reservations.domain.exception.InvalidItineraryException;
import com.edteam.reservations.domain.exception.InvalidMoneyException;
import com.edteam.reservations.domain.exception.InvalidPassengerException;
import com.edteam.reservations.domain.model.Itinerary;
import com.edteam.reservations.domain.model.Passenger;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ItineraryAssembler")
class ItineraryAssemblerTest {

    private final ItineraryAssembler assembler = new ItineraryAssembler();

    @Test
    @DisplayName("arma el itinerario del dominio respetando el orden de los tramos")
    void buildsItineraryPreservingOrder() {
        Itinerary itinerary = assembler.toItinerary(TestFixtures.connectingItineraryData());

        assertThat(itinerary.id()).isEmpty();
        assertThat(itinerary.segments()).hasSize(2);
        assertThat(itinerary.origin()).isEqualTo(TestFixtures.EZE);
        assertThat(itinerary.destination()).isEqualTo(TestFixtures.MAD);
        assertThat(itinerary.segments().getFirst().destination()).isEqualTo(TestFixtures.SCL);
        assertThat(itinerary.price().amount()).isEqualByComparingTo("1980.00");
    }

    @Test
    @DisplayName("los segmentos que arma no tienen id: los resuelve la persistencia")
    void buildsSegmentsWithoutId() {
        assertThat(assembler.toItinerary(TestFixtures.itineraryData()).segments())
                .allSatisfy(segment -> assertThat(segment.id()).isEmpty());
    }

    @Test
    @DisplayName("arma los pasajeros, con y sin documento")
    void buildsPassengers() {
        List<Passenger> passengers = assembler.toPassengers(List.of(
                new PassengerData("Ana", "Pérez", LocalDate.of(1990, 5, 20), "30123456"),
                new PassengerData("Juan", "Gómez", LocalDate.of(1985, 1, 2), null)));

        assertThat(passengers).hasSize(2);
        assertThat(passengers.getFirst().documentNumber()).contains("30123456");
        assertThat(passengers.getLast().documentNumber()).isEmpty();
        assertThat(passengers).allSatisfy(passenger -> assertThat(passenger.id()).isEmpty());
    }

    @Test
    @DisplayName("propaga las validaciones del dominio en la traducción")
    void propagatesDomainValidations() {
        ItineraryData codigoInvalido = new ItineraryData(BigDecimal.TEN, "USD",
                List.of(TestFixtures.segmentData(TestFixtures.EZE, TestFixtures.SCL, TestFixtures.DEPARTURE)));
        assertThat(assembler.toItinerary(codigoInvalido)).isNotNull();

        ItineraryData sinSegmentos = new ItineraryData(BigDecimal.TEN, "USD", List.of());
        assertThatThrownBy(() -> assembler.toItinerary(sinSegmentos))
                .isInstanceOf(InvalidItineraryException.class);

        ItineraryData monedaInvalida = new ItineraryData(BigDecimal.TEN, "DOLARES",
                List.of(TestFixtures.segmentData(TestFixtures.EZE, TestFixtures.SCL, TestFixtures.DEPARTURE)));
        assertThatThrownBy(() -> assembler.toItinerary(monedaInvalida))
                .isInstanceOf(InvalidMoneyException.class);

        ItineraryData aeropuertoInvalido = new ItineraryData(BigDecimal.TEN, "USD",
                List.of(new com.edteam.reservations.application.port.in.SegmentData(
                        "EZEE", "SCL", TestFixtures.AIRLINE, TestFixtures.DEPARTURE)));
        assertThatThrownBy(() -> assembler.toItinerary(aeropuertoInvalido))
                .isInstanceOf(InvalidAirportCodeException.class);

        assertThatThrownBy(() -> assembler.toPassengers(
                List.of(new PassengerData("", "Pérez", LocalDate.of(1990, 5, 20), "1"))))
                .isInstanceOf(InvalidPassengerException.class);
    }

    @Test
    @DisplayName("rechaza entradas nulas")
    void rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> assembler.toItinerary(null));
        assertThatNullPointerException().isThrownBy(() -> assembler.toPassengers(null));
    }
}
