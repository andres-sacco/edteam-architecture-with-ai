package com.edteam.reservations.infrastructure.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * Almacén que nunca guarda nada, como si el cache estuviera caído.
 *
 * <p>Es el doble que verifica la restricción más importante del diseño: una
 * caída del cache no puede tumbar el servicio ni cambiar lo que ve el cliente.
 * Cumple el contrato de {@link CacheStore} —no propaga errores, todo es un
 * miss—, que es exactamente en lo que degrada {@code RedisCacheStore} cuando
 * Redis no responde.
 */
public final class FailingCacheStore implements CacheStore {

    private int gets;
    private int puts;

    @Override
    public Optional<String> get(String key) {
        gets++;
        return Optional.empty();
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        puts++;
    }

    @Override
    public void evict(String key) {
        // Nada que borrar: nunca se guardó.
    }

    public int gets() {
        return gets;
    }

    public int puts() {
        return puts;
    }
}
