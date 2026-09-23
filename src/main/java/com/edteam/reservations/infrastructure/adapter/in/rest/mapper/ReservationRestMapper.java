package com.edteam.reservations.infrastructure.adapter.in.rest.mapper;

import com.edteam.reservations.application.port.in.CancelReservationCommand;
import com.edteam.reservations.application.port.in.CreateReservationCommand;
import com.edteam.reservations.application.port.in.ItineraryData;
import com.edteam.reservations.application.port.in.ModifyReservationCommand;
import com.edteam.reservations.application.port.in.PassengerData;
import com.edteam.reservations.application.port.in.SegmentData;
import com.edteam.reservations.domain.access.Actor;
import com.edteam.reservations.application.query.ReservationSearchCriteria;
import com.edteam.reservations.application.query.ReservationSortBy;
import com.edteam.reservations.application.query.ResultPage;
import com.edteam.reservations.application.query.SortDirection;
import com.edteam.reservations.domain.model.Email;
import com.edteam.reservations.domain.model.Itinerary;
import com.edteam.reservations.domain.model.Passenger;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationStatus;
import com.edteam.reservations.domain.model.Segment;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.CreateReservationRequest;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ItineraryRequest;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ItineraryResponse;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ListReservationsParams;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.MoneyResponse;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.PageMetadataResponse;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.PassengerRequest;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.PassengerResponse;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ReservationPageResponse;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ReservationResponse;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ReservationStatusDto;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.SegmentRequest;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.SegmentResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Traduce entre los DTOs del contrato HTTP y los tipos de la aplicación.
 *
 * <p>Es el equivalente de entrada a los mappers de persistencia, y existe por
 * el mismo motivo: que el agregado no se serialice. Sin esta capa, agregar un
 * campo al dominio cambiaría el JSON de todos los clientes, y renombrar uno
 * rompería a los que ya están en producción.
 *
 * <p>No valida: para cuando el mapper corre, Bean Validation ya rechazó los
 * datos malformados y el dominio se encarga de las reglas de negocio. Acá sólo
 * se traduce.
 */
@Component
public class ReservationRestMapper {

    // ------------------------------------------------------------------
    // HTTP -> aplicación
    // ------------------------------------------------------------------

    /**
     * El comprador no sale del cuerpo sino del {@code actor}, que el adaptador
     * obtuvo del token. Es la traducción que hace imposible reservar a nombre
     * de otro: no hay ningún camino por el que un dato del pedido termine
     * siendo la identidad del titular.
     */
    public CreateReservationCommand toCommand(CreateReservationRequest request,
                                              UUID idempotencyKey,
                                              Actor actor) {
        Objects.requireNonNull(request, "El pedido es obligatorio");
        Objects.requireNonNull(idempotencyKey, "La clave de idempotencia es obligatoria");
        Objects.requireNonNull(actor, "El solicitante es obligatorio");

        return new CreateReservationCommand(
                actor,
                idempotencyKey.toString(),
                toItineraryData(request.itinerary()),
                request.passengers().stream().map(ReservationRestMapper::toPassengerData).toList());
    }

    public ModifyReservationCommand toCommand(long reservationId,
                                              long expectedVersion,
                                              ItineraryRequest itinerary,
                                              Actor actor) {
        return new ModifyReservationCommand(reservationId, expectedVersion, toItineraryData(itinerary), actor);
    }

    public CancelReservationCommand toCancelCommand(long reservationId, long expectedVersion, Actor actor) {
        return new CancelReservationCommand(reservationId, expectedVersion, actor);
    }

