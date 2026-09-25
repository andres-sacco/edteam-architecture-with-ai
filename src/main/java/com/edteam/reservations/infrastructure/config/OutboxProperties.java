package com.edteam.reservations.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
public record OutboxProperties(
        Duration dispatchInterval,
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
            // Ochenta y no diez. Con initial-backoff 5 s, max-backoff 5 m y
            // diez intentos, los diez se agotaban en 20 minutos de reloj y el
            // techo de seis horas no participaba nunca: dos números que
            // decían cosas distintas, y el que mandaba era el que NO
            // describía la intención. Ahora manda el techo de tiempo —que es
            // el que expresa «seis horas reintentando es un problema que ya
            // tiene dueño»— y max-attempts vuelve a ser lo que su javadoc
            // decía: la red por si el backoff queda mal configurado.
            maxAttempts = 80;
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

    /**
     * Cuánto tiempo de reloj cubren {@code max-attempts} reintentos con este
     * backoff, en el peor caso.
     *
     * <p>Es el número que hacía falta para que los dos cortes fueran
     * coherentes. Si es menor que {@code retry-ceiling}, el techo de tiempo es
     * configuración muerta: {@code max-attempts} gana siempre. Un test lo
     * compara y falla cuando divergen.
     */
    public Duration worstCaseRetryWindow() {
        Duration total = Duration.ZERO;
        long millis = initialBackoff.toMillis();
        for (int attempt = 1; attempt < maxAttempts; attempt++) {
            total = total.plusMillis(Math.min(millis, maxBackoff.toMillis()));
            if (millis < maxBackoff.toMillis()) {
                millis *= 2;
            }
        }
        return total;
    }

    /**
     * Una copia con el lease del reclamo elevado a {@code floor}.
     *
     * <p>El lease tiene que ser mayor que el peor caso de un tick, o el
     * reclamo vence mientras el lote todavía se está publicando y otra
     * instancia re-reclama mensajes en vuelo. Con lotes de 50 y un
     * {@code confirm-timeout} de 5 s, ese peor caso son más de cuatro minutos
     * contra un broker que acepta y no confirma: el lease de dos minutos que
     * había era la mitad de corto de lo necesario. Se deriva de
     * {@code batch-size × confirm-timeout} en lugar de ser una constante
     * suelta que hay que acordarse de subir.
     */
    public OutboxProperties withClaimLeaseAtLeast(Duration floor) {
        if (floor == null || claimLease.compareTo(floor) >= 0) {
            return this;
        }
        return new OutboxProperties(
                dispatchInterval,
                batchSize,
                maxAttempts,
                retryCeiling,
                initialBackoff,
                maxBackoff,
                floor,
                retention,
                metricsCache);
    }
}
