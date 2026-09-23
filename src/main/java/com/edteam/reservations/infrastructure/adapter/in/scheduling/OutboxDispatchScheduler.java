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
import java.util.concurrent.ThreadLocalRandom;

/**
 * Adaptador de entrada que dispara periódicamente el relay del outbox.
 *
 * <p>Es infraestructura pura: la lógica vive en el caso de uso
 * {@link DispatchPendingNotificationsUseCase}, que se testea sin Spring. Acá
 * sólo está el mecanismo de disparo, que es un detalle reemplazable.
 *
 * <p>Se puede apagar con {@code reservations.outbox.dispatch-enabled=false},
 * que es lo que hacen los tests para que el despacho no compita con las
 * aserciones.
 *
 * <h2>Varias instancias ya no son un problema</h2>
 * Cada una ejecuta la tarea, y está bien: con el outbox en la base y el reclamo
 * por {@code FOR UPDATE SKIP LOCKED}, cada instancia se lleva un subconjunto
 * disjunto de mensajes. No hace falta el lock distribuido que antes había que
 * considerar, y de paso el despacho escala con la cantidad de instancias.
 *
 * <p>Lo que sí hace falta es <b>jitter</b>: con {@code fixedDelay} puro, N
 * instancias que arrancaron juntas despiertan juntas y compiten por las mismas
 * filas en la misma milésima. El sorteo las descorrelaciona.
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

    @Scheduled(fixedDelayString = "${reservations.outbox.dispatch-interval:5s}",
            initialDelayString = "${reservations.outbox.dispatch-interval:5s}")
    public void dispatch() {
        try {
            jitter();
            OutboxDispatchResult result = dispatchNotifications.dispatchPending(properties.batchSize());
            if (result.total() > 0) {
                log.info("Outbox despachado: {} publicados, {} fallidos, {} postergados",
                        result.dispatched(), result.failed(), result.deferred());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // Si se propaga, Spring cancela las siguientes ejecuciones de la
            // tarea: el relay quedaría muerto hasta el próximo reinicio.
            log.error("Error inesperado despachando el outbox", e);
        }
    }

    /**
     * Hasta un 20 % del intervalo, sorteado.
     *
     * <p>Duerme en el hilo del scheduler, que tiene su propio pool y no atiende
     * pedidos. Es la forma más barata de descorrelacionar N instancias sin
     * agregar un coordinador.
     */
    private void jitter() throws InterruptedException {
        long spread = properties.dispatchInterval().toMillis() / 5;
        if (spread > 1) {
            Thread.sleep(ThreadLocalRandom.current().nextLong(spread));
        }
    }
}
