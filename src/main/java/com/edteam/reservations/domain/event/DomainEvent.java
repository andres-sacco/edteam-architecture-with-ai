package com.edteam.reservations.domain.event;

import com.edteam.reservations.domain.model.ItinerarySummary;
import com.edteam.reservations.domain.model.ReservationId;
import com.edteam.reservations.domain.model.UserId;

import java.time.Instant;

/**
 * Hecho de negocio ya ocurrido sobre una reserva.
 *
 * <p>Los eventos son el mecanismo que desacopla las reservas del sistema
 * externo de notificaciones: el caso de uso registra el hecho y termina; el
 * envío se hace después, fuera de la transacción del usuario. Si el sistema de
 * notificaciones está caído, las reservas siguen funcionando.
 *
 * <p>El evento identifica al destinatario por {@link UserId} y no por su
 * correo: el dato de contacto lo resuelve el sistema de notificaciones, que es
 * su dueño. Así un cambio de email no obliga a reemitir eventos, y las reservas
 * no necesitan leer el usuario para notificar.
 *
 * <p>Los eventos se construyen a partir de una reserva <em>ya persistida</em>
 * (ver las factorías {@code of(...)} de cada tipo), porque hasta ese momento no
 * existe el {@link ReservationId} que el consumidor necesita.
 *
 * <p>La interfaz es {@code sealed} para que el compilador obligue a cubrir
 * todos los casos cuando se hace pattern matching sobre el tipo de evento.
 */
public sealed interface DomainEvent
        permits ReservationCreated, ReservationConfirmed, ReservationModified, ReservationCancelled {

    ReservationId reservationId();

    /** Usuario dueño de la reserva y destinatario de la notificación. */
    UserId userId();

    /** Instante en que ocurrió el hecho (no en el que se envía la notificación). */
    Instant occurredAt();

    /** Nombre estable del evento; es parte del contrato con los consumidores. */
    String eventType();

    /** Qué se reservó, en la forma resumida que consumen las notificaciones. */
    ItinerarySummary itinerary();
}
