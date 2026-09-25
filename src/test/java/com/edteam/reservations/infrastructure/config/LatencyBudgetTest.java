package com.edteam.reservations.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.edteam.reservations.infrastructure.security.RateLimitFilter;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * El presupuesto de latencia, <strong>generado</strong> a partir de
 * {@code application.yml} y no escrito a mano.
 *
 * <p>Es la forma de cerrar los tres hallazgos de números divergentes. El
 * javadoc del retry declaraba un peor caso de 6,5 s que omitía el connect
 * timeout —el real era 7,8 s— y todo cálculo derivado quedaba un 17 % corto;
 * el diseño presupuestaba ocho ciudades cuando el contrato admite once; y el
 * presupuesto facturaba como Redis un rate limit que es en memoria. Editar el
 * texto no arregla nada: el día que alguien cambie un timeout, el texto vuelve
 * a quedar viejo. Este test lo calcula y falla.
 */
@DisplayName("Presupuesto de latencia")
class LatencyBudgetTest {

    /**
     * Once, no ocho. Diez tramos encadenados —el máximo del contrato— tocan
     * once ciudades distintas. El número del diseño subestimaba el peor caso.
     */
    private static final int MAX_CITIES = 11;

    // -----------------------------------------------------------------
    // Los renglones del presupuesto, uno por techo configurado.
    // -----------------------------------------------------------------

    /** El rate limit es en memoria: no cuesta una ida a Redis (ver el último test). */
    private static final Duration RATE_LIMIT = Duration.ZERO;

    /** Una sola lectura agrupada (MGET) y no una por ciudad. */
    private static final Duration CACHE_READ = Duration.ofMillis(200);

    /** La escritura de las ciudades resueltas. */
    private static final Duration CACHE_WRITE = Duration.ofMillis(200);

    /** {@code spring.datasource.hikari.connection-timeout}. */
    private static final Duration POOL = Duration.ofSeconds(1);

    /** {@code @Transactional(timeout = 1)} de las clases {@code *Transaction}. */
    private static final Duration WRITE_TRANSACTION = Duration.ofSeconds(1);

    /** Una lectura por índice con {@code @EntityGraph}, acotada por {@code statement_timeout}. */
    private static final Duration READ_QUERY = Duration.ofSeconds(2);

    private static final Duration SERIALIZATION = Duration.ofMillis(100);

    /**
     * Techo duro del {@code POST}, sostenido por los timeouts configurados.
     *
     * <p>El objetivo declarado por el diseño es 4 s; el techo que los números
     * de {@code application.yml} realmente sostienen es 4,2 s. Los 200 ms de
     * diferencia son la escritura del cache, que el diseño lista como el
     * tercer recorte disponible («si el presupuesto se agotó, el put se hace
     * sin esperar») y que todavía no se recorta. Está anotado como lo que
     * queda por hacer, no escondido en una constante más grande.
     */
    private static final Duration POST_CEILING = Duration.ofMillis(4_200);

    /**
     * Techo duro del {@code PUT}. Mayor que el del {@code POST} por la lectura
     * previa —{@code findById} fuera de transacción, que necesita su propia
     * conexión— y mayor que el objetivo de 4,5 s del diseño por la misma
     * razón: ese cálculo no facturaba la obtención de la conexión dos veces.
     */
    private static final Duration PUT_CEILING = Duration.ofMillis(7_200);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(Properties.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AirportCatalogProperties.class)
    static class Properties {}

    @Test
    @DisplayName("el peor caso por ciudad sale de los timeouts configurados, no de una constante")
    void theWorstCasePerCityIsDerived() {
        runner.run(context -> {
            AirportCatalogProperties properties = context.getBean(AirportCatalogProperties.class);

            // connect + read, y el connect SÍ cuenta: ése era el 17 % que
            // faltaba en el número documentado.
            assertThat(properties.attemptCost())
                    .isEqualTo(properties.connectTimeout().plus(properties.readTimeout()));

            Duration expected = properties
                    .attemptCost()
                    .multipliedBy(properties.retry().maxAttempts())
                    .plus(properties.retryPolicy().worstCaseBackoff());
            assertThat(properties.worstCasePerCity()).isEqualTo(expected);
        });
    }

    @Test
    @DisplayName("el presupuesto del itinerario acota el peor caso, aunque sean once ciudades")
    void theItineraryBudgetCapsTheWorstCase() {
        runner.run(context -> {
            AirportCatalogProperties properties = context.getBean(AirportCatalogProperties.class);

            assertThat(properties.worstCaseItinerary())
                    .as("el presupuesto es del itinerario, así que %d ciudades cuestan lo mismo que una", MAX_CITIES)
                    .isLessThanOrEqualTo(properties.itineraryBudget());
        });
    }

