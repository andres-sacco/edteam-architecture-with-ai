package com.edteam.reservations.infrastructure.adapter.out.airport;

import com.edteam.reservations.application.exception.AirportCatalogIntegrationException;
import com.edteam.reservations.infrastructure.adapter.out.airport.catalog.CatalogDeadline;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El techo de latencia del pedido.
 *
 * <p>El hallazgo que esto cierra es el número: con las ciudades resueltas en
 * serie y el contrato admitiendo diez tramos —que encadenados son once
 * ciudades distintas—, un {@code POST} contra un catálogo colgado costaba del
 * orden de noventa segundos. Más que el {@code graceful shutdown} de 25 s y
 * más que el corte de cualquier proxy: el usuario veía un {@code 504}
 * mientras el servidor seguía y creaba la reserva igual.
 */
@DisplayName("Presupuesto de tiempo del itinerario")
class BudgetedCityCatalogFanoutTest {

    /** Once ciudades: diez tramos encadenados, que es lo que admite el contrato. */
    private static final List<String> ELEVEN_CITIES = List.of(
            "BUE", "SCL", "LIM", "BOG", "MEX", "MIA", "NYC", "MAD", "BCN", "PAR", "LON");

    private static final Duration BUDGET = Duration.ofMillis(1_600);

    @Test
    @DisplayName("once ciudades contra un catálogo colgado responden dentro del presupuesto")
    void anItineraryAgainstAHungCatalogRespectsTheBudget() {
        // Cada ciudad tarda cinco segundos: en serie serían 55 s.
        CityResolver hung = sleeping(Duration.ofSeconds(5));
        CityResolver fanout = new BudgetedCityCatalogFanout(
                hung, BUDGET, Clock.systemUTC(), new SimpleMeterRegistry());

        long startedAt = System.nanoTime();
        Map<String, CityResolution> resolutions = fanout.resolve(ELEVEN_CITIES);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed)
                .as("el presupuesto es del itinerario, no de la ciudad")
                .isLessThan(BUDGET.plusMillis(500));
        assertThat(resolutions).containsOnlyKeys(ELEVEN_CITIES.toArray(String[]::new));
        assertThat(resolutions.values())
                .as("lo que no contestó se trata como no disponible y entra al fallback; "
                        + "nunca se acepta una ciudad sin validar para ganar tiempo")
                .allMatch(resolution -> resolution.status() == CityResolution.Status.UNAVAILABLE);
    }

    @Test
    @DisplayName("once ciudades sanas cuestan la más lenta, no la suma")
    void healthyCitiesCostTheSlowestAndNotTheSum() {
        CityResolver slowButHealthy = sleeping(Duration.ofMillis(300), CityResolution.present());
        CityResolver fanout = new BudgetedCityCatalogFanout(
                slowButHealthy, BUDGET, Clock.systemUTC(), new SimpleMeterRegistry());

        long startedAt = System.nanoTime();
        Map<String, CityResolution> resolutions = fanout.resolve(ELEVEN_CITIES);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(resolutions.values()).allMatch(CityResolution::exists);
        assertThat(elapsed)
                .as("en serie serían 3,3 s; en paralelo, poco más de 300 ms")
                .isLessThan(Duration.ofMillis(1_500));
    }

    @Test
    @DisplayName("instala el vencimiento dentro de cada tarea, para que el retry pueda rendirse temprano")
    void installsTheDeadlineInsideEachTask() {
        List<Duration> remaining = new CopyOnWriteArrayList<>();
        CityResolver probe = codes -> {
            remaining.add(CatalogDeadline.remaining(Clock.systemUTC()));
            return answers(codes, CityResolution.present());
        };
        CityResolver fanout = new BudgetedCityCatalogFanout(
                probe, BUDGET, Clock.systemUTC(), new SimpleMeterRegistry());

        fanout.resolve(List.of("BUE", "SCL"));

        assertThat(remaining).hasSize(2).allSatisfy(left -> {
            assertThat(left).isNotNull();
            assertThat(left).isLessThanOrEqualTo(BUDGET);
        });
    }

    @Test
    @DisplayName("un fallo permanente de una ciudad se propaga: no se tapa con paralelismo")
    void aPermanentFailurePropagates() {
        CityResolver broken = codes -> {
            throw new AirportCatalogIntegrationException("401 del catálogo");
        };
        CityResolver fanout = new BudgetedCityCatalogFanout(
                broken, BUDGET, Clock.systemUTC(), new SimpleMeterRegistry());

        assertThatThrownBy(() -> fanout.resolve(List.of("BUE")))
                .isInstanceOf(AirportCatalogIntegrationException.class);
    }

    @Test
    @DisplayName("una lista vacía no arranca ninguna tarea")
    void anEmptyItineraryDoesNothing() {
        List<String> seen = new ArrayList<>();
        CityResolver spy = codes -> {
            seen.addAll(codes);
            return answers(codes, CityResolution.present());
        };

        assertThat(new BudgetedCityCatalogFanout(spy, BUDGET, Clock.systemUTC(), new SimpleMeterRegistry())
                .resolve(List.of())).isEmpty();
        assertThat(seen).isEmpty();
    }

    private static CityResolver sleeping(Duration delay) {
        return sleeping(delay, CityResolution.present());
    }

    private static CityResolver sleeping(Duration delay, CityResolution resolution) {
        return codes -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return answers(codes, resolution);
        };
    }

    private static Map<String, CityResolution> answers(Collection<String> codes, CityResolution resolution) {
        Map<String, CityResolution> resolutions = new LinkedHashMap<>();
        codes.forEach(code -> resolutions.put(code, resolution));
        return resolutions;
    }
}
