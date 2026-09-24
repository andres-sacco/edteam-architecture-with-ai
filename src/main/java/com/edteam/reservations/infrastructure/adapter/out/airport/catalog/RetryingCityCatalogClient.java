package com.edteam.reservations.infrastructure.adapter.out.airport.catalog;

import com.edteam.reservations.application.exception.AirportCatalogUnavailableException;
import com.edteam.reservations.infrastructure.logging.LogFields;
import com.edteam.reservations.infrastructure.logging.Throwables;
import com.edteam.reservations.infrastructure.resilience.FailureClassification;
import com.edteam.reservations.infrastructure.resilience.Failures;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reintenta {@code GET /city/{code}} cuando el fallo es transitorio.
 *
 * <p>Se reintenta sólo esta llamada, y sólo porque es una <strong>lectura
 * idempotente</strong>: repetirla no puede duplicar ni cambiar nada. Ninguna
 * escritura del sistema se reintenta. La única repetición admitida de un
 * {@code POST} es la que hace el cliente con la misma {@code Idempotency-Key},
 * y la resuelve la búsqueda por esa clave antes de insertar.
 *
 * <p>Tres cosas lo separan de la versión anterior, y las tres son hallazgos de
 * la auditoría:
 *
 * <ol>
 *   <li><strong>Qué se reintenta lo decide {@link Failures}</strong>, no un
 *       {@code catch} por tipo. Antes acá había una copia de la clasificación
 *       que el cliente HTTP ya hacía, y el {@code 429} caía del lado
 *       equivocado: se reintentaba, que es desobedecer al proveedor que acaba
 *       de pedirnos que bajemos el ritmo.</li>
 *   <li><strong>Dos intentos y no tres.</strong> El primer reintento recupera
 *       el microcorte, que es el 90 % de los fallos transitorios de red. El
 *       tercero cuesta 1,2 s más de peor caso y sólo ayuda si el proveedor se
 *       cae y se recupera en menos de 400 ms.</li>
 *   <li><strong>Consciente del presupuesto.</strong> Antes de dormir pregunta
 *       si lo que queda del itinerario alcanza para la espera más un intento
 *       completo. Si no alcanza, se rinde: el reintento llegaría tarde para
 *       este pedido y sólo le sacaría tiempo a las ciudades que faltan.</li>
 * </ol>
 *
 * <p>El backoff es exponencial con techo y con jitter sobre la mitad superior
 * del valor calculado: desincroniza N instancias sin hacer impredecible el
 * peor caso, que es lo que el presupuesto necesita para poder calcularse.
 *
 * <p>El {@code Sleeper} es inyectable: los tests verifican cuánto se habría
 * esperado sin esperarlo.
 */
