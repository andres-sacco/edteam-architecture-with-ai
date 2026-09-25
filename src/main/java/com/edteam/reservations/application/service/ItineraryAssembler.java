package com.edteam.reservations.application.service;

import com.edteam.reservations.application.port.in.ItineraryData;
import com.edteam.reservations.application.port.in.PassengerData;
import com.edteam.reservations.application.port.in.SegmentData;
import com.edteam.reservations.domain.access.Actor;
import com.edteam.reservations.domain.model.AirportCode;
import com.edteam.reservations.domain.model.Itinerary;
import com.edteam.reservations.domain.model.Money;
import com.edteam.reservations.domain.model.Passenger;
import com.edteam.reservations.domain.model.Segment;
import com.edteam.reservations.domain.model.User;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Traduce los datos de entrada de los casos de uso a value objects del dominio.
 *
 * <p>Está separado de los servicios porque la creación y la modificación
 * necesitan exactamente la misma traducción: es el único lugar donde se decide
 * cómo un {@link ItineraryData} se convierte en un {@link Itinerary}, con todas
 * las validaciones del dominio disparándose en el camino.
 *
 * <p>Los objetos que produce no tienen id: son candidatos a persistir. El
 * adaptador de persistencia se encarga de reutilizar las filas que ya existan.
 */
@Component
public class ItineraryAssembler {

    public Itinerary toItinerary(ItineraryData data) {
        Objects.requireNonNull(data, "Los datos del itinerario son obligatorios");

        List<Segment> segments =
                data.segments().stream().map(ItineraryAssembler::toSegment).toList();

        return Itinerary.newItinerary(new Money(data.price(), data.currency()), segments);
    }

    /**
     * Candidato a usuario, todavía sin id.
     *
     * <p>{@code registeredAt} lo decide el caso de uso a partir del reloj
     * inyectado, no el cliente: es un dato del sistema. Si el usuario ya
     * existe, el adaptador de salida conserva el que tenía.
     */
    /**
     * Arma el usuario a partir de la identidad del solicitante.
     *
     * <p>Antes tomaba un {@code UserData} del cuerpo del pedido. Ahora el
     * único origen posible es el token: el email, el nombre y el apellido son
     * los que afirmó el emisor, no los que escribió el cliente.
     */
    public User toUser(Actor actor, Instant now) {
        Objects.requireNonNull(actor, "El solicitante es obligatorio");
        return User.newUser(actor.email(), actor.firstName(), actor.lastName(), now);
    }

    public List<Passenger> toPassengers(List<PassengerData> data) {
        Objects.requireNonNull(data, "Los datos de los pasajeros son obligatorios");

        return data.stream().map(ItineraryAssembler::toPassenger).toList();
    }

    private static Segment toSegment(SegmentData data) {
        Objects.requireNonNull(data, "Los datos del segmento son obligatorios");
        return Segment.newSegment(
                AirportCode.of(data.originAirportCode()),
                AirportCode.of(data.destinationAirportCode()),
                data.airline(),
                data.departureAt());
    }

    private static Passenger toPassenger(PassengerData data) {
        Objects.requireNonNull(data, "Los datos del pasajero son obligatorios");
        return Passenger.newPassenger(data.firstName(), data.lastName(), data.birthDate(), data.documentNumber());
    }
}
