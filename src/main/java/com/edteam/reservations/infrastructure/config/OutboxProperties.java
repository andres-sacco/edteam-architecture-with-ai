package com.edteam.reservations.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Parámetros del relay del outbox.
 *
 * <p>{@code dispatch-enabled} y {@code purge-cron} los leen directamente las
 * anotaciones del disparador, porque se evalúan antes de que exista este bean.
 * {@code dispatch-interval} se declara acá <em>además</em> de en el
 * {@code @Scheduled}: el disparador necesita el valor para calcular su jitter,
 * y una constante duplicada sería una que se desincroniza.
 *
 * <h2>Por qué hay un techo de tiempo además de un tope de intentos</h2>
 * Contar sólo intentos describe mal un fallo transitorio largo: con backoff
 * exponencial, cinco intentos se agotan en minutos y una caída de media hora
 * del broker manda a la dead letter mensajes cuyo fallo era recuperable. Con
 * sólo un techo de tiempo, en cambio, un mensaje venenoso se reintentaría
 * durante horas. Se usan los dos y manda el que llegue primero.
 *
 * @param dispatchInterval cada cuánto corre el relay. El jitter del disparador
 *                       se sortea sobre una fracción de este valor
 * @param batchSize      mensajes por corrida del relay
 * @param maxAttempts    tope de intentos antes de la dead letter
 * @param retryCeiling   antigüedad máxima en reintentos; al superarla el
 *                       mensaje va a la dead letter aunque le queden intentos
 * @param initialBackoff espera del primer reintento; duplica en cada uno
 * @param maxBackoff     techo de la espera. Sin él la progresión exponencial se
 *                       vuelve absurda. El jitter se sortea sobre el valor
 *                       calculado, para que N instancias no reintenten todas en
 *                       el mismo instante contra un destino que ya está caído
 * @param claimLease     cuánto vale un reclamo. Si el proceso muere con el
 *                       mensaje tomado, al vencer vuelve a ser elegible: es lo
 *                       que impide que una caída deje mensajes trabados
 * @param retention      cuánto se conservan los despachados antes de purgarse
 * @param metricsCache   cuánto vale una foto del outbox para los gauges. Un
 *                       gauge se evalúa en cada raspado del recolector y son
 *                       cinco series sobre la misma foto: sin esta ventana, el
 *                       monitoreo se convierte en carga sobre la base. En cero
 *                       cada lectura consulta, que es lo que necesitan los tests
 */
@ConfigurationProperties(prefix = "reservations.outbox")
public record OutboxProperties(Duration dispatchInterval,
                               int batchSize,
                               int maxAttempts,
                               Duration retryCeiling,
                               Duration initialBackoff,
                               Duration maxBackoff,
                               Duration claimLease,
                               Duration retention,
                               Duration metricsCache) {

    public OutboxProperties {
        if (dispatchInterval == null || dispatchInterval.isNegative() || dispatchInterval.isZero()) {
            dispatchInterval = Duration.ofSeconds(5);
        }
        if (batchSize <= 0) {
            batchSize = 50;
        }
        if (maxAttempts <= 0) {
            maxAttempts = 10;
        }
        if (retryCeiling == null || retryCeiling.isNegative() || retryCeiling.isZero()) {
            retryCeiling = Duration.ofHours(6);
        }
        if (initialBackoff == null || initialBackoff.isNegative() || initialBackoff.isZero()) {
            initialBackoff = Duration.ofSeconds(5);
        }
        if (maxBackoff == null || maxBackoff.compareTo(initialBackoff) < 0) {
            maxBackoff = Duration.ofMinutes(5);
        }
        if (claimLease == null || claimLease.isNegative() || claimLease.isZero()) {
            claimLease = Duration.ofMinutes(2);
        }
        if (retention == null || retention.isNegative() || retention.isZero()) {
            retention = Duration.ofDays(7);
        }
        if (metricsCache == null || metricsCache.isNegative()) {
            metricsCache = Duration.ofSeconds(5);
        }
    }
}
