package com.edteam.reservations.infrastructure.adapter.in.scheduling;

import com.edteam.reservations.application.outbox.OutboxDispatchResult;
import com.edteam.reservations.application.port.in.DispatchPendingNotificationsUseCase;
import com.edteam.reservations.infrastructure.config.OutboxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Adaptador de entrada que dispara periódicamente el despacho del outbox.
 *
 * <p>Es infraestructura pura: la lógica vive en el caso de uso
 * {@link DispatchPendingNotificationsUseCase}, que se testea sin Spring. Acá
 * sólo está el mecanismo de disparo, que es un detalle reemplazable.
 *
 * <p>Se puede apagar con {@code reservations.outbox.dispatch-enabled=false},
 * que es lo que hacen los tests para que el envío no compita con las
 * aserciones.
 *
 * <p>Nota para cuando haya varias instancias: hoy cada una ejecutaría la
 * tarea. Con el outbox en base de datos eso se resuelve tomando los mensajes
 * con {@code FOR UPDATE SKIP LOCKED}; si hiciera falta una única ejecución por
 * cluster, hay que sumar un lock distribuido.
 */
@Component
@ConditionalOnProperty(name = "reservations.outbox.dispatch-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatchScheduler.class);

    private final DispatchPendingNotificationsUseCase dispatchNotifications;
    private final OutboxProperties properties;

    public OutboxDispatchScheduler(DispatchPendingNotificationsUseCase dispatchNotifications,
                                   OutboxProperties properties) {
        this.dispatchNotifications = Objects.requireNonNull(dispatchNotifications);
        this.properties = Objects.requireNonNull(properties);
    }

    @Scheduled(fixedDelayString = "${reservations.outbox.dispatch-interval:5s}")
    public void dispatch() {
        try {
            OutboxDispatchResult result = dispatchNotifications.dispatchPending(properties.batchSize());
            if (result.total() > 0) {
                log.info("Outbox despachado: {} enviados, {} fallidos", result.dispatched(), result.failed());
            }
        } catch (RuntimeException e) {
            // Si se propaga, Spring cancela las siguientes ejecuciones de la tarea.
            log.error("Error inesperado despachando el outbox", e);
        }
    }
}
