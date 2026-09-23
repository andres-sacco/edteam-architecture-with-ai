package com.edteam.reservations.infrastructure.adapter.out.notification;

import com.edteam.reservations.application.port.out.NotificationPort;
import com.edteam.reservations.domain.event.DomainEvent;
import com.edteam.reservations.domain.event.ReservationCancelled;
import com.edteam.reservations.domain.event.ReservationConfirmed;
import com.edteam.reservations.domain.event.ReservationCreated;
import com.edteam.reservations.domain.event.ReservationModified;
import com.edteam.reservations.domain.model.ItinerarySummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adaptador de notificaciones que sólo escribe en el log.
 *
 * <p><strong>Es un stub deliberado:</strong> el sistema de notificaciones es un
 * servicio aparte y su integración no forma parte de este esqueleto. Se
 * reemplaza por un cliente HTTP o un productor de mensajes (SQS/Kafka) sin
 * tocar nada más, porque el resto del sistema depende de
 * {@link NotificationPort}.
 *
 * <p>El {@code switch} sobre el evento es exhaustivo sin {@code default}
 * gracias a que {@link DomainEvent} es sellada: si mañana se agrega un tipo de
 * evento, el compilador marca este lugar como pendiente de actualizar.
 */
@Component
public class LoggingNotificationAdapter implements NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationAdapter.class);

    @Override
    public void notify(DomainEvent event) {
        String message = switch (event) {
            case ReservationCreated e -> "Registramos tu reserva %s: %s, %d pasajero(s), %s"
                    .formatted(e.reservationId(), describe(e.itinerary()), e.passengerCount(),
                            e.itinerary().price());
            case ReservationConfirmed e -> "Tu reserva %s quedó confirmada: %s"
                    .formatted(e.reservationId(), describe(e.itinerary()));
            case ReservationModified e -> "Tu reserva %s cambió de itinerario: antes %s, ahora %s"
                    .formatted(e.reservationId(), describe(e.previousItinerary()), describe(e.itinerary()));
            case ReservationCancelled e -> "Se canceló tu reserva %s: %s"
                    .formatted(e.reservationId(), describe(e.itinerary()));
        };

        // Dos niveles a propósito. En INFO queda la traza operativa —qué se
        // notificó, a qué usuario y sobre qué reserva—, identificando al
        // usuario por su id interno, que fuera de nuestra base no es un dato
        // personal. El texto del mensaje lleva ruta y fechas de viaje, que sí
        // lo son: queda en DEBUG, apagado en producción, donde el destino de
        // estos logs es un SaaS de observabilidad.
        log.info("[notificaciones] tipo={} usuario={} reserva={}",
                event.eventType(), event.userId(), event.reservationId());
        log.debug("[notificaciones] reserva={} mensaje=\"{}\"", event.reservationId(), message);
    }

    private static String describe(ItinerarySummary itinerary) {
        String route = "%s-%s el %s".formatted(itinerary.origin(), itinerary.destination(),
                itinerary.firstDeparture());
        return itinerary.hasConnections()
                ? "%s (%d tramos)".formatted(route, itinerary.segmentCount())
                : route;
    }
}
