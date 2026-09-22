package com.edteam.reservations.infrastructure.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Almacén en memoria del proceso. Es el fallback cuando no hay Redis.
 *
 * <p>Existe por una restricción explícita: la aplicación tiene que arrancar y
 * los tests correr sin Redis disponible. Con este almacén, el cache sigue
 * funcionando —local al proceso, como el {@code ConcurrentHashMap} que tenía
 * antes {@code CachingAirportCatalog}— y activar Redis es un cambio de
 * configuración, no de código.
 *
 * <p>Su límite es conocido y hay que tenerlo presente: con N instancias hay N
 * caches fríos. Cada deploy y cada escalado horizontal dispara una estampida
 * contra el origen justo cuando el sistema está más frágil. Por eso el
 * objetivo del entorno productivo es Redis y esto es el plan B.
 *
 * <h2>Cota de memoria</h2>
 * El mapa está acotado por {@code maxEntries}. Al llegar al tope se purgan
 * primero las entradas vencidas y, si eso no alcanza, se desalojan las de
 * vencimiento más próximo. Sin esa cota, un cliente con un bug que consulta
 * códigos aleatorios haría crecer el mapa hasta el {@code OutOfMemoryError}:
 * el cache dejaría de ser una optimización para pasar a ser una falla.
 */
public final class InMemoryCacheStore implements CacheStore {

    /** Fracción del mapa que se desaloja de una vez, para no desalojar en cada escritura. */
    private static final double EVICTION_RATIO = 0.1;

    private final Clock clock;
    private final int maxEntries;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong capacityEvictions = new AtomicLong();

    public InMemoryCacheStore(Clock clock, int maxEntries) {
        this.clock = Objects.requireNonNull(clock, "El clock es obligatorio");
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("El máximo de entradas debe ser positivo");
        }
        this.maxEntries = maxEntries;
    }

    @Override
    public Optional<String> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpiredAt(clock.instant())) {
            // Se borra al leerla: sin esto, una clave que nadie vuelve a pedir
            // ocuparía lugar hasta que el desalojo por capacidad la alcance.
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        Objects.requireNonNull(key, "La clave es obligatoria");
        Objects.requireNonNull(value, "El valor es obligatorio");
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        if (entries.size() >= maxEntries && !entries.containsKey(key)) {
            makeRoom();
        }
        entries.put(key, new Entry(value, clock.instant().plus(ttl)));
    }

    @Override
    public void evict(String key) {
        entries.remove(key);
    }

    @Override
    public OptionalLong estimatedSize() {
        return OptionalLong.of(entries.size());
    }

    /** Cantidad de entradas desalojadas por falta de lugar, no por vencimiento. */
    public long capacityEvictions() {
        return capacityEvictions.get();
    }

    /**
     * Libera lugar: primero lo vencido, después lo que está por vencer.
     *
     * <p>No es un LRU. Desalojar por vencimiento más próximo es más barato
     * —no hay que registrar accesos— y acá alcanza: lo que se guarda son
     * escalares con TTL corto, así que la entrada más vieja es casi siempre la
     * menos útil. Si el tope se alcanzara seguido, la respuesta correcta es
     * activar Redis, no afinar la política de desalojo de un plan B.
     */
    private void makeRoom() {
        Instant now = clock.instant();
        int before = entries.size();
        entries.entrySet().removeIf(entry -> entry.getValue().isExpiredAt(now));
        capacityEvictions.addAndGet(before - entries.size());

        if (entries.size() < maxEntries) {
            return;
        }

        int toEvict = Math.max(1, (int) (maxEntries * EVICTION_RATIO));
        List<String> oldest = entries.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().expiresAt()))
                .limit(toEvict)
                .map(Map.Entry::getKey)
                .toList();
        oldest.forEach(entries::remove);
        capacityEvictions.addAndGet(oldest.size());
    }

    private record Entry(String value, Instant expiresAt) {

        boolean isExpiredAt(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }
}
