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
}
