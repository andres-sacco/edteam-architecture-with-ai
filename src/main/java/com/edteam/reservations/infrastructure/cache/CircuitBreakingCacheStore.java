package com.edteam.reservations.infrastructure.cache;

import com.edteam.reservations.infrastructure.logging.LogFields;
import com.edteam.reservations.infrastructure.logging.LogSanitizer;
import com.edteam.reservations.infrastructure.logging.Throwables;
import com.edteam.reservations.infrastructure.resilience.Circuit;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Convierte un almacén que falla en uno que degrada, y le pone un circuito.
 *
 * <p>Es el lugar donde ahora vive la decisión que antes estaba adentro de
 * {@code RedisCacheStore}: qué hacer cuando el cache no responde. Separarla
 * del almacén es lo que permite que el circuito exista de verdad — con el
 * almacén tragándose los errores, el circuito veía el 100 % de las llamadas
 * como exitosas.
 *
 * <p><strong>Por qué hay circuito para un componente opcional.</strong> No es
 * por corrección sino por latencia: con Redis caído, un {@code POST} hace
 * hasta once lecturas y once escrituras de ciudades más la versión, y cada una
 * paga el timeout de 200 ms. Son segundos de espera pura por un componente
 * cuyo aporte es ahorrar tiempo. Con el circuito abierto, cuestan cero.
 *
 * <p><strong>No hay reintentos, y no es un olvido</strong>: el «reintento» de
 * un cache es ir al origen, que es lo que se hace igual en un miss. Insistir
 * paga el timeout otra vez por un dato opcional.
 *
 * <h2>El fallback es por prefijo de clave, y la distinción importa</h2>
 * Las claves de ciudades caen a un almacén en memoria acotado; las demás, no.
 * La razón no es de tamaño: {@code rsv:ver:*} se invalida <em>activamente</em>
 * en cada escritura, y una copia por instancia no recibe esa invalidación. Con
 * N instancias, un {@code ETag} servido desde la memoria de A después de que B
 * modificó la reserva produce un {@code 412} sobre un {@code If-Match}
 * correcto —o peor, un {@code 304} sobre un recurso que cambió—. Un cache
 * degradado puede permitirse ser lento; no puede permitirse ser incoherente.
 * Para esas claves, el circuito abierto significa <strong>miss</strong>, que
 * siempre es correcto.
 *
 * <p>El L1 de ciudades se escribe <strong>siempre</strong>, no sólo en
 * emergencia. Si se poblara recién cuando Redis se cae, arrancaría vacío justo
 * en el momento en que hace falta: el primer pedido de cada ciudad —el que
 * importa— no encontraría nada, y una caída de Redis se llevaría puesto al
 * <em>stale-while-error</em> del catálogo, que es la única defensa del camino
 * del pedido.
 */
