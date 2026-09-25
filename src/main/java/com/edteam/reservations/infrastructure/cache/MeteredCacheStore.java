package com.edteam.reservations.infrastructure.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;

/**
 * Decorador que publica las métricas de un almacén.
 *
 * <p>Un cache que nadie mide es un acto de fe: no hay forma de saber si sirve,
 * ni de enterarse el día que deja de servir —un TTL mal elegido, una clave con
 * más cardinalidad de la prevista o una invalidación demasiado agresiva se ven
 * todos igual desde afuera, como "está un poco más lento".
 *
 * <p>Las series que publica, todas con la etiqueta {@code cache}:
 * <ul>
 *   <li>{@code reservations.cache.gets} con {@code result=hit|miss} — la tasa
 *       de aciertos, que es la métrica que decide si el cache vale la pena;</li>
 *   <li>{@code reservations.cache.puts} — cuánto se escribe, para detectar una
 *       clave que nunca acierta porque su cardinalidad explotó;</li>
 *   <li>{@code reservations.cache.evictions} — invalidaciones explícitas;</li>
 *   <li>{@code reservations.cache.errors} con {@code operation} — cuándo el
 *       almacén está degradando al origen;</li>
 *   <li>{@code reservations.cache.size} — entradas vivas, sólo donde el
 *       almacén las conoce (el fallback en memoria).</li>
 * </ul>
 *
 * <p>Es un decorador, y no instrumentación adentro de cada almacén, por la
 * misma razón que el cache del catálogo es un decorador: la decisión queda
 * visible en el cableado y cada pieza se puede testear sin la otra.
 */
public final class MeteredCacheStore implements CacheStore {

    public static final String GETS = "reservations.cache.gets";
    public static final String PUTS = "reservations.cache.puts";
    public static final String EVICTIONS = "reservations.cache.evictions";
    public static final String ERRORS = "reservations.cache.errors";
    public static final String SIZE = "reservations.cache.size";

    private final CacheStore delegate;
    private final Counter hits;
    private final Counter misses;
    private final Counter puts;
    private final Counter evictions;

    public MeteredCacheStore(CacheStore delegate, String name, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "El delegado es obligatorio");
        Objects.requireNonNull(name, "El nombre del cache es obligatorio");
        Objects.requireNonNull(registry, "El registro de métricas es obligatorio");

        this.hits = Counter.builder(GETS)
                .tags(Tags.of("cache", name, "result", "hit"))
                .description("Lecturas del cache resueltas con un valor vigente")
                .register(registry);
        this.misses = Counter.builder(GETS)
                .tags(Tags.of("cache", name, "result", "miss"))
                .description("Lecturas del cache que tuvieron que ir al origen")
                .register(registry);
        this.puts = Counter.builder(PUTS)
                .tag("cache", name)
                .description("Escrituras en el cache")
                .register(registry);
        this.evictions = Counter.builder(EVICTIONS)
                .tag("cache", name)
                .description("Invalidaciones explícitas de una clave")
                .register(registry);

        delegate.estimatedSize()
                .ifPresent(ignored -> registry.gauge(
                        SIZE,
                        Tags.of("cache", name),
                        delegate,
                        store -> store.estimatedSize().orElse(0L)));
    }

    /**
     * Contador de fallos del almacén, para pasarle a {@link RedisCacheStore}.
     *
     * <p>Se construye aparte —y antes— del decorador porque el almacén que
     * falla está <em>debajo</em> de él: si el contador viviera en el decorador,
     * cablearlos sería un ciclo. Que sea un {@code Consumer} mantiene además a
     * {@code RedisCacheStore} sin dependencia de Micrometer.
     */
    public static Consumer<String> failureMeter(String name, MeterRegistry registry) {
        return operation -> Counter.builder(ERRORS)
                .tags(Tags.of("cache", name, "operation", operation))
                .description("Operaciones que el almacén no pudo resolver y degradaron al origen")
                .register(registry)
                .increment();
    }

    @Override
    public Optional<String> get(String key) {
        Optional<String> value = delegate.get(key);
        if (value.isPresent()) {
            hits.increment();
        } else {
            misses.increment();
        }
        return value;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        delegate.put(key, value, ttl);
        puts.increment();
    }

    @Override
    public void evict(String key) {
        delegate.evict(key);
        evictions.increment();
    }

    @Override
    public OptionalLong estimatedSize() {
        return delegate.estimatedSize();
    }
}
