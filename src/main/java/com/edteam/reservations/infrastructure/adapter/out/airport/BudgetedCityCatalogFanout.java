package com.edteam.reservations.infrastructure.adapter.out.airport;

import com.edteam.reservations.application.exception.AirportCatalogIntegrationException;
import com.edteam.reservations.infrastructure.adapter.out.airport.catalog.CatalogDeadline;
import com.edteam.reservations.infrastructure.logging.LogFields;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * El techo de latencia del pedido, y la pieza que lo hace posible.
 *
 * <p>El problema que resuelve es de forma, no de números: mientras las
 * ciudades se resolvían <strong>en serie</strong>, el peor caso de un
 * itinerario era la suma del peor caso de cada una. El contrato admite diez
 * tramos, que encadenados son <strong>once ciudades distintas</strong>, así
 * que con el catálogo colgado un {@code POST} costaba alrededor de noventa
 * segundos — más que el {@code graceful shutdown} de 25 s y más que el corte
 * de cualquier proxy. Ningún ajuste de timeout arregla eso; hay que cambiar la
 * serie por un abanico.
 *
 * <p>Dos mecanismos, y los dos hacen falta:
 *
 * <ol>
 *   <li><strong>Fan-out sobre hilos virtuales.</strong> Once ciudades pasan de
 *       costar la suma a costar la más lenta. Los hilos virtuales son la razón
 *       por la que esto es gratis: no hay pool que dimensionar, y el bulkhead
 *       —que está más abajo— es el que impide que el paralelismo se convierta
 *       en una avalancha contra el proveedor.</li>
 *   <li><strong>Un vencimiento único para el itinerario.</strong> No uno por
 *       ciudad: el presupuesto es del pedido. Lo que no contestó cuando se
 *       agota se marca como no disponible y sigue el camino de fallback
 *       normal. Nunca se acepta una ciudad sin validar para ganar tiempo: eso
 *       cambiaría la corrección, no la latencia.</li>
 * </ol>
 *
 * <p>El vencimiento se instala también <em>dentro</em> de cada tarea
 * ({@link CatalogDeadline}) para que el retry pueda rendirse temprano en lugar
 * de arrancar un intento que no va a llegar a tiempo.
 *
 * <p>Una {@link AirportCatalogIntegrationException} de cualquier ciudad se
 * propaga: es el fallo que necesita una persona y no se tapa con paralelismo.
 */
public class BudgetedCityCatalogFanout implements CityResolver {

    private static final Logger log = LoggerFactory.getLogger(BudgetedCityCatalogFanout.class);

    /** Ciudades que quedaron sin resolver porque se agotó el presupuesto. */
    public static final String BUDGET_EXHAUSTED = "reservations.catalog.budget_exhausted";

    /** Cuánto tardó resolver el itinerario completo. Es lo que el presupuesto acota. */
    public static final String FANOUT_DURATION = "reservations.catalog.fanout";

    public static final String BUDGET_REASON = "budget_exhausted";

    private final CityResolver delegate;
    private final Duration budget;
    private final Clock clock;
    private final Counter budgetExhausted;
    private final Timer fanout;

