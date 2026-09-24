package com.edteam.reservations.application.port.in;

import com.edteam.reservations.application.outbox.OutboxDispatchResult;

/**
 * Puerto de entrada: enviar al sistema externo las notificaciones pendientes.
 *
 * <p>Es un caso de uso propio, disparado por el sistema y no por un usuario.
 * Tener el puerto —en lugar de que el scheduler llame al servicio concreto—
 * permite cambiar el disparador (tarea programada, consumidor de cola, un
 * endpoint de operaciones) sin tocar la lógica de despacho.
 */
public interface DispatchPendingNotificationsUseCase {

    /**
     * Procesa un lote de notificaciones pendientes.
     *
     * @param batchSize cantidad máxima de mensajes a tomar; debe ser positivo
     */
    OutboxDispatchResult dispatchPending(int batchSize);

    /**
     * Despacha <strong>un</strong> mensaje como llamada de prueba, sin
     * gastarle el intento si falla.
     *
     * <p>Es lo que el relay dispara cuando el circuito del broker está
     * semiabierto: hace falta una publicación real para saber si el destino
     * volvió, pero esa publicación no puede acercar a la dead letter a un
     * mensaje que no hizo nada malo. Si la prueba falla, el mensaje se libera
     * con su contador intacto.
     */
    OutboxDispatchResult dispatchProbe();
}
