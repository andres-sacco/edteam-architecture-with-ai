package com.edteam.reservations.infrastructure.adapter.out.airport;

import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.domain.model.AirportCode;
import com.edteam.reservations.infrastructure.cache.CacheKeys;
import com.edteam.reservations.infrastructure.cache.CacheStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Decorador con cache sobre el maestro de aeropuertos. <strong>Es la entrada
 * P0 del análisis de cuellos de botella.</strong>
 *
 * <p>Motivo: el maestro se consulta una vez por cada código del itinerario
 * —origen y destino de cada tramo— en cada {@code POST} y cada {@code PUT}. Un
 * ida y vuelta con escala son 8 llamadas HTTP secuenciales por reserva, contra
 * la única dependencia de red del camino del pedido, y la única sin timeout ni
 * reintento. El dato, en cambio, es casi estático: el catálogo de ciudades
 * cambia con frecuencia mensual, no por pedido.
 *
 * <p>Es un decorador y no una anotación {@code @Cacheable} para que la decisión
 * de cachear quede explícita en el grafo de dependencias, sea testeable sin
 * levantar el contexto de Spring y pueda reemplazarse por un cache distribuido
 * sin tocar el adaptador que consulta el origen. Esta clase es justamente ese
 * reemplazo: el {@code ConcurrentHashMap} local que tenía antes pasó a ser un
 * {@link CacheStore}, que en producción es Redis. La diferencia no es de
 * latencia sino de alcance: con N instancias había N caches fríos, y cada
 * deploy disparaba una estampida contra el catálogo justo cuando el sistema
 * está más frágil.
 *
 * <h2>Dos TTL, no uno</h2>
 * Los positivos viven más (30 m por defecto) que los negativos (5 m). Un
 * código que hoy no existe puede darse de alta mañana, y el costo de
 * equivocarse no es simétrico: servir un negativo viejo es rechazar una
 * reserva válida, mientras que servir un positivo viejo es aceptar una ciudad
 * que se dio de baja, que el resto del flujo puede corregir después.
 *
 * <h2>{@code stale-while-error}</h2>
 * Si el catálogo devuelve 5xx, 429 o no contesta, se sirve el último valor
 * conocido aunque esté vencido. Para eso el valor guardado lleva su propio
 * instante de frescura y se almacena con un TTL más largo —la ventana de
 * gracia—, de modo que exista una franja en la que la entrada ya no es fresca
 * pero todavía se puede leer.
 *
 * <p>Es lo que convierte una caída del proveedor en una degradación silenciosa
 * en lugar de un rechazo masivo de reservas. Cuando no hay <em>nada</em>
 * guardado, la excepción sube: {@code CatalogAirportCatalog} no traga fallos a
 * propósito, y devolver {@code false} ante una caída sería rechazar reservas
 * con aeropuertos válidos.
 *
 * <p>Sólo se guardan resultados, nunca excepciones: una caída del catálogo no
 * envenena el cache.
 *
 * <h2>Qué se guarda</h2>
 * Un booleano y un instante, indexados por un código de tres letras. Nada
 * sensible: ni un dato de pasajero ni de pago. Unos cientos de entradas del
 * orden de decenas de KB, que es el mejor ratio beneficio/memoria de toda la
 * lista.
 */
public class CachingAirportCatalog implements AirportCatalogPort {

    private static final Logger log = LoggerFactory.getLogger(CachingAirportCatalog.class);

    private static final char SEPARATOR = '@';

    private final AirportCatalogPort delegate;
    private final CacheStore cache;
    private final Ttl ttl;
    private final Clock clock;

    public CachingAirportCatalog(AirportCatalogPort delegate, CacheStore cache, Ttl ttl, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "El delegado es obligatorio");
        this.cache = Objects.requireNonNull(cache, "El almacén de cache es obligatorio");
        this.ttl = Objects.requireNonNull(ttl, "El TTL es obligatorio");
        this.clock = Objects.requireNonNull(clock, "El clock es obligatorio");
    }

    @Override
    public boolean exists(AirportCode code) {
        if (code == null) {
            return false;
        }

        String key = CacheKeys.CITY_PREFIX + code.value();
        Instant now = clock.instant();
        Optional<Entry> cached = cache.get(key).flatMap(Entry::parse);

        if (cached.isPresent() && cached.get().isFreshAt(now)) {
            return cached.get().exists();
        }

        try {
            boolean exists = delegate.exists(code);
            Duration freshFor = exists ? ttl.positive() : ttl.negative();
            cache.put(key, new Entry(exists, now.plus(freshFor)).serialize(), freshFor.plus(ttl.staleWindow()));
            log.trace("Ciudad {} resuelta contra el origen: exists={}", code, exists);
            return exists;
        } catch (RuntimeException e) {
            if (cached.isEmpty()) {
                throw e;
            }
            log.warn("El catálogo no respondió por {}: se sirve el último valor conocido (exists={}). Causa: {}",
                    code, cached.get().exists(), e.getMessage());
            return cached.get().exists();
        }
    }

    /**
     * Invalida el código indicado. Útil ante un alta o baja conocida.
     *
     * <p>Reemplaza al {@code invalidateAll()} que tenía la versión en memoria:
     * sobre un almacén distribuido y compartido, vaciar "todo" significa un
     * {@code SCAN} por prefijo o un {@code FLUSHDB}, y ninguno de los dos es
     * una operación que este decorador deba poder disparar contra una base que
     * comparte con los demás caches.
     */
    public void invalidate(AirportCode code) {
        if (code != null) {
            cache.evict(CacheKeys.CITY_PREFIX + code.value());
        }
    }

    /**
     * Política de vencimiento del cache del catálogo.
     *
     * @param positive    cuánto vale un "existe" antes de revalidarlo
     * @param negative    cuánto vale un "no existe"; más corto a propósito
     * @param staleWindow cuánto más allá de la frescura se conserva la entrada
     *                    para poder servirla si el origen falla
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
     * Lo que se guarda: el resultado y hasta cuándo se lo considera fresco.
     *
     * <p>El instante va adentro del valor —y no se deduce del TTL del
     * almacén— porque la frescura y la permanencia son dos cosas distintas:
     * la entrada sobrevive a su frescura justamente para poder servirse si el
     * origen se cae.
     *
     * <p>El formato es {@code true@1760000000000}: texto plano, sin Jackson.
     * Un valor ilegible —por un cambio de formato entre versiones desplegadas
     * a la vez— se trata como un miss, no como un error.
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