public class RetryingCityCatalogClient implements CityCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(RetryingCityCatalogClient.class);

    /**
     * Reintentos, con el resultado como etiqueta. Sin esto la política no se
     * puede auditar.
     *
     * <p>La etiqueta es {@code result} y no {@code outcome}, y el §4.1 del
     * diseño decía que este contador no tenía ninguna: el código estaba bien y
     * la tabla mal (hallazgo 17). Lo que sostiene que las dos no se vuelvan a
     * desalinear es {@code MetricsCatalogTest}, que contrasta el juego exacto
     * de claves de cada medidor contra una tabla escrita en el test.
     */
    public static final String RETRIES = "reservations.catalog.retries";

    /** El valor de {@code dependency} en el log, igual al que sale en {@code X-Degraded}. */
    private static final String DEPENDENCY = "api-catalog";

    private final CityCatalogClient delegate;
    private final Retry retry;
    private final Sleeper sleeper;
    private final Duration attemptCost;
    private final Clock clock;
    private final Counter attempted;
    private final Counter recovered;
    private final Counter exhausted;
    private final Counter skippedByBudget;

    public RetryingCityCatalogClient(CityCatalogClient delegate,
                                     Retry retry,
                                     Sleeper sleeper,
                                     Duration attemptCost,
                                     Clock clock,
                                     MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "El delegado es obligatorio");
        this.retry = Objects.requireNonNull(retry, "La política de reintentos es obligatoria");
        this.sleeper = Objects.requireNonNull(sleeper, "El sleeper es obligatorio");
        this.attemptCost = Objects.requireNonNull(attemptCost, "El costo de un intento es obligatorio");
        this.clock = Objects.requireNonNull(clock, "El clock es obligatorio");
        Objects.requireNonNull(registry, "El registro de métricas es obligatorio");

        this.attempted = counter(registry, "attempted", "Reintentos disparados contra el catálogo");
        this.recovered = counter(registry, "recovered", "Reintentos que resolvieron la consulta");
        this.exhausted = counter(registry, "exhausted", "Resoluciones que agotaron todos los intentos");
        this.skippedByBudget = counter(registry, "skipped_by_budget",
                "Reintentos NO disparados porque el presupuesto del itinerario no alcanzaba");
    }

    /**
     * Sin presupuesto ni métricas: es el que usan los tests del decorador y
     * cualquier camino donde no hay un itinerario del que descontar tiempo.
     */
    public RetryingCityCatalogClient(CityCatalogClient delegate, Retry retry, Sleeper sleeper) {
        this(delegate, retry, sleeper, Duration.ZERO, Clock.systemUTC(), new SimpleMeterRegistry());
    }

    public RetryingCityCatalogClient(CityCatalogClient delegate, Retry retry) {
        this(delegate, retry, Sleeper.real());
    }

    private static Counter counter(MeterRegistry registry, String result, String description) {
        return Counter.builder(RETRIES).tags(Tags.of("result", result))
                .description(description).register(registry);
    }

    @Override
    public Optional<CatalogCity> findByCode(String code) {
        RuntimeException last = null;
        boolean retried = false;

        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            try {
                Optional<CatalogCity> city = delegate.findByCode(code);
                if (retried) {
                    recovered.increment();
                }
                return city;
            } catch (RuntimeException e) {
                FailureClassification classification = Failures.catalog(e);
                if (!classification.retryable()) {
                    // 429, credencial vencida, bulkhead lleno, circuito
                    // abierto: insistir no cambia el resultado, o lo empeora.
                    throw e;
                }
                last = e;
                if (attempt == retry.maxAttempts()) {
                    break;
                }

                Duration wait = retry.backoffFor(attempt);
                if (!CatalogDeadline.allows(wait.plus(attemptCost), clock)) {
                    skippedByBudget.increment();
                    log.atWarn()
                            .addKeyValue(LogFields.EVENT, LogFields.CATALOG_RETRY)
                            .addKeyValue(LogFields.DEPENDENCY, DEPENDENCY)
                            .addKeyValue(LogFields.CITY_CODE, code)
                            .addKeyValue(LogFields.ATTEMPT, attempt)
                            .addKeyValue(LogFields.OUTCOME, "skipped_by_budget")
                            .addKeyValue("required_ms", wait.plus(attemptCost).toMillis())
                            .log("No queda presupuesto del itinerario para otro intento: se cae al fallback");
                    break;
                }

                attempted.increment();
                retried = true;
                log.atWarn()
                        .addKeyValue(LogFields.EVENT, LogFields.CATALOG_RETRY)
                        .addKeyValue(LogFields.DEPENDENCY, DEPENDENCY)
                        .addKeyValue(LogFields.CITY_CODE, code)
                        .addKeyValue(LogFields.ATTEMPT, attempt)
                        .addKeyValue(LogFields.MAX_ATTEMPTS, retry.maxAttempts())
                        .addKeyValue(LogFields.BACKOFF_MS, wait.toMillis())
                        .addKeyValue(LogFields.OUTCOME, "retrying")
                        .addKeyValue(LogFields.EXCEPTION_CLASS, Throwables.rootClassOf(e))
                        .addKeyValue(LogFields.REASON, Throwables.reasonOf(e))
                        .log("El catálogo no respondió: se reintenta");
                if (!sleeper.sleep(wait)) {
                    // Interrumpieron el hilo: se corta acá en lugar de seguir
                    // ocupando a un pedido que ya nadie está esperando.
                    log.atWarn()
                            .addKeyValue(LogFields.EVENT, LogFields.CATALOG_RETRY)
                            .addKeyValue(LogFields.DEPENDENCY, DEPENDENCY)
                            .addKeyValue(LogFields.CITY_CODE, code)
                            .addKeyValue(LogFields.ATTEMPT, attempt)
                            .addKeyValue(LogFields.OUTCOME, "interrupted")
                            .log("Reintento interrumpido: se devuelve el último fallo");
                    break;
                }
            }
        }

        exhausted.increment();
        log.atWarn()
                .addKeyValue(LogFields.EVENT, LogFields.CATALOG_RETRY)
                .addKeyValue(LogFields.DEPENDENCY, DEPENDENCY)
                .addKeyValue(LogFields.CITY_CODE, code)
                .addKeyValue(LogFields.MAX_ATTEMPTS, retry.maxAttempts())
                .addKeyValue(LogFields.OUTCOME, "exhausted")
                .addKeyValue(LogFields.EXCEPTION_CLASS, Throwables.rootClassOf(last))
                .addKeyValue(LogFields.REASON, Throwables.reasonOf(last))
                .log("El catálogo no respondió después de todos los intentos");
        throw new AirportCatalogUnavailableException(
                "El catálogo no respondió por '%s' después de %d intentos".formatted(code, retry.maxAttempts()),
                last);
    }

    /**
     * Política de reintentos. El backoff crece por potencias de dos hasta el
     * techo, y el jitter se sortea sobre la mitad superior del valor
     * calculado: el peor caso sigue siendo el techo —que es lo que el
     * presupuesto necesita— y el mejor no cae tan abajo como para reintentar
     * casi en caliente.
     */
    public record Retry(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {

        private static final int MULTIPLIER = 2;

        public Retry {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("Tiene que haber al menos un intento");
            }
            requirePositive(initialBackoff, "El backoff inicial");
            requirePositive(maxBackoff, "El backoff máximo");
            if (maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException("El backoff máximo no puede ser menor que el inicial");
            }
        }

        public static Retry disabled() {
            return new Retry(1, Duration.ofMillis(1), Duration.ofMillis(1));
        }

        /** El peor caso de esta política: todas las esperas en su techo. */
        public Duration worstCaseBackoff() {
            Duration total = Duration.ZERO;
            for (int attempt = 1; attempt < maxAttempts; attempt++) {
                long millis = initialBackoff.toMillis();
                for (int i = 1; i < attempt && millis < maxBackoff.toMillis(); i++) {
                    millis *= MULTIPLIER;
                }
                total = total.plusMillis(Math.min(millis, maxBackoff.toMillis()));
            }
            return total;
        }

        Duration backoffFor(int attempt) {
            long millis = initialBackoff.toMillis();
            for (int i = 1; i < attempt && millis < maxBackoff.toMillis(); i++) {
                millis *= MULTIPLIER;
            }
            long capped = Math.min(millis, maxBackoff.toMillis());
            long half = Math.max(1L, capped / 2);
            return Duration.ofMillis(half + ThreadLocalRandom.current().nextLong(half + 1));
        }

        private static void requirePositive(Duration value, String what) {
            Objects.requireNonNull(value, what + " es obligatorio");
            if (value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(what + " debe ser positivo");
            }
        }
    }

    /** La espera entre intentos, inyectable para que los tests no duerman. */
    @FunctionalInterface
    public interface Sleeper {

        /** @return {@code false} si interrumpieron el hilo mientras esperaba */
        boolean sleep(Duration duration);

        static Sleeper real() {
            return duration -> {
                try {
                    Thread.sleep(duration);
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            };
        }
    }
}