    /**
     * Traduce los parámetros de consulta al criterio de búsqueda.
     *
     * <p>Acá es donde el vocabulario del contrato ({@code createdAt,desc}) se
     * convierte en el de la aplicación ({@code CREATED_AT} + {@code DESC}). El
     * formato admitido ya lo garantizó la validación del DTO.
     */
    public ReservationSearchCriteria toCriteria(ListReservationsParams params) {
        Objects.requireNonNull(params, "Los parámetros son obligatorios");

        String[] sort = params.sort().split(",", 2);
        ReservationSortBy sortBy = "firstDepartureAt".equals(sort[0])
                ? ReservationSortBy.FIRST_DEPARTURE_AT
                : ReservationSortBy.CREATED_AT;
        SortDirection direction = "asc".equals(sort[1]) ? SortDirection.ASC : SortDirection.DESC;

        Set<ReservationStatus> statuses = params.status().stream()
                .map(ReservationStatusDto::toDomain)
                .collect(Collectors.toUnmodifiableSet());

        return new ReservationSearchCriteria(
                // Un 'userId=' vacío significa "sin filtro", no "usuario con email
                // vacío". Y "sin filtro" ya no significa "todas": el caso de uso
                // reduce el criterio al alcance del solicitante antes de consultar.
                Optional.ofNullable(params.userId())
                        .filter(email -> !email.isBlank())
                        .map(Email::of),
                statuses,
                Optional.ofNullable(params.departureFrom()),
                Optional.ofNullable(params.departureTo()),
                params.page(),
                params.size(),
                sortBy,
                direction);
    }

    private static ItineraryData toItineraryData(ItineraryRequest request) {
        return new ItineraryData(
                new BigDecimal(request.price()),
                request.currency().toUpperCase(Locale.ROOT),
                request.segments().stream().map(ReservationRestMapper::toSegmentData).toList());
    }

    private static SegmentData toSegmentData(SegmentRequest request) {
        return new SegmentData(
                request.originAirportCode(),
                request.destinationAirportCode(),
                request.airline(),
                request.departureAt());
    }

    private static PassengerData toPassengerData(PassengerRequest request) {
        return new PassengerData(
                request.firstName(),
                request.lastName(),
                request.birthDate(),
                request.documentNumber());
    }

    // ------------------------------------------------------------------
    // Aplicación -> HTTP
    // ------------------------------------------------------------------

    public ReservationResponse toResponse(Reservation reservation) {
        Objects.requireNonNull(reservation, "La reserva es obligatoria");

        return new ReservationResponse(
                reservation.requireId().toString(),
                ReservationStatusDto.from(reservation.status()),
                reservation.user().email().value(),
                toResponse(reservation.itinerary()),
                reservation.passengers().stream().map(ReservationRestMapper::toResponse).toList(),
                reservation.createdAt(),
                reservation.updatedAt(),
                cancelledAt(reservation));
    }

    public ReservationPageResponse toResponse(ResultPage<Reservation> page) {
        Objects.requireNonNull(page, "La página es obligatoria");

        return new ReservationPageResponse(
                page.items().stream().map(this::toResponse).toList(),
                new PageMetadataResponse(page.page(), page.size(), page.totalElements(), page.totalPages()));
    }

    /**
     * La cancelación no tiene fecha propia en el modelo de datos, pero no hace
     * falta: una reserva cancelada no admite más operaciones, así que su
     * última actualización <em>es</em> el momento en que se canceló.
     */
    private static Instant cancelledAt(Reservation reservation) {
        return reservation.status() == ReservationStatus.CANCELLED ? reservation.updatedAt() : null;
    }

    private static ItineraryResponse toResponse(Itinerary itinerary) {
        List<Segment> segments = itinerary.segments();
        List<SegmentResponse> segmentResponses = new ArrayList<>(segments.size());
        for (int index = 0; index < segments.size(); index++) {
            segmentResponses.add(toResponse(segments.get(index), index + 1));
        }

        return new ItineraryResponse(
                itinerary.id().map(Object::toString).orElse(null),
                new MoneyResponse(itinerary.price().amount().toPlainString(), itinerary.price().currency()),
                itinerary.origin().value(),
                itinerary.destination().value(),
                itinerary.firstDeparture(),
                List.copyOf(segmentResponses));
    }

    private static SegmentResponse toResponse(Segment segment, int position) {
        return new SegmentResponse(
                segment.id().map(Object::toString).orElse(null),
                position,
                segment.origin().value(),
                segment.destination().value(),
                segment.airline(),
                segment.departureAt());
    }

    private static PassengerResponse toResponse(Passenger passenger) {
        return new PassengerResponse(
                passenger.id().map(Object::toString).orElse(null),
                passenger.firstName(),
                passenger.lastName(),
                passenger.birthDate(),
                passenger.documentNumber().orElse(null));
    }
}
