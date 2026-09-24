package com.edteam.reservations.infrastructure.config;

import java.time.Duration;

/**
 * Umbrales de un circuito. Los mismos campos para las tres dependencias, con
 * valores distintos: cada número está justificado en {@code application.yml}
 * y ninguno es el default de la librería.
 *
 * <p>Todos los campos son opcionales y se completan con los valores por
 * defecto de <em>esa</em> dependencia, no con unos globales. Es lo que permite
 * que un entorno baje sólo {@code wait-duration-in-open-state} sin tener que
 * repetir los otros ocho y sin que los que no escribió se conviertan en cero.
 *
 * @param enabled                                apagarlo deja la cadena sin circuito, útil para aislar
 *                                               un problema en producción sin redeployar
 * @param slidingWindowSize                      tamaño de la ventana, en llamadas
 * @param minimumNumberOfCalls                   mínimo para que el umbral signifique algo
 * @param failureRateThreshold                   porcentaje de fallo que abre
 * @param slowCallDurationThreshold              a partir de cuánto una llamada es «lenta»
 * @param slowCallRateThreshold                  porcentaje de llamadas lentas que abre
 * @param waitDurationInOpenState                cuánto queda abierto antes de probar
 * @param permittedCallsInHalfOpenState          llamadas de prueba en semiabierto
 * @param automaticTransitionFromOpenToHalfOpen  la recuperación no depende de que llegue tráfico
 * @param windowMaxAge                           edad a partir de la cual la ventana se descarta entera
 */
public record CircuitBreakerProperties(Boolean enabled,
                                       Integer slidingWindowSize,
                                       Integer minimumNumberOfCalls,
                                       Integer failureRateThreshold,
                                       Duration slowCallDurationThreshold,
                                       Integer slowCallRateThreshold,
                                       Duration waitDurationInOpenState,
                                       Integer permittedCallsInHalfOpenState,
                                       Boolean automaticTransitionFromOpenToHalfOpen,
                                       Duration windowMaxAge) {

    /**
     * Completa lo que no vino con los valores por defecto de la dependencia.
     *
     * @param configured lo que se leyó de la configuración, o {@code null}
     * @param defaults   los valores documentados para esta dependencia
     */
    public static CircuitBreakerProperties merge(CircuitBreakerProperties configured,
                                                 CircuitBreakerProperties defaults) {
        if (configured == null) {
            return defaults;
        }
        return new CircuitBreakerProperties(
                configured.enabled != null ? configured.enabled : defaults.enabled,
                positive(configured.slidingWindowSize, defaults.slidingWindowSize),
                positive(configured.minimumNumberOfCalls, defaults.minimumNumberOfCalls),
                percentage(configured.failureRateThreshold, defaults.failureRateThreshold),
                configured.slowCallDurationThreshold != null
                        ? configured.slowCallDurationThreshold : defaults.slowCallDurationThreshold,
                percentage(configured.slowCallRateThreshold, defaults.slowCallRateThreshold),
                configured.waitDurationInOpenState != null
                        ? configured.waitDurationInOpenState : defaults.waitDurationInOpenState,
                positive(configured.permittedCallsInHalfOpenState, defaults.permittedCallsInHalfOpenState),
                configured.automaticTransitionFromOpenToHalfOpen != null
                        ? configured.automaticTransitionFromOpenToHalfOpen
                        : defaults.automaticTransitionFromOpenToHalfOpen,
                configured.windowMaxAge != null ? configured.windowMaxAge : defaults.windowMaxAge);
    }

    private static Integer positive(Integer configured, Integer fallback) {
        return configured != null && configured > 0 ? configured : fallback;
    }

    private static Integer percentage(Integer configured, Integer fallback) {
        return configured != null && configured > 0 && configured <= 100 ? configured : fallback;
    }
}
