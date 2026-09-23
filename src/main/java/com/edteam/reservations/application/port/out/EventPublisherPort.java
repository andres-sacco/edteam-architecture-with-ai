package com.edteam.reservations.application.port.out;

import com.edteam.reservations.application.exception.EventPublishException;
import com.edteam.reservations.application.outbox.OutboxMessage;

/**
 * Puerto de salida para publicar los hechos ya ocurridos.
 *
 * <p>Reemplaza al viejo {@code NotificationPort}: el puerto sigue siendo el
 * mismo concepto —una salida para despachar hechos— pero el nombre mentía en
 * cuanto el destino es un exchange. «Notificaciones» nombra a un consumidor
 * que el productor no debería conocer; acá no se sabe si hay uno, tres o
 * ninguno.
 *
 * <p>Ningún caso de uso lo llama de forma directa: lo invoca el despachador
 * del outbox, por fuera de la operación del usuario. Una caída del destino no
 * afecta la disponibilidad de las reservas.
 *
 * <p>Recibe el {@link OutboxMessage} completo y no el evento de dominio, y eso
 * es la corrección de un defecto concreto: el {@code messageId} y el
 * {@code sequence} tienen que llegar al consumidor. Se le pide idempotencia y
 * tolerancia al desorden; sin esos dos valores no tiene con qué pagarla.
 */
public interface EventPublisherPort {

    /**
     * Publica el mensaje y no vuelve hasta tener confirmación del destino.
     *
     * <p>La entrega es <em>at-least-once</em>: el mismo mensaje puede
     * publicarse más de una vez (un reintento después de un ack perdido), y el
     * {@code id} del mensaje es estable entre esos reintentos justamente para
     * que el consumidor pueda deduplicar.
     *
     * @throws EventPublishException si el fallo es transitorio y hay que reintentar
     * @throws RuntimeException      cualquier otra excepción se interpreta como
     *                               fallo permanente y el mensaje va a la dead letter
     *                               sin reintentos
     */
    void publish(OutboxMessage message);
}
