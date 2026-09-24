package com.edteam.reservations.infrastructure.adapter.out.airport;

import com.edteam.reservations.application.exception.AirportCatalogUnavailableException;
import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.domain.model.AirportCode;
import com.edteam.reservations.infrastructure.cache.CacheKeys;
import com.edteam.reservations.infrastructure.cache.CacheStore;
import com.edteam.reservations.infrastructure.resilience.DegradationRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Cache y <em>stale-while-error</em> sobre el maestro de aeropuertos. Es la
 * capa más externa del adaptador: la que implementa el puerto y la única que
 * decide qué se contesta.
 *
 * <p>El cache va afuera de todo —del circuito, del bulkhead, del retry— y eso
 * está elegido, no heredado: un hit no tiene que consumir una llamada del
 * circuito ni un permiso del bulkhead, porque no toca la red. Si el circuito
 * estuviera por encima, un circuito abierto dejaría sin servir datos que
 * estaban guardados y frescos, que es el peor resultado posible. Y el
 * <em>stale-while-error</em> <strong>necesita</strong> estar por encima del
 * circuito para poder reaccionar a su rechazo: el fallback es un envoltorio
 * alrededor de todo lo demás.
 *
 * <h2>Tres cosas cambiaron respecto de la versión anterior, y las tres son
 * hallazgos de la auditoría</h2>
 *
 * <ol>
 *   <li><strong>El fallback dejó de ser el camino lento.</strong> Antes se
 *       llamaba al origen y recién en el {@code catch} se servía el valor
 *       viejo: cada ciudad, en cada pedido, volvía a pagar los intentos
 *       completos antes de caer al <em>stale</em>. Un {@code POST} con el
 *       catálogo caído y el cache poblado devolvía {@code 201} después de
 *       ~86 s. Ahora hay dos cortes: el circuito, que hace que la llamada
 *       cueste microsegundos, y {@link #originAvailable}, que con el circuito
 *       abierto evita incluso bajar por la cadena.</li>
 *   <li><strong>El {@code catch} se estrechó.</strong> Atrapaba
 *       {@code RuntimeException}, así que una credencial vencida quedaba
 *       tapada durante horas detrás de un dato viejo y salía después como un
 *       {@code 500} genérico. Ahora sólo se tapa lo que el resolutor marcó
 *       como no disponible; una integración rota sube.</li>
 *   <li><strong>La ventana de gracia es sólo para los positivos.</strong>
 *       Servir un negativo viejo rechaza una reserva válida con un
 *       {@code 400 UNKNOWN_AIRPORT} que le dice al usuario que corrija un
 *       itinerario que estaba bien: no es reintentable y miente sobre la
 *       causa. Es el error más caro que este sistema puede cometer. Un
 *       negativo vencido con el origen caído se contesta {@code 503} +
 *       {@code Retry-After}, que es reintentable y honesto. Los negativos se
 *       guardan <strong>sin</strong> ventana de gracia, así que un negativo
 *       viejo estructuralmente no existe.</li>
 * </ol>
 *
 * <p>Y ninguna respuesta degradada es silenciosa: cada una pasa por
 * {@link DegradationRecorder}, que deja métrica, {@code WARN} y la marca que
 * el borde convierte en {@code X-Degraded}.
 */
public class CachingAirportCatalog implements AirportCatalogPort {

    private static final Logger log = LoggerFactory.getLogger(CachingAirportCatalog.class);

    /** Nombre de la dependencia en la métrica y en el header {@code X-Degraded}. */
    public static final String DEPENDENCY = "airport-catalog";

    private static final char SEPARATOR = '@';

    private final CityResolver delegate;
    private final CacheStore cache;
    private final Ttl ttl;
    private final Clock clock;
    private final DegradationRecorder degradation;
    private final BooleanSupplier originAvailable;

    public CachingAirportCatalog(CityResolver delegate,
                                 CacheStore cache,
                                 Ttl ttl,
                                 Clock clock,
                                 DegradationRecorder degradation,
                                 BooleanSupplier originAvailable) {
        this.delegate = Objects.requireNonNull(delegate, "El delegado es obligatorio");
        this.cache = Objects.requireNonNull(cache, "El almacén de cache es obligatorio");
        this.ttl = Objects.requireNonNull(ttl, "El TTL es obligatorio");
        this.clock = Objects.requireNonNull(clock, "El clock es obligatorio");
        this.degradation = Objects.requireNonNull(degradation, "El registrador de degradación es obligatorio");
        this.originAvailable = Objects.requireNonNull(originAvailable, "La sonda del origen es obligatoria");
    }

    /** Sin circuito ni métricas reales: el que usan los tests del decorador. */
    public CachingAirportCatalog(CityResolver delegate, CacheStore cache, Ttl ttl, Clock clock) {
        this(delegate, cache, ttl, clock,
                new DegradationRecorder(new SimpleMeterRegistry()), () -> true);
    }

    @Override
    public Set<AirportCode> unknown(Collection<AirportCode> codes) {
        Objects.requireNonNull(codes, "Los códigos son obligatorios");
        if (codes.isEmpty()) {
            return Set.of();
        }

        Instant now = clock.instant();
        Set<AirportCode> wanted = new LinkedHashSet<>(codes);
        wanted.remove(null);

        // Una sola lectura agrupada y no una por ciudad: con Redis caído, once
        // lecturas en serie eran 2,2 s del presupuesto gastados en un
        // componente cuyo aporte es ahorrar tiempo.
        Map<String, String> raw = cache.getAll(wanted.stream().map(CachingAirportCatalog::keyOf).toList());

        Set<AirportCode> unknown = new LinkedHashSet<>();
        Map<AirportCode, Entry> stale = new LinkedHashMap<>();
        Set<AirportCode> toResolve = new LinkedHashSet<>();

        for (AirportCode code : wanted) {
            Optional<Entry> cached = Optional.ofNullable(raw.get(keyOf(code))).flatMap(Entry::parse);
            if (cached.isPresent() && cached.get().isFreshAt(now)) {
                if (!cached.get().exists()) {
                    unknown.add(code);
                }
                continue;
            }
            cached.ifPresent(entry -> stale.put(code, entry));
            toResolve.add(code);
        }

        if (toResolve.isEmpty()) {
            return Set.copyOf(unknown);
        }

        Map<String, CityResolution> resolutions = resolve(toResolve);

        Set<AirportCode> unresolved = new LinkedHashSet<>();
        for (AirportCode code : toResolve) {
            CityResolution resolution = resolutions.getOrDefault(code.value(),
                    CityResolution.unavailable("sin respuesta"));
            if (resolution.isKnown()) {
                remember(code, resolution.exists(), now);
                if (!resolution.exists()) {
                    unknown.add(code);
                }
            } else if (servedFromGraceWindow(code, stale.get(code), resolution.reason(), now)) {
                // Sólo los positivos llegan acá: existe y se sirve como tal.
                log.debug("Ciudad {} servida desde la ventana de gracia", code);
            } else {
                unresolved.add(code);
            }
        }

        if (!unresolved.isEmpty()) {
            // Sin fallback posible: se falla de frente. Devolver 'existe' sería
            // inventar un dato y devolver 'no existe' rechazaría una reserva
            // válida con un error que el cliente no puede corregir.
            degradation.exhausted(DEPENDENCY, "no_fallback",
                    "sin dato guardado para " + codesOf(unresolved));
            throw new AirportCatalogUnavailableException(
                    "No se pudo verificar %s contra el maestro de aeropuertos".formatted(codesOf(unresolved)));
        }

        return Set.copyOf(unknown);
    }

    /**
     * Baja por la cadena, salvo que ya sepamos que el origen está caído.
     *
     * <p>Este corte es la memoria de «el origen no está» que el fallback no
     * tenía. El circuito ya hace que la llamada sea barata; esto la hace
     * inexistente, y además cubre el hueco que el circuito no cubre solo: con
     * el cache caliente, los primeros minutos de una caída producen muy pocas
     * llamadas reales, así que el circuito tarda en juntar los votos que
     * necesita para abrir. Cuando por fin abre, este atajo hace que ninguna
     * ciudad vuelva a pagar el viaje.
     */
    private Map<String, CityResolution> resolve(Set<AirportCode> toResolve) {
        if (!originAvailable.getAsBoolean()) {
            Map<String, CityResolution> shortCircuited = new LinkedHashMap<>();
            toResolve.forEach(code ->
                    shortCircuited.put(code.value(), CityResolution.unavailable("circuit_open")));
            return shortCircuited;
        }
        return delegate.resolve(toResolve.stream().map(AirportCode::value).toList());
    }

    /**
     * Sirve el último valor conocido, si lo hay, es positivo y está dentro de
     * la ventana.
     *
     * @return {@code true} si la respuesta quedó resuelta por el fallback
     */
    private boolean servedFromGraceWindow(AirportCode code, Entry entry, String reason, Instant now) {
        if (entry == null || !entry.exists()) {
            return false;
        }
        Instant graceUntil = entry.freshUntil().plus(ttl.staleWindow());
        if (!now.isBefore(graceUntil)) {
            return false;
        }
        degradation.served(DEPENDENCY, reason,
                "se sirve el último valor conocido de " + code.value(),
                Duration.between(entry.freshUntil(), now));
        return true;
    }

    private void remember(AirportCode code, boolean exists, Instant now) {
        Duration freshFor = exists ? ttl.positive() : ttl.negative();
        // Los positivos se guardan con la ventana de gracia encima; los
        // negativos, sin ella: un negativo vencido tiene que desaparecer, no
        // sobrevivir para rechazar una reserva válida.
        Duration keepFor = exists ? freshFor.plus(ttl.staleWindow()) : freshFor;
        cache.put(keyOf(code), new Entry(exists, now.plus(freshFor)).serialize(), keepFor);
    }

    /**
     * Borra la entrada de un código. No lo usa el camino del pedido: está para
     * la operación y para los tests, donde un cache que no se puede vaciar
     * obliga a esperar al TTL.
     */
    public void invalidate(AirportCode code) {
        if (code != null) {
            cache.evict(keyOf(code));
        }
    }

    private static String keyOf(AirportCode code) {
        return CacheKeys.CITY_PREFIX + code.value();
    }

    private static String codesOf(Collection<AirportCode> codes) {
        return codes.stream().map(AirportCode::value).reduce((a, b) -> a + ", " + b).orElse("");
    }

    /**
     * Los tres plazos del cache de ciudades.
     *
     * @param positive    cuánto vale un «existe»
     * @param negative    cuánto vale un «no existe»: más corto, porque un
     *                    código que hoy no existe puede darse de alta y el
     *                    costo de equivocarse es rechazar una reserva válida
     * @param staleWindow cuánto más se puede servir un positivo vencido si el
     *                    origen no responde. <strong>No se aplica a los
     *                    negativos</strong>
     */
    public record Ttl(Duration positive, Duration negative, Duration staleWindow) {

        public Ttl {
            requirePositive(positive, "El TTL de los positivos");
            requirePositive(negative, "El TTL de los negativos");
            Objects.requireNonNull(staleWindow, "La ventana de gracia es obligatoria");
            if (staleWindow.isNegative()) {
                throw new IllegalArgumentException("La ventana de gracia no puede ser negativa");
            }
        }

        private static void requirePositive(Duration value, String what) {
            Objects.requireNonNull(value, what + " es obligatorio");
            if (value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(what + " debe ser positivo");
            }
        }
    }

    /**
     * Lo guardado: un booleano y el instante hasta el que es fresco. Nada
     * sensible, y legible a ojo desde {@code redis-cli}.
     */
    private record Entry(boolean exists, Instant freshUntil) {

        String serialize() {
            return exists + String.valueOf(SEPARATOR) + freshUntil.toEpochMilli();
        }

        static Optional<Entry> parse(String raw) {
            int separator = raw.indexOf(SEPARATOR);
            if (separator < 0) {
                return Optional.empty();
            }
            try {
                return Optional.of(new Entry(
                        Boolean.parseBoolean(raw.substring(0, separator)),
                        Instant.ofEpochMilli(Long.parseLong(raw.substring(separator + 1)))));
            } catch (NumberFormatException e) {
                log.debug("Entrada de cache ilegible, se trata como miss: {}", raw);
                return Optional.empty();
            }
        }

        boolean isFreshAt(Instant now) {
            return now.isBefore(freshUntil);
        }
    }
}