    public BudgetedCityCatalogFanout(CityResolver delegate, Duration budget, Clock clock, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "El delegado es obligatorio");
        this.budget = Objects.requireNonNull(budget, "El presupuesto es obligatorio");
        if (budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("El presupuesto del itinerario debe ser positivo");
        }
        this.clock = Objects.requireNonNull(clock, "El clock es obligatorio");
        Objects.requireNonNull(registry, "El registro de métricas es obligatorio");
        this.budgetExhausted = Counter.builder(BUDGET_EXHAUSTED)
                .description("Ciudades que quedaron sin resolver porque venció el presupuesto del itinerario")
                .register(registry);
        this.fanout = Timer.builder(FANOUT_DURATION)
                .description("Tiempo de resolver todas las ciudades de un itinerario contra el origen")
                .register(registry);
    }

    @Override
    public Map<String, CityResolution> resolve(Collection<String> codes) {
        Map<String, CityResolution> resolutions = new LinkedHashMap<>();
        if (codes.isEmpty()) {
            return resolutions;
        }

        Instant startedAt = clock.instant();
        Instant deadline = startedAt.plus(budget);
        List<String> pending = List.copyOf(codes);

        // Un ejecutor por invocación: con hilos virtuales crear uno cuesta lo
        // que una asignación, y así las tareas de este pedido no comparten
        // estado con ningún otro.
        //
        // NO va en un try-with-resources: close() espera a que todas las
        // tareas terminen, y eso convertiría al presupuesto en decorativo —el
        // pedido quedaría esperando exactamente a la ciudad que se decidió
        // abandonar—. Se cierra con shutdown(), que no bloquea: las tareas
        // huérfanas terminan solas dentro de su propio read timeout y el
        // ejecutor se libera después, sin que nadie las espere.
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            // El MDC de Logback es un ThreadLocal NO heredable, y
            // `supplyAsync(..., workers)` no lleva nada: hasta acá, las catorce
            // líneas que el catálogo escribe por un POST degradado salían sin
            // correlationId. Son justamente las que uno va a buscar cuando un
            // POST sale degradado, así que el id faltaba exactamente donde más
            // se lo necesita (hallazgo 11).
            //
            // `captureAll()` se toma UNA vez en el hilo llamador y `wrap()` lo
            // restituye en cada tarea y lo limpia al terminar. El mismo wrap
            // arregla las dos cosas: el MDC y el padre del span cliente.
            ContextSnapshot snapshot = ContextSnapshotFactory.builder().build().captureAll();
            List<CompletableFuture<Map.Entry<String, CityResolution>>> futures = new ArrayList<>(pending.size());
            for (String code : pending) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> {
                            // `setThreadLocals()` restituye el contexto capturado en el
                            // hilo llamador —el MDC entre otros— y el try-with-resources
                            // lo deshace al terminar. Sin el cierre, el hilo virtual
                            // quedaría con el correlationId de este pedido pegado, y el
                            // ejecutor los reusa.
                            try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                                return Map.entry(
                                        code,
                                        CatalogDeadline.within(
                                                deadline,
                                                () -> delegate.resolve(List.of(code))
                                                        .getOrDefault(code, CityResolution.absent())));
                            }
                        },
                        workers));
            }

            int outOfBudget = 0;
            for (CompletableFuture<Map.Entry<String, CityResolution>> future : futures) {
                long left = Duration.between(clock.instant(), deadline).toMillis();
                try {
                    Map.Entry<String, CityResolution> entry = future.get(Math.max(0, left), TimeUnit.MILLISECONDS);
                    resolutions.put(entry.getKey(), entry.getValue());
                } catch (TimeoutException e) {
                    outOfBudget++;
                    future.cancel(true);
                } catch (ExecutionException e) {
                    throw unwrap(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    future.cancel(true);
                    outOfBudget++;
                }
            }

            if (outOfBudget > 0) {
                budgetExhausted.increment(outOfBudget);
                log.atWarn()
                        .addKeyValue(LogFields.EVENT, LogFields.CATALOG_FANOUT)
                        .addKeyValue(LogFields.DEPENDENCY, CachingAirportCatalog.DEPENDENCY)
                        .addKeyValue(LogFields.OUTCOME, "budget_exhausted")
                        .addKeyValue("catalog.budget_ms", budget.toMillis())
                        .addKeyValue("catalog.out_of_budget", outOfBudget)
                        .addKeyValue("catalog.cities", pending.size())
                        .log("Presupuesto del itinerario agotado: quedaron ciudades sin resolver");
            }
            // Las que no contestaron entran al fallback como cualquier otra
            // ciudad no disponible: el corte lo pusimos nosotros, así que NO
            // cuenta para el circuito —mezclaría nuestra política de latencia
            // con la salud del proveedor—.
            for (String code : pending) {
                resolutions.putIfAbsent(code, CityResolution.unavailable(BUDGET_REASON));
            }
        } finally {
            workers.shutdown();
            fanout.record(Duration.between(startedAt, clock.instant()));
        }
        return resolutions;
    }

    private static RuntimeException unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Fallo inesperado resolviendo el itinerario", cause);
    }
}
