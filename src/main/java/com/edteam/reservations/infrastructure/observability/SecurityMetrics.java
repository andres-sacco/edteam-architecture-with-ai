package com.edteam.reservations.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.util.Objects;
import java.util.Set;

/**
 * Los contadores del borde: credenciales rechazadas y cuota superada.
 *
 * <p>Los dos eventos que estos contadores miden eran invisibles. Un 401 no
 * dejaba ni log ni métrica —el javadoc del entry point prometía que «el motivo
 * real queda del lado del servidor, en el log» y no escribía ninguna línea—,
 * así que una campaña de credenciales robadas contra la API no se veía por
 * ningún lado. El rechazo por cuota dejaba un {@code WARN} y ningún contador,
 * así que no había umbral posible ni forma de decidir si subir o bajar la
 * cuota, que es la única decisión que ese filtro habilita.
 *
 * <h2>El motivo es un enum, y nunca lleva el token</h2>
 * {@code reason} toma tres valores y salen de {@link #REASONS}. No se escribe
 * el token, ni el {@code kid}, ni los primeros caracteres: un fragmento de JWT
 * en una etiqueta de métrica es una credencial replicada a un sistema que
 * además la retiene por semanas.
 *
 * <h2>Y no se etiqueta por IP</h2>
 * Sería la etiqueta obvia y es ilimitada por definición —la elige quien
 * ataca— además de ser dato personal. La IP se escribe en el log, donde tiene
 * retención acotada, y la métrica dice sólo «cuántas y de qué tipo», que es
 * lo que un umbral necesita. El pivote de la alerta a los {@code clientIp}
 * concretos es por {@code event=auth.failed} en el log.
 */
public class SecurityMetrics {

    public static final String AUTH_FAILURES = "reservations.security.auth.failures";
    public static final String RATE_LIMITED = "reservations.security.rate_limited";
    public static final String QUOTA_REGISTRY_RESET = "reservations.security.quota_registry_reset";

    /** Vocabulario cerrado de {@code reason}. */
    public static final Set<String> REASONS = Set.of("no_token", "invalid_token", "forbidden");

    private final MeterRegistry registry;

    public SecurityMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "El registro de métricas es obligatorio");
    }

    public void authFailure(String reason) {
        Counter.builder(AUTH_FAILURES)
                .tags(Tags.of("reason", REASONS.contains(reason) ? reason : "invalid_token"))
                .description("Pedidos rechazados por credencial ausente, inválida o insuficiente")
                .register(registry)
                .increment();
    }

    public void rateLimited(String method, String route) {
        Counter.builder(RATE_LIMITED)
                .tags(Tags.of("method", method, "route", route))
                .description("Pedidos rechazados por superar la cuota del borde")
                .register(registry)
                .increment();
    }

    /**
     * El registro de cuotas se vació por desbordarse.
     *
     * <p>Tiene contador propio porque no es lo mismo que un rechazo: cuando
     * pasa, todos los clientes legítimos reciben una ventana de regalo. Sin
     * este contador, la única señal es un {@code WARN} suelto en medio de un
     * ataque, que es cuando nadie lo va a leer.
     */
    public void quotaRegistryReset() {
        Counter.builder(QUOTA_REGISTRY_RESET)
                .description("Veces que el registro de cuotas se vació por desbordarse: "
                        + "cada una regala una ventana a todos los clientes")
                .register(registry)
                .increment();
    }
}
