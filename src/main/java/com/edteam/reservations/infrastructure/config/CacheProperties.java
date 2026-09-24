package com.edteam.reservations.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Parámetros del cache.
 *
 * <p>La conexión a Redis no se configura acá: son las propiedades estándar
 * {@code spring.data.redis.*}. Lo único propio es el interruptor
 * {@code redis.enabled}, que decide si el almacén es el distribuido o el
 * fallback en memoria. Apagado, la aplicación arranca y los tests corren sin
 * Redis, igual que arrancan sin el catálogo externo.
 *
 * <h2>Los TTL, uno por uno</h2>
 * Ninguno está elegido por costumbre; cada uno sale de qué pasa si el valor
 * queda viejo:
 *
 * <ul>
 *   <li>{@code reservation-count-ttl} (45 s): el total de una paginación es
 *       una pista para la interfaz, no un invariante. Que diga 1.204 cuando
 *       son 1.205 durante medio minuto no rompe nada, y un TTL corto hace
 *       innecesaria cualquier invalidación activa —que además sería
 *       imposible: no hay forma de saber qué filtros matchea una reserva
 *       nueva sin recorrer las claves.</li>
 *   <li>{@code reservation-version-ttl} (60 s): acá sí hay invalidación
 *       activa en cada escritura, y el TTL es la red de seguridad para cuando
 *       se pierda —proceso caído entre el commit y el borrado, o Redis sin
 *       responder—. Un minuto acota el daño; sin TTL, una invalidación
 *       perdida sería permanente.</li>
 * </ul>
 *
 * <h2>La cota de memoria</h2>
 * {@code max-entries} acota el fallback en memoria, que es el único almacén
 * que no tiene una política de desalojo propia. Contra Redis la cota la pone
 * el proveedor: en un free tier hay que dejarlo en {@code allkeys-lru}, que
 * con estos TTL y estos tamaños —decenas de KB el catálogo, cientos de KB los
 * totales, ~90 B por reserva caliente— no debería llegar a activarse.
 *
 * @param maxEntries            tope de entradas del almacén en memoria
 * @param reservationCountTtl   vigencia del total del listado
 * @param reservationVersionTtl vigencia de la versión de una reserva
 * @param redis                 configuración del almacén distribuido
 */
@ConfigurationProperties(prefix = "reservations.cache")
public record CacheProperties(Integer maxEntries,
                              Integer cityFallbackMaxEntries,
                              Duration reservationCountTtl,
                              Duration reservationVersionTtl,
                              Redis redis,
                              CircuitBreakerProperties circuitBreaker) {

    /**
     * Umbrales por defecto del circuito de Redis.
     *
     * <p>Ventana 100 / mínimo 30: es la dependencia con más llamadas por
     * pedido, así que 30 llamadas son un par de pedidos y la ventana de 100
     * sigue representando pocos segundos de tráfico.
     *
     * <p>Llamada lenta a los 150 ms contra un timeout de 200 ms: una lectura
     * sana de Redis en la misma red es de un dígito en milisegundos, así que
     * 150 ms ya es la zona donde el cache dejó de ser un atajo.
     *
     * <p>Abierto 10 s: un failover de Redis o un reinicio de contenedor tarda
     * del orden de 5 a 15 s. Menos sería probar contra algo que todavía no
     * volvió, y cada ronda de prueba cuesta cinco timeouts.
     */
    private static final CircuitBreakerProperties CIRCUIT_DEFAULTS = new CircuitBreakerProperties(
            true, 100, 30, 50, Duration.ofMillis(150), 60,
            Duration.ofSeconds(10), 5, true, Duration.ofMinutes(10));

    public CacheProperties {
        if (maxEntries == null || maxEntries <= 0) {
            maxEntries = 50_000;
        }
        if (cityFallbackMaxEntries == null || cityFallbackMaxEntries <= 0) {
            cityFallbackMaxEntries = 10_000;
        }
        if (reservationCountTtl == null) {
            reservationCountTtl = Duration.ofSeconds(45);
        }
        if (reservationVersionTtl == null) {
            reservationVersionTtl = Duration.ofSeconds(60);
        }
        if (redis == null) {
            redis = new Redis(false);
        }
        circuitBreaker = CircuitBreakerProperties.merge(circuitBreaker, CIRCUIT_DEFAULTS);
    }

    /** @param enabled {@code true} para usar Redis; {@code false} deja el cache en memoria */
    public record Redis(boolean enabled) {
    }
}