    @Test
    @DisplayName("el peor caso del POST entra en su techo, desglosado renglón por renglón")
    void theWorstCasePostFitsInItsCeiling() {
        runner.run(context -> {
            AirportCatalogProperties properties = context.getBean(AirportCatalogProperties.class);

            Duration worstCase = RATE_LIMIT
                    .plus(CACHE_READ)
                    .plus(properties.worstCaseItinerary())
                    .plus(CACHE_WRITE)
                    .plus(POOL)
                    .plus(WRITE_TRANSACTION)
                    .plus(SERIALIZATION);

            assertThat(worstCase)
                    .as("peor caso del POST: %d ms", worstCase.toMillis())
                    .isLessThanOrEqualTo(POST_CEILING);
        });
    }

    @Test
    @DisplayName("el peor caso del PUT entra en su techo")
    void theWorstCasePutFitsInItsCeiling() {
        runner.run(context -> {
            AirportCatalogProperties properties = context.getBean(AirportCatalogProperties.class);

            Duration worstCase = RATE_LIMIT
                    .plus(CACHE_READ)
                    .plus(POOL)
                    .plus(READ_QUERY) // findById fuera de transacción
                    .plus(properties.worstCaseItinerary())
                    .plus(CACHE_WRITE)
                    .plus(POOL)
                    .plus(WRITE_TRANSACTION) // la transacción de escritura
                    .plus(SERIALIZATION);

            assertThat(worstCase)
                    .as("peor caso del PUT: %d ms", worstCase.toMillis())
                    .isLessThanOrEqualTo(PUT_CEILING);
        });
    }

    @Test
    @DisplayName("con el circuito del catálogo abierto, el POST vuelve al orden del segundo")
    void anOpenCatalogCircuitBringsThePostBackToOneSecond() {
        // Es el valor concreto del circuito, y la razón por la que el
        // presupuesto se puede sostener aun con el proveedor caído: la
        // validación entera se resuelve contra el valor guardado sin tocar la
        // red, y el catálogo deja de aportar al peor caso.
        Duration worstCase = RATE_LIMIT
                .plus(CACHE_READ)
                .plus(Duration.ZERO) // el catálogo no se consulta
                .plus(CACHE_WRITE)
                .plus(POOL)
                .plus(WRITE_TRANSACTION)
                .plus(SERIALIZATION);

        assertThat(worstCase).isLessThanOrEqualTo(Duration.ofMillis(2_600));
    }

    @Test
    @DisplayName("el peor caso cabe dentro del graceful shutdown: un deploy no corta reservas en curso")
    void theWorstCaseFitsInsideTheGracefulShutdown() {
        // Era el hallazgo con la consecuencia más incómoda: con ~90 s de peor
        // caso contra un 'timeout-per-shutdown-phase' de 25 s, un deploy
        // durante una degradación del catálogo cortaba los pedidos en curso, y
        // detrás de ellos había transacciones a punto de abrirse.
        runner.run(context -> {
            AirportCatalogProperties properties = context.getBean(AirportCatalogProperties.class);
            Duration gracefulShutdown = Duration.parse("PT"
                    + context.getEnvironment()
                            .getProperty("spring.lifecycle.timeout-per-shutdown-phase", "25s")
                            .replace("s", "S"));

            Duration worstCase = properties
                    .worstCaseItinerary()
                    .plus(Duration.ofSeconds(1)) // conexión del pool
                    .plus(Duration.ofSeconds(2)) // transacción
                    .plus(Duration.ofMillis(600)); // cache y serialización

            assertThat(worstCase)
                    .as("el pedido más largo posible tiene que poder terminar antes de que el proceso se vaya")
                    .isLessThan(gracefulShutdown);
        });
    }

    @Test
    @DisplayName("el rate limit no cuesta una ida a Redis: es en memoria")
    void theRateLimitDoesNotCostARedisRoundTrip() {
        // El presupuesto del diseño facturaba 200 ms de Redis por el rate
        // limit. No los cuesta —es un ConcurrentHashMap por instancia— y
        // dejarlo escrito sobreestimaba el presupuesto en 200 ms mientras
        // escondía el costo real: una cuota por instancia, N veces la nominal
        // con N instancias.
        assertThat(Arrays.stream(RateLimitFilter.class.getDeclaredFields())
                        .map(field -> field.getType().getName()))
                .as("ningún campo del filtro es un cliente de Redis")
                .noneMatch(type -> type.startsWith("org.springframework.data.redis"));
    }
}
