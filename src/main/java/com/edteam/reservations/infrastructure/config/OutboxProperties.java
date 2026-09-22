package com.edteam.reservations.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parámetros del despacho de eventos hacia el sistema de notificaciones.
 *
 * <p>Las otras dos propiedades del prefijo las leen directamente las
 * anotaciones del disparador, porque se evalúan antes de que exista este bean:
 * {@code reservations.outbox.dispatch-enabled} en el {@code @ConditionalOnProperty}
 * y {@code reservations.outbox.dispatch-interval} en el {@code @Scheduled} de
 * {@code OutboxDispatchScheduler}.
 *
 * @param batchSize   mensajes por corrida del despachador
 * @param maxAttempts intentos antes de dar un mensaje por fallido (dead letter)
 */
@ConfigurationProperties(prefix = "reservations.outbox")
public record OutboxProperties(int batchSize, int maxAttempts) {

    public OutboxProperties {
        if (batchSize <= 0) {
            batchSize = 50;
        }
        if (maxAttempts <= 0) {
            maxAttempts = 5;
        }
    }
}
