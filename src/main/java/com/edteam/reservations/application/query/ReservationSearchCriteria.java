package com.edteam.reservations.application.query;

import com.edteam.reservations.domain.model.Email;
import com.edteam.reservations.domain.model.ReservationStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Filtros, orden y paginación de una búsqueda de reservas.
 *
 * <p>Es el lenguaje con el que la aplicación le pide un listado al repositorio.
 * Deliberadamente no viaja un {@code Specification} ni un {@code Pageable}: el
 * criterio se expresa en tipos propios y es el adaptador el que decide cómo
 * traducirlo a su tecnología. Así el caso de uso se puede testear sin JPA, y
 * cambiar de proveedor no obliga a tocar la aplicación.
 *
 * <p>Los filtros vacíos significan "sin filtrar": {@code Optional.empty()} o un
 * conjunto vacío no restringen el resultado.
 *
 * @param userEmail     dueño de las reservas, si se filtra por usuario. Es el
 *                      email y no el id interno: es lo que el cliente conoce
 * @param statuses      estados admitidos; vacío significa todos
 * @param departureFrom cota inferior para la salida del primer tramo
 * @param departureTo   cota superior para la salida del primer tramo
 * @param page          número de página, base 0
 * @param size          tamaño de página
 * @param sortBy        campo de orden
 * @param direction     sentido del orden
 */
public record ReservationSearchCriteria(Optional<Email> userEmail,
                                        Set<ReservationStatus> statuses,
                                        Optional<Instant> departureFrom,
                                        Optional<Instant> departureTo,
                                        int page,
                                        int size,
                                        ReservationSortBy sortBy,
                                        SortDirection direction) {

    /** Cota dura del tamaño de página: protege a la base de un pedido abusivo. */
    public static final int MAX_PAGE_SIZE = 100;

    public static final int DEFAULT_PAGE_SIZE = 20;

    public ReservationSearchCriteria {
        Objects.requireNonNull(userEmail, "El usuario es obligatorio (usar Optional.empty() si no se filtra)");
        Objects.requireNonNull(statuses, "Los estados son obligatorios (usar un conjunto vacío si no se filtra)");
        Objects.requireNonNull(departureFrom, "departureFrom es obligatorio (usar Optional.empty() si no se filtra)");
        Objects.requireNonNull(departureTo, "departureTo es obligatorio (usar Optional.empty() si no se filtra)");
        Objects.requireNonNull(sortBy, "El campo de orden es obligatorio");
        Objects.requireNonNull(direction, "El sentido del orden es obligatorio");

        if (page < 0) {
            throw new IllegalArgumentException("El número de página no puede ser negativo");
        }
        if (size <= 0 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "El tamaño de página debe estar entre 1 y %d".formatted(MAX_PAGE_SIZE));
        }
        if (departureFrom.isPresent() && departureTo.isPresent()
                && departureFrom.get().isAfter(departureTo.get())) {
            throw new IllegalArgumentException("departureFrom no puede ser posterior a departureTo");
        }
        statuses = Set.copyOf(statuses);
    }

    /** Primera página sin filtros, ordenada por fecha de alta descendente. */
    public static ReservationSearchCriteria unfiltered() {
        return new ReservationSearchCriteria(Optional.empty(), Set.of(), Optional.empty(), Optional.empty(),
                0, DEFAULT_PAGE_SIZE, ReservationSortBy.CREATED_AT, SortDirection.DESC);
    }

    /** {@code true} si hay que restringir por estado. */
    /**
     * El mismo criterio, pero filtrando por el usuario indicado.
     *
     * <p>Lo usa {@code ListReservationsService} para reducir lo que pidió el
     * cliente al alcance que le corresponde. Es una copia y no una mutación:
     * el criterio pedido se conserva, así el test puede comparar uno contra
     * otro y comprobar que la reducción efectivamente ocurrió.
     *
     * @param owner el filtro efectivo; {@code Optional.empty()} deja ver todo,
     *              que es un privilegio y no un default
     */
    public ReservationSearchCriteria restrictedTo(Optional<Email> owner) {
        Objects.requireNonNull(owner, "El filtro es obligatorio (usar Optional.empty() si no se filtra)");
        return new ReservationSearchCriteria(owner, statuses, departureFrom, departureTo,
                page, size, sortBy, direction);
    }

    public boolean filtersByStatus() {
        return !statuses.isEmpty();
    }

    /** Desplazamiento de la primera fila de la página. */
    public int offset() {
        return page * size;
    }
}
