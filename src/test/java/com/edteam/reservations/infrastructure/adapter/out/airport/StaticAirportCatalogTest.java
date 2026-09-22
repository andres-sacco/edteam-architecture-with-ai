package com.edteam.reservations.infrastructure.adapter.out.airport;

import com.edteam.reservations.domain.model.AirportCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StaticAirportCatalog")
class StaticAirportCatalogTest {

    @Test
    @DisplayName("reconoce los aeropuertos del set por defecto")
    void knowsDefaultAirports() {
        StaticAirportCatalog catalog = StaticAirportCatalog.withDefaults();

        assertThat(catalog.exists(AirportCode.of("EZE"))).isTrue();
        assertThat(catalog.exists(AirportCode.of("MAD"))).isTrue();
    }

    @Test
    @DisplayName("no reconoce un aeropuerto que no está en el maestro")
    void rejectsUnknownAirport() {
        assertThat(StaticAirportCatalog.withDefaults().exists(AirportCode.of("ZZZ"))).isFalse();
    }

    @Test
    @DisplayName("normaliza los códigos que recibe en el constructor")
    void normalizesConfiguredCodes() {
        StaticAirportCatalog catalog = new StaticAirportCatalog(Set.of("eze"));

        assertThat(catalog.exists(AirportCode.of("EZE"))).isTrue();
    }

    @Test
    @DisplayName("tolera un código nulo")
    void handlesNullCode() {
        assertThat(StaticAirportCatalog.withDefaults().exists(null)).isFalse();
    }
}
