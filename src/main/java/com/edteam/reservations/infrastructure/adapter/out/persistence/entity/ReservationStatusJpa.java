package com.edteam.reservations.infrastructure.adapter.out.persistence.entity;

import com.edteam.reservations.domain.model.ReservationStatus;

/**
 * Valores de la columna {@code reserva.estado}, tal como los define el
 * {@code CHECK} del modelo de datos.
 *
 * <p>Existe para que el dominio no quede atado a los literales de la base: el
 * enum del dominio está en inglés como el resto del código, y la traducción
 * vive acá, del lado de la infraestructura.
 */
public enum ReservationStatusJpa {

    PENDIENTE(ReservationStatus.PENDING),
    CONFIRMADA(ReservationStatus.CONFIRMED),
    CANCELADA(ReservationStatus.CANCELLED);

    private final ReservationStatus domainStatus;

    ReservationStatusJpa(ReservationStatus domainStatus) {
        this.domainStatus = domainStatus;
    }

    public ReservationStatus toDomain() {
        return domainStatus;
    }

    public static ReservationStatusJpa fromDomain(ReservationStatus status) {
        for (ReservationStatusJpa candidate : values()) {
            if (candidate.domainStatus == status) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Estado de reserva sin equivalente en la base: " + status);
    }
}
