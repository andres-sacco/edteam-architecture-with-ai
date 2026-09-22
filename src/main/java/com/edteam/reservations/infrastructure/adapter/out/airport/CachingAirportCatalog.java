package com.edteam.reservations.infrastructure.adapter.out.airport;

import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.domain.model.AirportCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decorador con cache sobre el maestro de aeropuertos.
 *
 * <p>Motivo: el maestro se consulta dos veces por cada creación o modificación
 * de reserva y su contenido es casi estático. Con el volumen de concurrencia
 * esperado, ir al origen en cada llamada agrega latencia al flujo de reserva y
 * —si el maestro termina siendo un proveedor externo— lo convierte en un
 * cuello de botella y en un punto de falla.
 *
 * <p>Es un decorador y no una anotación {@code @Cacheable} para que la decisión
 * de cachear quede explícita en el grafo de dependencias, sea testeable sin
 * levantar el contexto de Spring y pueda reemplazarse por un cache distribuido
 * sin tocar el adaptador que consulta el origen.
 *
 * <p>Se cachean también las respuestas negativas, con el mismo TTL: si no,
 * pedidos con códigos inexistentes —típicos de un cliente con un bug— pasarían
 * siempre al origen.
 */
public class CachingAirportCatalog implements AirportCatalogPort {

    private static final Logger log = LoggerFactory.getLogger(CachingAirportCatalog.class);

    private final AirportCatalogPort delegate;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public CachingAirportCatalog(AirportCatalogPort delegate, Duration ttl, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "El delegado es obligatorio");
        this.ttl = Objects.requireNonNull(ttl, "El TTL es obligatorio");
        this.clock = Objects.requireNonNull(clock, "El clock es obligatorio");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("El TTL debe ser positivo");
        }
    }

    @Override
    public boolean exists(AirportCode code) {
        if (code == null) {
            return false;
        }

        Instant now = clock.instant();
        CacheEntry cached = cache.get(code.value());
        if (cached != null && !cached.isExpired(now)) {
            return cached.exists();
        }

        boolean exists = delegate.exists(code);
        cache.put(code.value(), new CacheEntry(exists, now.plus(ttl)));
        log.trace("Aeropuerto {} resuelto contra el origen: exists={}", code, exists);
        return exists;
    }

    /** Invalida el cache completo. Útil ante un cambio conocido del maestro. */
    public void invalidateAll() {
        cache.clear();
    }

    private record CacheEntry(boolean exists, Instant expiresAt) {

        boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }
}
