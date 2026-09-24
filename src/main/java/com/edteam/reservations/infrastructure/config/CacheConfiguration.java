package com.edteam.reservations.infrastructure.config;

import com.edteam.reservations.infrastructure.adapter.in.rest.ReservationVersionCache;
import com.edteam.reservations.infrastructure.adapter.out.persistence.CachingReservationSearchQuery;
import com.edteam.reservations.infrastructure.adapter.out.persistence.ReservationSearchJpaQuery;
import com.edteam.reservations.infrastructure.adapter.out.persistence.ReservationSearchQuery;
import com.edteam.reservations.infrastructure.cache.CacheKeys;
import com.edteam.reservations.infrastructure.cache.CacheStore;
import com.edteam.reservations.infrastructure.cache.CircuitBreakingCacheStore;
import com.edteam.reservations.infrastructure.cache.InMemoryCacheStore;
import com.edteam.reservations.infrastructure.cache.MeteredCacheStore;
import com.edteam.reservations.infrastructure.cache.RedisCacheStore;
import com.edteam.reservations.infrastructure.resilience.Circuit;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.util.function.Consumer;

/**
 * Cableado del cache: dónde vive y quién lo usa.
 *
 * <p>Está en un archivo aparte de {@link AdapterConfiguration} porque describe
 * una decisión distinta: aquél compone los adaptadores de salida, éste dice
 * qué se cachea. Las dos decisiones se leen mejor separadas, y ésta es la que
 * va a cambiar cuando aparezca un cuello de botella nuevo.
 *
 * <h2>Un almacén por uso, no uno compartido</h2>
 * Los tres caches tienen su propio {@link CacheStore}. No es por aislamiento
 * —las claves ya están prefijadas y no se pisan— sino por observabilidad: con
 * un almacén por uso, las métricas salen etiquetadas y se puede ver que el
 * catálogo acierta el 99% mientras el total del listado acierta el 40%. Con un
 * almacén único, las dos series se suman y no se puede diagnosticar ninguna.
 *
 * <h2>Redis o memoria, por configuración</h2>
 * El interruptor es {@code reservations.cache.redis.enabled}. Apagado, cada
 * uso recibe un {@link InMemoryCacheStore} acotado: la aplicación arranca y
 * los tests corren sin Redis, que es una restricción explícita del diseño.
 * Encendido, el mismo código pasa a compartir el cache entre instancias sin
 * que ningún decorador ni ningún caso de uso se entere.
 *
 * <p>El {@link StringRedisTemplate} se pide con un {@link ObjectProvider}: si
 * alguien enciende el interruptor en un contexto donde Spring no
 * autoconfiguró Redis, se registra el problema y se cae al fallback, en lugar
 * de romper el arranque por un cache.
 */
