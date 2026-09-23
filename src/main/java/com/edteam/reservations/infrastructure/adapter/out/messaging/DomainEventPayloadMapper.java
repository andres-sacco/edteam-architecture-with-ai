package com.edteam.reservations.infrastructure.adapter.out.messaging;

import com.edteam.reservations.domain.event.DomainEvent;
import com.edteam.reservations.domain.event.ReservationCancelled;
import com.edteam.reservations.domain.event.ReservationConfirmed;
import com.edteam.reservations.domain.event.ReservationCreated;
import com.edteam.reservations.domain.event.ReservationModified;
import com.edteam.reservations.domain.model.ItinerarySummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Evento de dominio → JSON del {@code data} del mensaje.
 *
 * <p>Es el único punto del sistema que conoce los cuatro tipos de hecho, y está
 * en infraestructura: el dominio no sabe serializarse. Acá vive el
 * {@code switch} exhaustivo sobre la interfaz sellada, que es lo que hace que
 * agregar un quinto evento rompa el <em>build</em> y no la producción.
 *
 * <p>Se invoca <b>al encolar</b>, dentro de la transacción del caso de uso, y
 * no al publicar. Lo que sale es exactamente lo que pasó aunque el código
 * cambie entre el encolado y el despacho, y el relay reenvía bytes sin conocer
 * ningún tipo de evento.
 *
 * <h2>Lo que no viaja, y no es negociable</h2>
 * Email, nombre y documento del pasajero, datos de pago. El destinatario se
 * identifica por {@code userId} interno y el sistema de notificaciones resuelve
 * el contacto, que es dato suyo: un cambio de email no obliga a reemitir nada.
 *
 * <p>Lo que sí viaja es ruta y fecha de viaje, porque sin eso el consumidor no
 * puede redactar el mensaje. Atado a un {@code userId} eso <em>es</em> dato
 * personal, así que el broker es un sistema de tratamiento y no un caño.
 *
 * <h2>Detalles del formato que son decisiones</h2>
 * <ul>
 *   <li>{@code price.amount} viaja como <b>string</b>, no como número: un
 *       consumidor que parsee JSON a {@code double} perdería precisión. Es el
 *       mismo motivo por el que {@code Money} usa {@code BigDecimal};</li>
 *   <li>los ids viajan como string aunque en la base sean {@code BIGSERIAL},
 *       para que un cambio futuro del tipo de id no rompa el contrato.</li>
 * </ul>
 */
@Component
public class DomainEventPayloadMapper {

    /** Versión mayor del esquema del payload. Sólo cambia ante una ruptura. */
    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public DomainEventPayloadMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /** Versión del esquema del payload de este evento. */
    public int schemaVersion(DomainEvent event) {
        Objects.requireNonNull(event, "El evento es obligatorio");
        // Hoy los cuatro comparten versión. El método existe para que el día
        // que uno rompa su esquema no haya que cambiar la firma de nada.
        return switch (event) {
            case ReservationCreated ignored -> SCHEMA_VERSION;
            case ReservationConfirmed ignored -> SCHEMA_VERSION;
            case ReservationModified ignored -> SCHEMA_VERSION;
            case ReservationCancelled ignored -> SCHEMA_VERSION;
        };
    }

    /** El {@code data} del mensaje, serializado. */
    public String toPayload(DomainEvent event) {
        Objects.requireNonNull(event, "El evento es obligatorio");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reservationId", event.reservationId().toString());
        data.put("userId", String.valueOf(event.userId().value()));

        // Exhaustivo y sin 'default': si mañana aparece un quinto hecho, el
        // compilador marca este lugar. Es el único punto del sistema donde eso
        // pasa, y es a propósito.
        switch (event) {
            case ReservationCreated e -> {
                data.put("passengerCount", e.passengerCount());
                data.put("itinerary", itinerary(e.itinerary()));
            }
            case ReservationConfirmed e -> data.put("itinerary", itinerary(e.itinerary()));
            case ReservationModified e -> {
                // El itinerario anterior además del nuevo: sin eso el
                // consumidor sólo puede decir "tu reserva cambió" y no "tu
                // vuelo pasó del 12 al 14". Reconstruirlo preguntándonos el
                // estado previo sería imposible —ya no existe— y volvería a
                // acoplar al consumidor con nosotros.
                data.put("previousItinerary", itinerary(e.previousItinerary()));
                data.put("itinerary", itinerary(e.itinerary()));
            }
            case ReservationCancelled e -> data.put("itinerary", itinerary(e.itinerary()));
        }

        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            // Permanente por naturaleza: insistir con el mismo objeto da el
            // mismo resultado. Sube como IllegalStateException y el caso de uso
            // falla, que es lo correcto: es mejor no crear la reserva que
            // crearla con un evento que nunca va a poder publicarse.
            throw new IllegalStateException(
                    "No se pudo serializar el payload de %s".formatted(event.eventType()), e);
        }
    }

    private static Map<String, Object> itinerary(ItinerarySummary itinerary) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("origin", itinerary.origin().value());
        node.put("destination", itinerary.destination().value());
        node.put("firstDeparture", itinerary.firstDeparture().toString());
        node.put("segmentCount", itinerary.segmentCount());
        Map<String, Object> price = new LinkedHashMap<>();
        // String, no número: ver el javadoc de la clase.
        price.put("amount", itinerary.price().amount().toPlainString());
        price.put("currency", itinerary.price().currency());
        node.put("price", price);
        return node;
    }

    /** Vuelve a leer un payload ya serializado. Lo usa el consumidor. */
    public ObjectNode parse(String payload) throws JsonProcessingException {
        return (ObjectNode) objectMapper.readTree(payload);
    }
}
