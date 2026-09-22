package com.edteam.reservations.infrastructure.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Almacén sobre Redis: el cache compartido entre instancias.
 *
 * <p>Es lo que resuelve el límite del fallback en memoria. Con N instancias,
 * un cache local significa N caches fríos y N estampidas contra el origen en
 * cada deploy; con Redis hay uno solo, que además sobrevive a los reinicios.
 *
 * <h2>Un Redis caído no puede tumbar el servicio</h2>
 * Toda operación está envuelta: cualquier excepción del cliente se loguea y se
 * traga. Una lectura fallida es indistinguible de un miss, y una escritura
 * fallida es un miss futuro. En los dos casos el pedido sigue contra el
 * origen, que es como funcionaba el sistema antes de que existiera el cache.
 *
 * <p>Esto es también el motivo por el que {@code management.health.redis} está
 * apagado: el cache es opcional por diseño, así que su caída no debe marcar la
 * aplicación como {@code DOWN} y sacarla de rotación.
 *
 * <p>No define timeouts propios: los del transporte se configuran en
 * {@code spring.data.redis.timeout}, en un solo lugar, igual que se decidió
 * para el cliente del catálogo.
 */
public final class RedisCacheStore implements CacheStore {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheStore.class);

    private final StringRedisTemplate redis;
    private final Consumer<String> onFailure;

    /**
     * @param onFailure qué hacer cuando Redis no responde; recibe el nombre de
     *                  la operación. Se inyecta para que la métrica de errores
     *                  la lleve el decorador instrumentado y este almacén no
     *                  dependa de Micrometer.
     */
    public RedisCacheStore(StringRedisTemplate redis, Consumer<String> onFailure) {
        this.redis = Objects.requireNonNull(redis, "El template de Redis es obligatorio");
        this.onFailure = Objects.requireNonNull(onFailure, "El callback de fallo es obligatorio");
    }

    @Override
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key));
        } catch (RuntimeException e) {
            degrade("get", key, e);
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (RuntimeException e) {
            degrade("put", key, e);
        }
    }

    @Override
    public void evict(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            // Es el caso más incómodo: una invalidación perdida deja una
            // entrada vieja hasta que venza. Por eso toda clave invalidable
            // tiene además un TTL corto, que acota el daño a esa ventana.
            degrade("evict", key, e);
        }
    }

    private void degrade(String operation, String key, RuntimeException e) {
        log.warn("Redis no respondió al {} de '{}': se sigue contra el origen ({})",
                operation, key, e.getMessage());
        onFailure.accept(operation);
    }
}
