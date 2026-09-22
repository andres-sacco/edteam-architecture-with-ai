package com.edteam.reservations.application.service;

import com.edteam.reservations.application.exception.UnknownAirportException;
import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.domain.model.AirportCode;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AirportExistenceValidator")
class AirportExistenceValidatorTest {

    @Mock
    private AirportCatalogPort airportCatalog;

    private AirportExistenceValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AirportExistenceValidator(airportCatalog);
    }

    @Test
    @DisplayName("no hace nada si todos los aeropuertos existen")
    void passesWhenEveryAirportExists() {
        when(airportCatalog.exists(any(AirportCode.class))).thenReturn(true);

        assertThatCode(() -> validator.validate(TestFixtures.newItinerary())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("valida también los aeropuertos de las escalas")
    void validatesConnectionAirports() {
        when(airportCatalog.exists(any(AirportCode.class))).thenReturn(true);

        validator.validate(TestFixtures.connectingItinerary());

        verify(airportCatalog).exists(TestFixtures.EZE);
        verify(airportCatalog).exists(TestFixtures.SCL);
        verify(airportCatalog).exists(TestFixtures.MAD);
    }

    @Test
    @DisplayName("consulta una sola vez cada aeropuerto, aunque aparezca en dos tramos")
    void queriesEachAirportOnce() {
        when(airportCatalog.exists(any(AirportCode.class))).thenReturn(true);

        validator.validate(TestFixtures.connectingItinerary());

        // SCL es destino del primer tramo y origen del segundo.
        verify(airportCatalog).exists(TestFixtures.SCL);
    }

    @Test
    @DisplayName("informa el aeropuerto que no existe")
    void reportsUnknownAirport() {
        when(airportCatalog.exists(TestFixtures.EZE)).thenReturn(true);
        when(airportCatalog.exists(TestFixtures.SCL)).thenReturn(false);

        assertThatThrownBy(() -> validator.validate(TestFixtures.newItinerary()))
                .isInstanceOf(UnknownAirportException.class)
                .hasMessageContaining("SCL");
    }

    @Test
    @DisplayName("informa todos los desconocidos juntos, para corregirlos de una sola vez")
    void reportsEveryUnknownAirportAtOnce() {
        when(airportCatalog.exists(any(AirportCode.class))).thenReturn(false);

        assertThatThrownBy(() -> validator.validate(TestFixtures.connectingItinerary()))
                .isInstanceOf(UnknownAirportException.class)
                .hasMessageContaining("EZE")
                .hasMessageContaining("SCL")
                .hasMessageContaining("MAD");
    }

    @Test
    @DisplayName("exige el puerto del maestro y el itinerario")
    void rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> new AirportExistenceValidator(null));
        assertThatNullPointerException().isThrownBy(() -> validator.validate(null));
    }
}
