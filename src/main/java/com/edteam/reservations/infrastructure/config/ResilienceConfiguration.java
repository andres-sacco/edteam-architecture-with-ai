package com.edteam.reservations.infrastructure.config;

import com.edteam.reservations.infrastructure.adapter.in.rest.DegradationHeaderFilter;
import com.edteam.reservations.infrastructure.resilience.Circuit;
import com.edteam.reservations.infrastructure.resilience.DegradationRecorder;
import com.edteam.reservations.infrastructure.resilience.Failures;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * El cableado de la resiliencia, en un solo lugar.
 *
 * <p>Los circuitos y el bulkhead se arman a mano y no con las anotaciones de
 * {@code resilience4j-spring-boot3}, que directamente no está en el
 * {@code pom.xml}. La razón es la misma por la que el cache es un decorador y
 * no un {@code @Cacheable}: con anotaciones, la política de resiliencia se
 * aplica donde alguien escriba la anotación —y el lugar más cómodo para
 * escribirla es un servicio de aplicación, que es exactamente donde no puede
 * estar—. Cableados a mano, el orden de los decoradores es explícito, se lee
 * de arriba abajo y una regla de ArchUnit puede impedir que la librería se
 * filtre hacia adentro.
 *
 * <h2>Nombres de los circuitos</h2>
 * {@code catalog}, {@code redis} y {@code broker}. Son las etiquetas con las
 * que salen las series de Micrometer, así que cambiarlos rompe los paneles y
 * las alertas: son parte del contrato de observabilidad.
 */
@Configuration
@EnableConfigurationProperties({AirportCatalogProperties.class, CacheProperties.class, MessagingProperties.class})
public class ResilienceConfiguration {

    public static final String CATALOG_CIRCUIT = "catalog";
    public static final String REDIS_CIRCUIT = "redis";
    public static final String BROKER_CIRCUIT = "broker";
    public static final String CATALOG_BULKHEAD = "catalog";

    /**
     * Un único registro para los tres circuitos: es lo que permite que el
     * binder de Micrometer los publique todos con una sola línea y que
     * aparezca uno nuevo sin tocar la observabilidad.
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults();
    }

    @Bean
    public BulkheadRegistry bulkheadRegistry() {
        return BulkheadRegistry.ofDefaults();
    }

    @Bean
    public Circuit catalogCircuit(AirportCatalogProperties properties,
                                  CircuitBreakerRegistry registry,
                                  Clock clock) {
        return circuit(CATALOG_CIRCUIT, properties.circuitBreaker(), Failures::catalog, registry, clock);
    }

    @Bean
    public Circuit redisCircuit(CacheProperties properties,
                                CircuitBreakerRegistry registry,
                                Clock clock) {
        return circuit(REDIS_CIRCUIT, properties.circuitBreaker(), Failures::cache, registry, clock);
    }

    @Bean
    public Circuit brokerCircuit(MessagingProperties properties,
                                 CircuitBreakerRegistry registry,
                                 Clock clock) {
        return circuit(BROKER_CIRCUIT, properties.circuitBreaker(), Failures::broker, registry, clock);
    }

    private static Circuit circuit(String name,
                                   CircuitBreakerProperties properties,
                                   java.util.function.Function<Throwable,
                                           com.edteam.reservations.infrastructure.resilience.FailureClassification>
                                           classifier,
                                   CircuitBreakerRegistry registry,
                                   Clock clock) {
        return Boolean.TRUE.equals(properties.enabled())
                ? Circuit.of(name, properties, classifier, registry, clock)
                : Circuit.disabled(name, registry, clock);
    }

    /**
     * El bulkhead del catálogo. Espera cero: si no hay permiso, se rechaza en
     * el acto. Una cola de espera acá sería la misma cola que el bulkhead
     * quiere evitar, sólo que del lado nuestro y sin cota visible.
     */
    @Bean
    public Bulkhead catalogBulkhead(AirportCatalogProperties properties, BulkheadRegistry registry) {
        return registry.bulkhead(CATALOG_BULKHEAD, BulkheadConfig.custom()
                .maxConcurrentCalls(properties.bulkhead().maxConcurrentCalls())
                .maxWaitDuration(Duration.ZERO)
                .build());
    }

    /**
     * Publica el estado de cada circuito, sus transiciones y las llamadas por
     * resultado —incluidas las {@code not_permitted}, que son las que el
     * circuito ahorró— más la ocupación del bulkhead.
     *
     * <p>Sin esto no hay forma de enterarse de que el sistema está degradado
     * sin entrar al servidor a leer logs, que es lo mismo que no enterarse.
     */
    @Bean
    public MeterBinder resilienceMetrics(CircuitBreakerRegistry circuitBreakers, BulkheadRegistry bulkheads) {
        return registry -> {
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakers).bindTo(registry);
            TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheads).bindTo(registry);
        };
    }

    @Bean
    public DegradationRecorder degradationRecorder(MeterRegistry registry) {
        return new DegradationRecorder(registry);
    }

    /**
     * El filtro del header de degradación, fuera de la cadena de seguridad y
     * antes que ella, igual que el del correlation id: un {@code 503} por
     * catálogo caído también tiene que llevar la marca.
     */
    @Bean
    public FilterRegistrationBean<DegradationHeaderFilter> degradationHeaderFilter() {
        FilterRegistrationBean<DegradationHeaderFilter> registration =
                new FilterRegistrationBean<>(new DegradationHeaderFilter());
        registration.setOrder(Integer.MIN_VALUE + 1);
        return registration;
    }
}
