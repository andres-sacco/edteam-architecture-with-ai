package com.edteam.reservations.infrastructure.resilience;

import com.edteam.reservations.infrastructure.config.CircuitBreakerProperties;
import com.edteam.reservations.infrastructure.logging.LogFields;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Un circuito, con las dos cosas que la librería no trae de fábrica y este
 * diseño necesita.
 *
 * <p><strong>1. La ventana caduca.</strong> Los tres circuitos son
 * {@code COUNT_BASED} porque el tráfico es irregular: con una ventana temporal,
 * a las 4 AM dos fallos serían el 100 % de la ventana. Pero contar sin caducar
 * tiene el defecto inverso, y no estaba cubierto: cincuenta llamadas pueden
 * abarcar varias horas de madrugada, así que fallos de un incidente
 * <em>ya resuelto</em> mantienen el circuito al borde de abrir, y éxitos
 * viejos impiden que abra durante uno nuevo. Acá se combinan las dos cosas: se
 * cuentan llamadas, y si pasó más de {@code window-max-age} sin ninguna, la
 * ventana se descarta entera antes de registrar la siguiente. Sólo se descarta
 * con el circuito {@code CLOSED}: hacerlo en {@code OPEN} acortaría el tiempo
 * abierto y en {@code HALF_OPEN} perdería el resultado de las pruebas.
 *
 * <p><strong>2. Las transiciones se ven.</strong> Cada cambio de estado deja un
 * {@code WARN} con el nombre del circuito y las dos puntas de la transición.
 * La métrica de estado la publica el binder de Micrometer; el log es lo que
 * permite reconstruir el incidente después, que es cuando hace falta.
 *
 * <p>Esta clase es la <strong>única</strong> que el resto de la infraestructura
 * usa para hablar con la librería: los decoradores no importan
 * {@code CircuitBreakerConfig} ni el registro.
 */
public final class Circuit {

    private static final Logger log = LoggerFactory.getLogger(Circuit.class);

    private final CircuitBreaker breaker;
    private final Duration windowMaxAge;
    private final Clock clock;
    private final AtomicLong lastCallAt;

    private Circuit(CircuitBreaker breaker, Duration windowMaxAge, Clock clock) {
        this.breaker = Objects.requireNonNull(breaker, "El circuito es obligatorio");
        this.windowMaxAge = Objects.requireNonNull(windowMaxAge, "La edad máxima de la ventana es obligatoria");
        this.clock = Objects.requireNonNull(clock, "El clock es obligatorio");
        this.lastCallAt = new AtomicLong(clock.millis());
    }

    /**
     * Arma el circuito con los umbrales configurados y el clasificador de
     * fallos de su dependencia.
     *
     * @param classifier el mismo que usa el retry: es lo que garantiza que el
     *                   circuito no pueda contar algo distinto de lo que se
     *                   reintenta
     */
    public static Circuit of(
            String name,
            CircuitBreakerProperties properties,
            Function<Throwable, FailureClassification> classifier,
            CircuitBreakerRegistry registry,
            Clock clock) {
        Objects.requireNonNull(name, "El nombre del circuito es obligatorio");
        Objects.requireNonNull(properties, "Los umbrales son obligatorios");

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .slowCallDurationThreshold(properties.slowCallDurationThreshold())
                .slowCallRateThreshold(properties.slowCallRateThreshold())
                .waitDurationInOpenState(properties.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpenState())
                // La restricción «un circuito abierto no puede ser permanente»:
                // sin esto la vuelta a semiabierto depende de que llegue una
                // llamada, y un circuito que se abrió justo cuando el tráfico
                // cayó se quedaría abierto hasta el próximo pedido.
                .automaticTransitionFromOpenToHalfOpenEnabled(properties.automaticTransitionFromOpenToHalfOpen())
                .recordException(Failures.countsFor(classifier))
                .build();

        CircuitBreaker breaker = registry.circuitBreaker(name, config);
        breaker.getEventPublisher()
                .onStateTransition(event -> log.atWarn()
                        .addKeyValue(LogFields.EVENT, LogFields.CIRCUIT_STATE)
                        .addKeyValue("circuit", name)
                        .addKeyValue(
                                "state.from",
                                event.getStateTransition().getFromState().name())
                        .addKeyValue(
                                "state.to",
                                event.getStateTransition().getToState().name())
                        .log("Transición de estado del circuito"));

        // Toda la configuración en campos y no en prosa: antes era una frase
        // de la que no se podía filtrar un solo valor, y son los umbrales que
        // se comparan contra lo que el circuito está haciendo de verdad.
        log.atInfo()
                .addKeyValue(LogFields.EVENT, LogFields.STARTUP_WIRING)
                .addKeyValue("component", "circuit")
                .addKeyValue("circuit", name)
                .addKeyValue("circuit.windowSize", properties.slidingWindowSize())
                .addKeyValue("circuit.minimumCalls", properties.minimumNumberOfCalls())
                .addKeyValue("circuit.failureRateThreshold", properties.failureRateThreshold())
                .addKeyValue("circuit.slowCallRateThreshold", properties.slowCallRateThreshold())
                .addKeyValue(
                        "circuit.slowCallDurationMs",
                        properties.slowCallDurationThreshold().toMillis())
                .addKeyValue(
                        "circuit.openSeconds",
                        properties.waitDurationInOpenState().toSeconds())
                .addKeyValue("circuit.halfOpenCalls", properties.permittedCallsInHalfOpenState())
                .addKeyValue(
                        "circuit.windowMaxAgeMinutes", properties.windowMaxAge().toMinutes())
                .log("Circuito configurado");

        return new Circuit(breaker, properties.windowMaxAge(), clock);
    }