@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CacheConfiguration.class);

    /** Nombres de los caches: son la etiqueta {@code cache} de las métricas. */
    public static final String CITY_CATALOG_CACHE = "city-catalog";
    public static final String RESERVATION_COUNT_CACHE = "reservation-count";
    public static final String RESERVATION_VERSION_CACHE = "reservation-version";

    /** Almacén del maestro de ciudades (P0). */
    @Bean
    public CacheStore cityCatalogCacheStore(CacheProperties properties,
                                            ObjectProvider<StringRedisTemplate> redis,
                                            MeterRegistry registry,
                                            Circuit redisCircuit,
                                            Clock clock) {
        // El único cache con fallback local en caliente, y sólo para el
        // prefijo 'catalog:city:'. Ver CircuitBreakingCacheStore: las claves
        // de versión NO pueden caer a memoria porque se invalidan
        // activamente, y una copia por instancia daría ETags incoherentes.
        InMemoryCacheStore cityFallback =
                new InMemoryCacheStore(clock, properties.cityFallbackMaxEntries());
        return cacheStore(CITY_CATALOG_CACHE, properties, redis, registry, redisCircuit, clock,
                cityFallback, CacheKeys.CITY_PREFIX);
    }

    /** Almacén del total del listado (P1). */
    @Bean
    public CacheStore reservationCountCacheStore(CacheProperties properties,
                                                 ObjectProvider<StringRedisTemplate> redis,
                                                 MeterRegistry registry,
                                                 Circuit redisCircuit,
                                                 Clock clock) {
        return cacheStore(RESERVATION_COUNT_CACHE, properties, redis, registry, redisCircuit, clock, null, null);
    }

    /** Almacén de la versión de una reserva (P2). */
    @Bean
    public CacheStore reservationVersionCacheStore(CacheProperties properties,
                                                   ObjectProvider<StringRedisTemplate> redis,
                                                   MeterRegistry registry,
                                                   Circuit redisCircuit,
                                                   Clock clock) {
        return cacheStore(RESERVATION_VERSION_CACHE, properties, redis, registry, redisCircuit, clock, null, null);
    }

    /**
     * Decorador del listado, declarado {@code @Primary} porque
     * {@link ReservationSearchJpaQuery} también es candidato a
     * {@link ReservationSearchQuery}: el adaptador tiene que recibir el que
     * cachea, y el de la base queda accesible por su tipo concreto.
     */
    @Bean
    @Primary
    public ReservationSearchQuery cachingReservationSearchQuery(ReservationSearchJpaQuery delegate,
                                                                CacheStore reservationCountCacheStore,
                                                                CacheProperties properties) {
        return new CachingReservationSearchQuery(
                delegate, reservationCountCacheStore, properties.reservationCountTtl());
    }

    /** Cache de versiones que usa el adaptador REST para responder {@code 304}. */
    @Bean
    public ReservationVersionCache reservationVersionCache(CacheStore reservationVersionCacheStore,
                                                           CacheProperties properties) {
        return new ReservationVersionCache(reservationVersionCacheStore, properties.reservationVersionTtl());
    }

    /**
     * El orden de los decoradores del cache, y por qué:
     *
     * <pre>
     * MeteredCacheStore              las métricas se siguen viendo con el circuito abierto
     * └── CircuitBreakingCacheStore  circuito + degradación + L1 por prefijo
     *     └── RedisCacheStore        habla con Redis y relanza
     * </pre>
     *
     * <p>El medidor queda <strong>por fuera</strong> del circuito a propósito:
     * adentro, con el circuito abierto dejaríamos de publicar
     * {@code reservations.cache.gets} y el panel mostraría silencio en lugar
     * de degradación — que es la diferencia entre «no pasa nada» y «no nos
     * estamos enterando».
     */
    private static CacheStore cacheStore(String name,
                                         CacheProperties properties,
                                         ObjectProvider<StringRedisTemplate> redis,
                                         MeterRegistry registry,
                                         Circuit redisCircuit,
                                         Clock clock,
                                         CacheStore localFallback,
                                         String localPrefix) {
        Consumer<String> failures = MeteredCacheStore.failureMeter(name, registry);
        CacheStore store = backingStore(name, properties, redis, clock);
        if (store instanceof RedisCacheStore) {
            store = new CircuitBreakingCacheStore(store, redisCircuit, failures, localFallback, localPrefix);
        }
        return new MeteredCacheStore(store, name, registry);
    }

    private static CacheStore backingStore(String name,
                                           CacheProperties properties,
                                           ObjectProvider<StringRedisTemplate> redis,
                                           Clock clock) {
        if (!properties.redis().enabled()) {
            log.info("Cache '{}': en memoria, hasta {} entradas "
                            + "(no hay 'reservations.cache.redis.enabled')",
                    name, properties.maxEntries());
            return new InMemoryCacheStore(clock, properties.maxEntries());
        }

        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            log.warn("Cache '{}': se pidió Redis pero no hay StringRedisTemplate en el contexto; "
                    + "se usa el cache en memoria", name);
            return new InMemoryCacheStore(clock, properties.maxEntries());
        }

        log.info("Cache '{}': Redis, con circuito", name);
        return new RedisCacheStore(template);
    }
}
