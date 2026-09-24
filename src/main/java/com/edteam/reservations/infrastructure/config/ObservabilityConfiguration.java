package com.edteam.reservations.infrastructure.config;

import com.edteam.reservations.infrastructure.logging.LogFields;
import com.edteam.reservations.infrastructure.logging.RequestLogFilter;
import com.edteam.reservations.infrastructure.observability.BusinessMetrics;
import com.edteam.reservations.infrastructure.observability.SecurityMetrics;
import com.edteam.reservations.infrastructure.security.SecurityProperties;
import com.edteam.reservations.infrastructure.security.crypto.PiiCipherProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumSet;

/**
 * El cableado de la observabilidad: etiquetas comunes, el filtro de acceso y
 * los dos medidores de configuración peligrosa.
 *
 * <p>Toda la observabilidad es infraestructura y este archivo es donde se ve:
 * el {@code domain} no loguea, la {@code application} escribe pares
 * clave/valor con {@code org.slf4j} sin saber a dónde van, y el formato, el
 * registry y las etiquetas se deciden acá y en {@code logback-spring.xml}.
 * {@code HexagonalArchitectureTest} lo sostiene con dos reglas.
 */
@Configuration
public class ObservabilityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityConfiguration.class);

    /**
     * {@code reservations.security.pii.dev_key} y
     * {@code reservations.security.jwt.dev_tokens}: dos gauges 0/1 que dicen si
     * el proceso está corriendo con secretos de desarrollo.
     *
     * <p>Existen por el hallazgo 19 de la auditoría. Hoy el aviso es un
     * {@code WARN} de arranque, que es un registro que aparece una vez en la
     * vida del proceso y que nadie está mirando cuando aparece. Un gauge se
     * puede alertar, y un entorno con la clave publicada del repositorio
     * cifrando documentos de pasajeros es exactamente lo que hay que alertar.
     */
    public static final String PII_DEV_KEY = "reservations.security.pii.dev_key";
    public static final String JWT_DEV_TOKENS = "reservations.security.jwt.dev_tokens";

    /**
     * 1 si el publicador REAL está cableado; 0 si el que está es el que sólo
     * loguea.
     *
     * <p>El §4.1 del diseño lo listaba como «existe» y no existía: la auditoría
     * lo dio por hecho al escribir el hallazgo 19 («el gauge existe, el WARN de
     * arranque existe, la alerta no») y el gauge también faltaba. Es la señal
     * de la pérdida silenciosa más perfecta del sistema: con el publicador
     * falso el outbox <b>drena y marca todo como despachado</b>, así que
     * {@code reservations.outbox.lag} se queda en 0, la alerta del lag nunca
     * dispara, y ningún pasajero recibe nada.
     */
    public static final String MESSAGING_ENABLED = "reservations.messaging.enabled";

    /**
     * Las tres etiquetas que van en <b>todo</b> medidor.
     *
     * <p>Sin ellas, dos entornos que escriben en el mismo Prometheus se mezclan
     * y un panel no puede decir «esta instancia es la que está mal».
     *
     * <p>{@code instance} es la única cuya cardinalidad depende del despliegue:
     * está acotada por el tamaño de la flota, pero con autoscaling agresivo y
     * pods efímeros se convierte en churn de series. Queda anotado: pasadas
     * ~50 instancias se saca de acá y se la deja poner al scraper como
     * {@code pod}, donde el retention de series muertas lo maneja Prometheus.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags(
            @Value("${spring.application.name:flight-reservations}") String application,
            @Value("${reservations.environment:local}") String environment,
            @Value("${HOSTNAME:local}") String instance) {
        return registry -> registry.config().commonTags(Tags.of(
                "application", application,
                "env", environment,
                "instance", instance));
    }

    /**
     * Tope de cardinalidad como red de contención.
     *
     * <p>El catálogo del §4 razona la cardinalidad medidor por medidor, y las
     * reglas están escritas en cada uno. Esto es lo que pasa cuando una de esas
     * reglas se rompe igual: a partir de 1.000 series con el mismo nombre, el
     * medidor deja de aceptar combinaciones nuevas en lugar de tumbar al
     * recolector. Se prefiere perder resolución en un medidor a perder el
     * monitoreo entero justo cuando llega la avalancha que lo causó.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> cardinalityCeiling() {
        return registry -> registry.config().meterFilter(MeterFilter.maximumAllowableTags(
                "reservations", "tag", MAX_SERIES_PER_METER, MeterFilter.deny()));
    }

    private static final int MAX_SERIES_PER_METER = 1_000;

    @Bean
    public BusinessMetrics businessMetrics(MeterRegistry registry) {
        return new BusinessMetrics(registry);
    }

    @Bean
    public SecurityMetrics securityMetrics(MeterRegistry registry) {
        return new SecurityMetrics(registry);
    }

    /**
     * El log de acceso.
     *
     * <p>Orden {@code MIN_VALUE + 10}: después de {@code CorrelationIdFilter}
     * ({@code MIN_VALUE}), para que la línea lleve el id, y antes de
     * {@code DegradationHeaderFilter} ({@code MIN_VALUE + 20}), para que
     * {@code Degradation.sources()} ya esté completo cuando este filtro
     * escribe.
     *
     * <p>{@code DispatcherType.ERROR} incluido: sin eso, un 400 de parseo del
     * contenedor o un 404 sin handler pasan por {@code /error} y no dejan
     * ninguna línea.
     */
    @Bean
    public FilterRegistrationBean<RequestLogFilter> requestLogFilter(BusinessMetrics metrics) {
        FilterRegistrationBean<RequestLogFilter> registration =
                new FilterRegistrationBean<>(new RequestLogFilter(metrics));
        registration.setOrder(Integer.MIN_VALUE + 10);
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC,
                DispatcherType.ERROR));
        return registration;
    }

    /**
     * Los dos gauges de secretos de desarrollo, y su línea de arranque.
     *
     * <p>Un gauge en 1 en cualquier entorno que no sea local es una alerta
     * (regla 7 de {@code docker/prometheus/rules/reservations.yml}). La
     * alternativa —que el arranque falle con {@code APP_ENV != local}— es más
     * fuerte y también más fácil de saltear con una variable de entorno mal
     * puesta; el gauge dice la verdad aunque alguien fuerce el arranque.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> developmentSecretGauges(
            PiiCipherProperties pii,
            SecurityProperties security,
            @Value("${reservations.messaging.enabled:true}") boolean messagingEnabled) {
        return registry -> {
            int devKey = pii.usesPublishedDevKey() ? 1 : 0;
            int devTokens = security.jwt().devTokensEnabled() ? 1 : 0;
            int messaging = messagingEnabled ? 1 : 0;
            Gauge.builder(MESSAGING_ENABLED, () -> messaging)
                    .description("1 si el publicador real está cableado; 0 si los eventos se "
                            + "marcan como despachados sin publicarse en ningún lado")
                    .strongReference(true)
                    .register(registry);
            Gauge.builder(PII_DEV_KEY, () -> devKey)
                    .description("1 si los documentos se cifran con la clave publicada en el repositorio")
                    .strongReference(true)
                    .register(registry);
            Gauge.builder(JWT_DEV_TOKENS, () -> devTokens)
                    .description("1 si se aceptan tokens firmados con la clave simétrica de desarrollo")
                    .strongReference(true)
                    .register(registry);
            if (devKey == 1 || devTokens == 1) {
                log.atWarn()
                        .addKeyValue(LogFields.EVENT, LogFields.STARTUP_WIRING)
                        .addKeyValue("pii.key.source", devKey == 1 ? "dev" : "configured")
                        .addKeyValue("jwt.tokens.source", devTokens == 1 ? "dev" : "issuer")
                        .log("El proceso está corriendo con secretos de desarrollo");
            }
        };
    }
}
