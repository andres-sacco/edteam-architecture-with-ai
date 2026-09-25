package com.edteam.reservations.infrastructure.config;

import com.edteam.reservations.application.port.in.DispatchPendingNotificationsUseCase;
import com.edteam.reservations.infrastructure.adapter.in.ops.DeadLetterEndpoint;
import com.edteam.reservations.infrastructure.adapter.in.ops.OutboxEndpoint;
import com.edteam.reservations.infrastructure.adapter.out.messaging.DeadLetterQueue;
import com.edteam.reservations.infrastructure.adapter.out.outbox.OutboxAdmin;
import com.edteam.reservations.infrastructure.adapter.out.outbox.OutboxMetrics;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Herramientas de operación de la mensajería: las métricas y los dos endpoints
 * de dead letter.
 *
 * <p>Están juntas porque responden a la misma pregunta desde dos lados. La
 * métrica avisa que algo se trabó; el endpoint deja mirarlo y arreglarlo. Sin
 * la primera nadie se entera; sin el segundo, enterarse no sirve de nada.
 *
 * <p>Los endpoints viven en el puerto de gestión (9090), que no se publica
 * hacia afuera. {@code @ConditionalOnAvailableEndpoint} los cablea sólo si la
 * configuración los expone: un endpoint que reencola mensajes no se registra
 * "por si acaso".
 */
@Configuration
public class OpsConfiguration {

    /**
     * Gauges del outbox y de la DLQ.
     *
     * <p>La profundidad de la DLQ llega como {@code Supplier} y no como
     * dependencia directa para que el gauge no quede atado al ciclo de vida del
     * adaptador del broker: si no hay broker, devuelve "no sé" en lugar de
     * impedir que se registren las otras cuatro series.
     */
    @Bean
    public OutboxMetrics outboxMetrics(
            OutboxAdmin outbox, DeadLetterQueue deadLetterQueue, OutboxProperties properties) {
        return new OutboxMetrics(outbox, deadLetterQueue::depth, properties.metricsCache());
    }

    @Bean
    @ConditionalOnAvailableEndpoint(endpoint = OutboxEndpoint.class)
    public OutboxEndpoint outboxEndpoint(
            OutboxAdmin outbox, DispatchPendingNotificationsUseCase dispatchNotifications) {
        return new OutboxEndpoint(outbox, dispatchNotifications);
    }

    @Bean
    @ConditionalOnAvailableEndpoint(endpoint = DeadLetterEndpoint.class)
    public DeadLetterEndpoint deadLetterEndpoint(DeadLetterQueue deadLetterQueue) {
        return new DeadLetterEndpoint(deadLetterQueue);
    }
}
