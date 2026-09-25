package com.edteam.reservations.application.service;

import com.edteam.reservations.application.port.in.CreateReservationCommand;
import com.edteam.reservations.application.port.in.CreateReservationResult;
import com.edteam.reservations.application.port.in.CreateReservationUseCase;
import com.edteam.reservations.domain.model.IdempotencyKey;
import com.edteam.reservations.domain.model.Itinerary;
import com.edteam.reservations.domain.model.Passenger;
import com.edteam.reservations.domain.model.Reservation;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Alta de una reserva, o recuperación de la que ya existe para esa clave de
 * idempotencia.
 *
 * <h2>Dos cambios de orden que son de seguridad, no de estilo</h2>
 * <ol>
 *   <li><b>El comprador sale del token.</b> Ya no hay {@code UserData} en el
 *       comando: el usuario se arma con el {@code actor}. Con el email en el
 *       cuerpo, cualquiera reservaba a nombre de {@code victima@ejemplo.com} y
 *       la notificación de «tu reserva» le llegaba a la víctima desde nuestro
 *       canal.</li>
 *   <li><b>La clave de idempotencia se resuelve dentro del usuario.</b> Eso
 *       obliga a buscarlo antes de validar el pedido —no a darlo de alta:
 *       el alta sigue ocurriendo al final, cuando el pedido ya pasó todas las
 *       validaciones, para que un pedido inválido no deje una fila en el
 *       maestro de usuarios.</li>
 * </ol>
 *
 * <h2>Un cambio de orden que es de disponibilidad</h2>
 * <b>Este método no es transaccional.</b> Primero se arma y se valida el
 * itinerario —lo que incluye la llamada HTTP al maestro de aeropuertos— y sólo
 * después se abre la transacción, en
 * {@link CreateReservationTransaction#apply}. Con la llamada adentro, un
 * catálogo lento retenía una conexión del pool durante todos sus reintentos, y
 * unas pocas reservas concurrentes agotaban el pool y tiraban la API completa,
 * incluidas las lecturas que no tocan el catálogo. Es el mismo criterio que ya
 * gobierna al resto de las integraciones: degradar, no propagar.
 */
@Service
public class CreateReservationService implements CreateReservationUseCase {

    private final ItineraryAssembler itineraryAssembler;
    private final AirportExistenceValidator airportValidator;
    private final ReservationIdempotencyLookup idempotencyLookup;
    private final CreateReservationTransaction transaction;
    private final Clock clock;

    CreateReservationService(
            ItineraryAssembler itineraryAssembler,
            AirportExistenceValidator airportValidator,
            ReservationIdempotencyLookup idempotencyLookup,
            CreateReservationTransaction transaction,
            Clock clock) {
        this.itineraryAssembler = Objects.requireNonNull(itineraryAssembler);
        this.airportValidator = Objects.requireNonNull(airportValidator);
        this.idempotencyLookup = Objects.requireNonNull(idempotencyLookup);
        this.transaction = Objects.requireNonNull(transaction);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public CreateReservationResult create(CreateReservationCommand command) {
        Objects.requireNonNull(command, "El comando es obligatorio");
        Instant now = clock.instant();

        // La clave de idempotencia PRIMERO. Si esta reserva ya existe no hay
        // nada que validar, y el reintento del usuario —que con el catálogo
        // degradado es lo más probable que pase— cuesta una lectura por índice
        // en lugar de otra tanda entera de consultas contra un proveedor que
        // ya está sufriendo. La clave protegía la base; esto protege también
        // al tercero.
        Optional<Reservation> existing =
                idempotencyLookup.findExisting(command.actor().email(), IdempotencyKey.of(command.idempotencyKey()));
        if (existing.isPresent()) {
            return CreateReservationResult.alreadyExisted(existing.get());
        }

        // Fuera de la transacción, a propósito: acá está la red.
        Itinerary itinerary = itineraryAssembler.toItinerary(command.itinerary());
        airportValidator.validate(itinerary);
        List<Passenger> passengers = itineraryAssembler.toPassengers(command.passengers());

        // La transacción vuelve a chequear la clave: entre la lectura de
        // arriba y la escritura pueden haber pasado segundos, y la carrera de
        // dos pedidos simultáneos con la misma clave sólo la resuelve el
        // UNIQUE de la base.
        return transaction.apply(command, itinerary, passengers, now);
    }
}
