package com.edteam.reservations.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.edteam.reservations.infrastructure.config.CircuitBreakerProperties;
import com.edteam.reservations.infrastructure.resilience.Circuit;
import com.edteam.reservations.infrastructure.resilience.Failures;
import com.edteam.reservations.support.MutableClock;
import com.edteam.reservations.support.TestFixtures;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * El decorador que le devuelve al cache el contrato de «nunca falla» y, de
 * paso, hace que el circuito de Redis pueda abrirse.
 */
@DisplayName("CircuitBreakingCacheStore")
class CircuitBreakingCacheStoreTest {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String CITY_KEY = CacheKeys.CITY_PREFIX + "EZE";
    private static final String VERSION_KEY = CacheKeys.RESERVATION_VERSION_PREFIX + "42";

    private MutableClock clock;
    private BrokenRedis redis;
    private InMemoryCacheStore local;
    private List<String> failures;

    @BeforeEach
    void setUp() {
        clock = MutableClock.at(TestFixtures.NOW);
        redis = new BrokenRedis();
        local = new InMemoryCacheStore(clock, 100);
        failures = new ArrayList<>();
    }

    @Test
    @DisplayName("hallazgo 2: un Redis caído duro SÍ cuenta para el circuito, y lo abre")
    void aHardRedisOutageOpensTheCircuit() {
        // El hallazgo era éste: con el almacén tragándose los errores, Redis
        // caído duro —connection refused, que responde rápido— no era ni un
        // fallo contado ni una llamada lenta, así que el circuito quedaba
        // cerrado para siempre y se seguía pagando el viaje en cada
        // operación. El circuito más caro de construir era el que menos servía.
        Circuit circuit = circuit("redis-abre", 10, 5, 50);
        CacheStore store = new CircuitBreakingCacheStore(redis, circuit, failures::add, null, null);
        redis.broken(true);

        for (int i = 0; i < 5; i++) {
            assertThat(store.get("k")).isEmpty();
        }

        assertThat(circuit.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(failures).hasSize(5).containsOnly("get");
    }

    @Test
    @DisplayName("un error de Redis nunca se propaga: el pedido sigue contra el origen")
    void neverPropagatesACacheFailure() {
        Circuit circuit = circuit("redis-degrada", 100, 50, 50);
        CacheStore store = new CircuitBreakingCacheStore(redis, circuit, failures::add, null, null);
        redis.broken(true);

        assertThat(store.get("k")).isEmpty();
        assertThatCode(() -> store.put("k", "v", TTL)).doesNotThrowAnyException();
        assertThatCode(() -> store.evict("k")).doesNotThrowAnyException();
        assertThat(failures).containsExactly("get", "put", "evict");
    }

    @Test
    @DisplayName("con el circuito abierto las operaciones cuestan cero: no llegan a Redis")
    void anOpenCircuitSkipsRedisEntirely() {
        Circuit circuit = circuit("redis-corta", 10, 5, 50);
        CacheStore store = new CircuitBreakingCacheStore(redis, circuit, failures::add, null, null);
        redis.broken(true);

        for (int i = 0; i < 5; i++) {
            store.get("k");
        }
        int afterOpening = redis.calls();

        for (int i = 0; i < 20; i++) {
            store.get("k");
            store.put("k", "v", TTL);
        }

        assertThat(redis.calls()).isEqualTo(afterOpening);
    }

    @Test
    @DisplayName("hallazgo 3: el L1 de ciudades se escribe SIEMPRE, así está caliente cuando Redis se cae")
    void theCityFallbackIsWrittenThrough() {
        // Si el L1 se poblara recién cuando Redis se cae, arrancaría vacío
        // justo en el momento en que hace falta: el primer pedido de cada
        // ciudad —el que importa— no encontraría nada, y una caída de Redis se
        // llevaría puesto al stale-while-error del catálogo, que es la única
        // defensa del camino del pedido.
        Circuit circuit = circuit("redis-l1", 10, 5, 50);
        CacheStore store = new CircuitBreakingCacheStore(redis, circuit, failures::add, local, CacheKeys.CITY_PREFIX);

        store.put(CITY_KEY, "true@999", TTL);
        assertThat(local.get(CITY_KEY))
                .as("escrito en memoria aunque Redis estuviera perfectamente sano")
                .contains("true@999");

        redis.broken(true);
        assertThat(store.get(CITY_KEY))
                .as("y con Redis caído se sirve desde ahí: el stale-while-error sobrevive")
                .contains("true@999");
    }

    @Test
    @DisplayName("las claves de versión NUNCA caen a memoria: un ETag incoherente es peor que un miss")
    void versionKeysNeverFallBackToMemory() {
        // rsv:ver:* se invalida activamente en cada escritura, y una copia por
        // instancia no recibe esa invalidación. Con N instancias, un ETag
        // servido desde la memoria de A después de que B modificó la reserva
        // produce un 412 sobre un If-Match correcto —o peor, un 304 sobre un
        // recurso que cambió—. Un cache degradado puede ser lento; no puede
        // ser incoherente.
        Circuit circuit = circuit("redis-version", 10, 5, 50);
        CacheStore store = new CircuitBreakingCacheStore(redis, circuit, failures::add, local, CacheKeys.CITY_PREFIX);

        store.put(VERSION_KEY, "7", TTL);
        assertThat(local.get(VERSION_KEY)).isEmpty();

        redis.broken(true);
        assertThat(store.get(VERSION_KEY))
                .as("para estas claves, degradar significa miss, que siempre es correcto")
                .isEmpty();
    }

    @Test
    @DisplayName("getAll degrada al L1 para las ciudades y a vacío para el resto")
    void bulkReadFallsBackByPrefix() {
        Circuit circuit = circuit("redis-bulk", 10, 5, 50);
        CacheStore store = new CircuitBreakingCacheStore(redis, circuit, failures::add, local, CacheKeys.CITY_PREFIX);
        store.put(CITY_KEY, "true@999", TTL);
        store.put(VERSION_KEY, "7", TTL);

        redis.broken(true);

        assertThat(store.getAll(List.of(CITY_KEY, VERSION_KEY))).containsExactly(Map.entry(CITY_KEY, "true@999"));
    }

    private static Circuit circuit(String name, int window, int minimum, int failureRate) {
        CircuitBreakerProperties properties = new CircuitBreakerProperties(
                true,
                window,
                minimum,
                failureRate,
                Duration.ofSeconds(10),
                100,
                Duration.ofSeconds(30),
                2,
                true,
                Duration.ofHours(1));
        return Circuit.of(
                name,
                properties,
                Failures::cache,
                CircuitBreakerRegistry.ofDefaults(),
                MutableClock.at(TestFixtures.NOW));
    }

    /** Un Redis que se puede romper y despejar a voluntad, y que cuenta accesos. */
    private static final class BrokenRedis implements CacheStore {

        private final AtomicBoolean broken = new AtomicBoolean();
        private final AtomicInteger calls = new AtomicInteger();
        private final java.util.Map<String, String> values = new java.util.LinkedHashMap<>();

        void broken(boolean value) {
            broken.set(value);
        }

        int calls() {
            return calls.get();
        }

        private void touch() {
            calls.incrementAndGet();
            if (broken.get()) {
                throw new RedisConnectionFailureException("Redis caído");
            }
        }

        @Override
        public Optional<String> get(String key) {
            touch();
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public Map<String, String> getAll(Collection<String> keys) {
            touch();
            Map<String, String> found = new java.util.LinkedHashMap<>();
            keys.forEach(key -> {
                String value = values.get(key);
                if (value != null) {
                    found.put(key, value);
                }
            });
            return found;
        }

        @Override
        public void put(String key, String value, Duration ttl) {
            touch();
            values.put(key, value);
        }

        @Override
        public void evict(String key) {
            touch();
            values.remove(key);
        }
    }
}
