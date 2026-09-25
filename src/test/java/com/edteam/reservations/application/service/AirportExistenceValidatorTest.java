package com.edteam.reservations.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.edteam.reservations.application.exception.UnknownAirportException;
import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.domain.model.AirportCode;
import com.edteam.reservations.support.TestFixtures;
import java.util.Collection;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * El validador después del cambio de firma del puerto.
 *
 * <p>Los tests que verificaban «consulta una sola vez cada aeropuerto» se
 * reescribieron: con el puerto preguntando de a un código, eso se comprobaba
 * contando invocaciones; ahora se comprueba mirando el conjunto que se manda,
 * que es una aserción más directa sobre lo mismo. Y se agregó el que importa
 * de verdad bajo el esquema nuevo: que haya <strong>una sola</strong> llamada
 * al puerto por itinerario, que es lo que habilita el paralelismo y el
 * presupuesto.
 */
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
        when(airportCatalog.unknown(anyCollection())).thenReturn(Set.of());

        assertThatCode(() -> validator.validate(TestFixtures.newItinerary())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("valida también los aeropuertos de las escalas, en una sola consulta")
    void validatesConnectionAirportsInOneCall() {
        when(airportCatalog.unknown(anyCollection())).thenReturn(Set.of());

        validator.validate(TestFixtures.connectingItinerary());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<AirportCode>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(airportCatalog, times(1)).unknown(captor.capture());
        assertThat(captor.getValue())
                .as("el itinerario entero va en una sola consulta: es lo que permite resolverlo en paralelo")
                .containsExactlyInAnyOrder(TestFixtures.EZE, TestFixtures.SCL, TestFixtures.MAD);
    }

    @Test
    @DisplayName("pide una sola vez cada aeropuerto, aunque aparezca en dos tramos")
    void asksForEachAirportOnce() {
        when(airportCatalog.unknown(anyCollection())).thenReturn(Set.of());

        validator.validate(TestFixtures.connectingItinerary());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<AirportCode>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(airportCatalog).unknown(captor.capture());
        // SCL es destino del primer tramo y origen del segundo.
        assertThat(captor.getValue()).hasSize(3);
    }

    @Test
    @DisplayName("informa el aeropuerto que no existe")
    void reportsUnknownAirport() {
        when(airportCatalog.unknown(anyCollection())).thenReturn(Set.of(TestFixtures.SCL));

        assertThatThrownBy(() -> validator.validate(TestFixtures.newItinerary()))
                .isInstanceOf(UnknownAirportException.class)
                .hasMessageContaining("SCL");
    }

    @Test
    @DisplayName("informa todos los desconocidos juntos, para corregirlos de una sola vez")
    void reportsEveryUnknownAirportAtOnce() {
        when(airportCatalog.unknown(anyCollection()))
                .thenReturn(Set.of(TestFixtures.EZE, TestFixtures.SCL, TestFixtures.MAD));

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
