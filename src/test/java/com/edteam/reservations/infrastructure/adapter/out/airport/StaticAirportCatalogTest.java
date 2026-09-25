package com.edteam.reservations.infrastructure.adapter.out.airport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El stub resuelve ahora por conjunto, igual que el resolutor real: así la
 * cadena de arriba —cache, presupuesto, fallback— es la misma en los dos modos
 * y lo que se prueba en local es lo que corre en producción.
 */
@DisplayName("StaticAirportCatalog")
class StaticAirportCatalogTest {

    @Test
    @DisplayName("reconoce los aeropuertos del set por defecto")
    void knowsDefaultAirports() {
        StaticAirportCatalog catalog = StaticAirportCatalog.withDefaults();

        assertThat(catalog.resolve(List.of("EZE", "MAD")).values()).allMatch(CityResolution::exists);
    }

    @Test
    @DisplayName("no reconoce un aeropuerto que no está en el maestro")
    void rejectsUnknownAirport() {
        assertThat(StaticAirportCatalog.withDefaults()
                        .resolve(List.of("ZZZ"))
                        .get("ZZZ")
                        .status())
                .isEqualTo(CityResolution.Status.ABSENT);
    }

    @Test
    @DisplayName("normaliza los códigos que recibe en el constructor")
    void normalizesConfiguredCodes() {
        StaticAirportCatalog catalog = new StaticAirportCatalog(Set.of("eze"));

        assertThat(catalog.resolve(List.of("EZE")).get("EZE").exists()).isTrue();
    }

    @Test
    @DisplayName("nunca devuelve 'no disponible': un stub en memoria no se cae")
    void neverReportsUnavailable() {
        assertThat(StaticAirportCatalog.withDefaults()
                        .resolve(List.of("EZE", "ZZZ"))
                        .values())
                .allMatch(CityResolution::isKnown);
    }

    @Test
    @DisplayName("responde por cada código pedido, siempre")
    void answersEveryRequestedCode() {
        assertThat(StaticAirportCatalog.withDefaults().resolve(List.of("EZE", "ZZZ", "MAD")))
                .containsOnlyKeys("EZE", "ZZZ", "MAD");
    }
}
