package com.edteam.reservations.application.port.out;

import com.edteam.reservations.application.notification.NotificationDelivery;

/**
 * Efecto del consumidor: emitir la notificación del hecho recibido.
 *
 * <p>Existe como puerto y no como una línea de log por una razón práctica: sin
 * un efecto que se pueda contar no hay forma de probar que procesar el mismo
 * mensaje dos veces deja exactamente el mismo estado, que es la propiedad que
 * toda esta maquinaria de idempotencia promete.
 *
 * <p>El destinatario se identifica por id interno de usuario. Resolver el dato
 * de contacto es del dueño del canal: acá nunca hay un email.
 */
public interface NotificationDeliveryPort {

    /** Registra la entrega. Participa de la transacción del caso de uso. */
    void deliver(NotificationDelivery delivery);

    /** Entregas registradas para una reserva; sólo para inspección y tests. */
    int countFor(String subject);
}
