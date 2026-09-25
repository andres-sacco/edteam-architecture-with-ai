package com.edteam.reservations.support;

import java.util.Set;

/**
 * Los nombres de serie que este sistema publica, tal como salen de
 * {@code /actuator/prometheus}.
 *
 * <h2>Por qué existe una lista y no se derivan de las constantes</h2>
 * Entre el nombre del medidor en el código y el nombre de la serie en
 * Prometheus hay dos traducciones que dependen del <b>tipo</b> del medidor, y
 * el tipo no está en la constante: los puntos y los guiones pasan a guiones
 * bajos, los contadores ganan {@code _total}, y los timers y los gauges con
 * {@code baseUnit} ganan el sufijo de la unidad.
 *
 * <p>Esa última traducción ya costó un error real: la alerta del lag del outbox
 * estaba escrita contra {@code reservations_outbox_lag} y la serie se llama
 * {@code reservations_outbox_lag_seconds}. La regla era sintácticamente válida,
 * pasaba {@code promtool}, y <b>no habría disparado nunca</b>.
 *
 * <h2>Qué garantiza y qué no</h2>
 * Garantiza que ninguna alerta ni ningún panel nombre una serie que no está en
 * esta lista, que es el error que no se ve hasta el día que hace falta. No
 * garantiza lo inverso —que la lista siga el código si alguien renombra un
 * medidor—; de eso se ocupa {@code MetricsCatalogTest} para los medidores
 * nuevos, y de los heredados, el hecho de que renombrar uno rompa su panel.
 *
 * <p>Verificada contra la salida real de {@code /actuator/prometheus} con la
 * aplicación levantada y el camino sano y el degradado ejercitados.
 */
public final class PublishedMetrics {

    private PublishedMetrics() {}

    /** Series de la aplicación, más las de Actuator que los paneles usan. */
    public static final Set<String> NAMES = Set.of(
            // --- camino del pedido ---
            "reservations_operations_total",
            "reservations_requests_degraded_total",
            // --- catálogo de ciudades ---
            "reservations_catalog_call_seconds_bucket",
            "reservations_catalog_call_seconds_count",
            "reservations_catalog_call_seconds_sum",
            "reservations_catalog_fanout_seconds_bucket",
            "reservations_catalog_fanout_seconds_count",
            "reservations_catalog_fanout_seconds_sum",
            "reservations_catalog_errors_total",
            "reservations_catalog_retries_total",
            "reservations_catalog_budget_exhausted_total",
            // --- degradación ---
            "reservations_degraded_responses_total",
            "reservations_degraded_exhausted_total",
            "reservations_degraded_stale_age_seconds_count",
            "reservations_degraded_stale_age_seconds_sum",
            // --- outbox ---
            "reservations_outbox_lag_seconds",
            "reservations_outbox_pending",
            "reservations_outbox_dead",
            "reservations_outbox_dispatched_retained",
            "reservations_outbox_enqueued_total",
            "reservations_outbox_claimed_total",
            "reservations_outbox_dispatched_total",
            "reservations_outbox_failed_total",
            "reservations_outbox_deferred_total",
            "reservations_outbox_dispatch_skipped_total",
            "reservations_outbox_dispatch_probes_total",
            "reservations_outbox_dispatch_duration_seconds_count",
            "reservations_outbox_dispatch_duration_seconds_sum",
            "reservations_outbox_dispatch_duration_seconds_max",
            "reservations_outbox_metrics_errors_total",
            // --- mensajería ---
            "reservations_messaging_enabled",
            "reservations_messaging_dlq_depth",
            "reservations_messaging_consumed_total",
            "reservations_messaging_out_of_order_total",
            "reservations_messaging_dead_lettered_total",
            // --- seguridad ---
            "reservations_security_auth_failures_total",
            "reservations_security_rate_limited_total",
            "reservations_security_quota_registry_reset_total",
            "reservations_security_pii_dev_key",
            "reservations_security_jwt_dev_tokens",
            // --- cache ---
            "reservations_cache_gets_total",
            "reservations_cache_puts_total",
            "reservations_cache_evictions_total",
            "reservations_cache_errors_total",
            "reservations_cache_size",
            // --- de Actuator, usadas por los paneles y las alertas ---
            "http_server_requests_seconds_bucket",
            "http_server_requests_seconds_count",
            "hikaricp_connections_pending",
            // --- recording rules ---
            "reservations:auth_failures:rate10m");

    /**
     * Toda referencia con forma de serie nuestra dentro de una expresión.
     *
     * <p>Incluye {@code http_server_requests*} y {@code hikaricp_*} porque son
     * las dos de Actuator sobre las que hay paneles y alertas escritos.
     */
    public static final java.util.regex.Pattern REFERENCE = java.util.regex.Pattern.compile(
            "\\b(reservations[_:][A-Za-z0-9_:]+|http_server_requests[A-Za-z0-9_]*|hikaricp_[A-Za-z0-9_]+)\\b");
}
