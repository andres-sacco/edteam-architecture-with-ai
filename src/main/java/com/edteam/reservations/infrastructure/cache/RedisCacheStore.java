package com.edteam.reservations.infrastructure.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * El almacén contra Redis. Sólo eso: habla con Redis y
 * <strong>relanza</strong> lo que Redis le tire.
 *
 * <p>Antes atrapaba toda {@code RuntimeException} y devolvía vacío, y eso
 * mezclaba dos responsabilidades en una clase: <em>clasificar</em> el error y
 * <em>decidir</em> qué hacer con él. La consecuencia concreta era que el
 * circuito que va encima no podía abrirse nunca por fallo: Redis caído duro
 * —conexión rechazada— responde rápido, así que no era ni un fallo contado ni
 * una llamada lenta, y el circuito quedaba {@code CLOSED} para siempre
 * mientras se seguían pagando 200 ms por operación. El circuito más caro de
 * construir era el que menos servía.
 *
 * <p>Ahora la degradación al origen la aplica
 * {@link CircuitBreakingCacheStore}, que es quien necesita ver el fallo para
 * contarlo. Esta clase nunca se usa suelta: siempre va envuelta por ese
 * decorador, y el contrato de «nunca falla» se cumple ahí.
 */
public final class RedisCacheStore implements CacheStore {

    private final StringRedisTemplate redis;

    public RedisCacheStore(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "El template de Redis es obligatorio");
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }

    /**
     * Una sola ida y vuelta para todas las claves ({@code MGET}). Es lo que
     * hace que leer las once ciudades de un itinerario cueste un timeout y no
     * once.
     */
    @Override
    public Map<String, String> getAll(Collection<String> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        List<String> ordered = List.copyOf(keys);
        List<String> values = redis.opsForValue().multiGet(ordered);
        Map<String, String> found = new LinkedHashMap<>();
        if (values == null) {
            return found;
        }
        for (int i = 0; i < ordered.size() && i < values.size(); i++) {
            String value = values.get(i);
            if (value != null) {
                found.put(ordered.get(i), value);
            }
        }
        return found;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redis.opsForValue().set(key, value, ttl);
    }

    @Override
    public void evict(String key) {
        redis.delete(key);
    }
}