public final class CircuitBreakingCacheStore implements CacheStore {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakingCacheStore.class);

    /**
     * Marca de «ya avisé en este pedido».
     *
     * <p>El {@code WARN} de degradación era <b>por operación fallida</b>, y un
     * {@code POST} hace hasta 16 operaciones de cache: con Redis caído, eso
     * son ~1,6 M de líneas/día sobre los 100.000 pedidos del escenario de
     * volumen del diseño, o sea <b>doce veces el presupuesto diario completo</b>,
     * en un nivel encendido en producción. Un presupuesto que se rompe el día
     * que el log hace falta es un presupuesto que no existe: la cuota de
     * ingesta descarta líneas, y las que se descartan son las del incidente.
     *
     * <p>Se avisa una vez por pedido. La cuenta exacta de operaciones
     * degradadas sigue estando en {@code reservations.cache.errors}, que es
     * donde tiene que estar: es un número, no un relato. Es la misma decisión
     * que {@code OutboxDispatchScheduler} ya había tomado y documentado para
     * el tick salteado.
     */
    private static final ThreadLocal<Boolean> WARNED = new ThreadLocal<>();

    private final CacheStore remote;
    private final CacheStore localFallback;
    private final String localPrefix;
    private final Circuit circuit;
    private final Consumer<String> onFailure;

    /**
     * @param localFallback almacén en memoria para las claves de
     *                      {@code localPrefix}, o {@code null} si esta
     *                      instancia no tiene fallback local
     */
    public CircuitBreakingCacheStore(
            CacheStore remote,
            Circuit circuit,
            Consumer<String> onFailure,
            CacheStore localFallback,
            String localPrefix) {
        this.remote = Objects.requireNonNull(remote, "El almacén remoto es obligatorio");
        this.circuit = Objects.requireNonNull(circuit, "El circuito es obligatorio");
        this.onFailure = Objects.requireNonNull(onFailure, "El callback de fallo es obligatorio");
        this.localFallback = localFallback;
        this.localPrefix = localPrefix;
    }

    @Override
    public Optional<String> get(String key) {
        try {
            Optional<String> value = circuit.execute(() -> remote.get(key));
            if (value.isEmpty() && hasLocal(key)) {
                // Miss remoto con L1 poblado: pasa cuando Redis perdió la
                // clave (desalojo, reinicio) y la nuestra sigue vigente.
                return localFallback.get(key);
            }
            return value;
        } catch (CallNotPermittedException e) {
            return fromLocal("get", key);
        } catch (RuntimeException e) {
            degrade("get", key, e);
            return fromLocal("get", key);
        }
    }

    @Override
    public Map<String, String> getAll(Collection<String> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, String> found = circuit.execute(() -> remote.getAll(keys));
            if (localFallback == null) {
                return found;
            }
            Map<String, String> merged = new LinkedHashMap<>(found);
            for (String key : keys) {
                if (!merged.containsKey(key) && isLocal(key)) {
                    localFallback.get(key).ifPresent(value -> merged.put(key, value));
                }
            }
            return merged;
        } catch (CallNotPermittedException e) {
            return localAll(keys);
        } catch (RuntimeException e) {
            degrade("getAll", keys.iterator().next(), e);
            return localAll(keys);
        }
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        // El L1 se escribe primero y siempre: es lo que lo mantiene caliente
        // para el día que Redis no esté.
        if (isLocal(key)) {
            localFallback.put(key, value, ttl);
        }
        try {
            circuit.execute(() -> {
                remote.put(key, value, ttl);
                return null;
            });
        } catch (CallNotPermittedException e) {
            log.trace("Circuito del cache abierto: no se guarda '{}'", key);
        } catch (RuntimeException e) {
            degrade("put", key, e);
        }
    }

    @Override
    public void evict(String key) {
        if (isLocal(key)) {
            localFallback.evict(key);
        }
        try {
            circuit.execute(() -> {
                remote.evict(key);
                return null;
            });
        } catch (CallNotPermittedException e) {
            log.trace("Circuito del cache abierto: no se invalida '{}'", key);
        } catch (RuntimeException e) {
            // El caso más incómodo: una invalidación perdida deja una entrada
            // vieja hasta que venza. Por eso toda clave invalidable tiene
            // además un TTL corto, que acota el daño a esa ventana.
            degrade("evict", key, e);
        }
    }

    @Override
    public OptionalLong estimatedSize() {
        return localFallback != null ? localFallback.estimatedSize() : OptionalLong.empty();
    }

    private boolean isLocal(String key) {
        return localFallback != null && localPrefix != null && key != null && key.startsWith(localPrefix);
    }

    private boolean hasLocal(String key) {
        return isLocal(key) && localFallback.get(key).isPresent();
    }

    private Optional<String> fromLocal(String operation, String key) {
        if (!isLocal(key)) {
            return Optional.empty();
        }
        Optional<String> value = localFallback.get(key);
        if (value.isPresent()) {
            log.atTrace()
                    .addKeyValue(LogFields.EVENT, "cache.local_hit")
                    .addKeyValue(LogFields.OPERATION, operation)
                    .addKeyValue(LogFields.KEY, LogSanitizer.sanitize(key, 64))
                    .log("Cache degradado: se sirve desde memoria");
        }
        return value;
    }

    private Map<String, String> localAll(Collection<String> keys) {
        if (localFallback == null) {
            return Map.of();
        }
        Map<String, String> found = new LinkedHashMap<>();
        for (String key : keys) {
            if (isLocal(key)) {
                localFallback.get(key).ifPresent(value -> found.put(key, value));
            }
        }
        return found;
    }

    private void degrade(String operation, String key, RuntimeException e) {
        // La métrica siempre; la línea, una vez por pedido.
        onFailure.accept(operation);
        if (Boolean.TRUE.equals(WARNED.get())) {
            log.atTrace()
                    .addKeyValue(LogFields.EVENT, LogFields.CACHE_DEGRADED)
                    .addKeyValue(LogFields.OPERATION, operation)
                    .addKeyValue(LogFields.KEY, LogSanitizer.sanitize(key, 64))
                    .log("El cache no respondió: se sigue contra el origen");
            return;
        }
        WARNED.set(Boolean.TRUE);
        log.atWarn()
                .addKeyValue(LogFields.EVENT, LogFields.CACHE_DEGRADED)
                .addKeyValue(LogFields.OPERATION, operation)
                .addKeyValue(LogFields.KEY, LogSanitizer.sanitize(key, 64))
                .addKeyValue(LogFields.EXCEPTION_CLASS, Throwables.rootClassOf(e))
                .addKeyValue(LogFields.REASON, Throwables.reasonOf(e))
                .log("El cache no respondió: se sigue contra el origen");
    }

    /**
     * Rearma el aviso para el próximo pedido.
     *
     * <p>Lo llama {@code DegradationHeaderFilter} en su {@code finally}, que es
     * el mismo lugar donde se limpia la marca de degradación y por el mismo
     * motivo: con un pool de hilos, un estado que sobrevive al pedido le miente
     * al siguiente — acá, callando un aviso que sí correspondía.
     */
    public static void resetWarningScope() {
        WARNED.remove();
    }
}