    /**
     * Un circuito apagado: deja pasar todo y no cuenta nada.
     *
     * <p>Existe para que {@code reservations.*.circuit-breaker.enabled=false}
     * sea una opción real y no una rama de {@code if} repartida por los
     * decoradores. Aislar un problema en producción sacando un circuito de
     * juego no debería necesitar un redeploy ni un cableado distinto.
     */
    public static Circuit disabled(String name, CircuitBreakerRegistry registry, Clock clock) {
        CircuitBreaker breaker = registry.circuitBreaker(name, CircuitBreakerConfig.ofDefaults());
        breaker.transitionToDisabledState();
        log.atWarn()
                .addKeyValue(LogFields.EVENT, LogFields.STARTUP_WIRING)
                .addKeyValue("component", "circuit")
                .addKeyValue("circuit", name)
                .addKeyValue("circuit.enabled", false)
                .log("Circuito APAGADO por configuración: las llamadas pasan sin contarse");
        return new Circuit(breaker, Duration.ofDays(365), clock);
    }

    /**
     * Ejecuta la llamada bajo el circuito.
     *
     * @throws io.github.resilience4j.circuitbreaker.CallNotPermittedException
     *         si el circuito está abierto. Cada decorador la traduce a la
     *         excepción de su puerto: la librería no cruza hacia arriba.
     */
    public <T> T execute(Supplier<T> action) {
        expireStaleWindow();
        return breaker.executeSupplier(action);
    }

    /** El estado actual, para los gates que consultan antes de trabajar. */
    public CircuitBreaker.State state() {
        expireStaleWindow();
        return breaker.getState();
    }

    public boolean isOpen() {
        return state() == CircuitBreaker.State.OPEN;
    }

    public boolean isHalfOpen() {
        return state() == CircuitBreaker.State.HALF_OPEN;
    }

    public String name() {
        return breaker.getName();
    }

    /** Sólo para tests y para la operación manual. */
    public CircuitBreaker breaker() {
        return breaker;
    }

    private void expireStaleWindow() {
        long now = clock.millis();
        long previous = lastCallAt.getAndSet(now);
        if (now - previous <= windowMaxAge.toMillis()) {
            return;
        }
        if (breaker.getState() != CircuitBreaker.State.CLOSED) {
            return;
        }
        log.atInfo()
                .addKeyValue(LogFields.EVENT, LogFields.CIRCUIT_STATE)
                .addKeyValue("circuit", breaker.getName())
                .addKeyValue(LogFields.REASON, "stale_window")
                .addKeyValue("circuit.windowMaxAgeMinutes", windowMaxAge.toMinutes())
                .log("La ventana del circuito quedó vieja: se descarta");
        breaker.reset();
    }
}
