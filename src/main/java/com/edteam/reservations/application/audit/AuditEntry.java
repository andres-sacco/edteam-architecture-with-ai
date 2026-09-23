package com.edteam.reservations.application.audit;

import com.edteam.reservations.domain.model.Email;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Una línea del registro de auditoría: quién hizo qué sobre qué recurso, y con
 * qué resultado.
 *
 * <p>Lo que <b>no</b> lleva es tan importante como lo que lleva. No hay
 * correlation id, ni IP, ni user agent: son datos del transporte, y la capa de
 * aplicación no sabe que existe HTTP. Los agrega el adaptador que escribe la
 * fila, que sí está del lado en el que esas cosas tienen sentido. Tampoco hay
 * un solo dato de pasajero: la auditoría prueba <em>quién tocó qué</em>, no
 * repite el contenido, porque si lo repitiera pasaría a ser una segunda copia
 * de la PII con su propia política de retención.
 *
 * @param action           la operación
 * @param actor            identidad de quien la pidió
 * @param reservationId    recurso alcanzado, como string opaco
 * @param outcome          si se concretó o se rechazó
 * @param resourceVersion  versión resultante del recurso, cuando la operación escribió
 * @param occurredAt       instante, del reloj inyectado y no del de la base
 */
public record AuditEntry(AuditAction action,
                         Email actor,
                         String reservationId,
                         AuditOutcome outcome,
                         Optional<Long> resourceVersion,
                         Instant occurredAt) {

    public AuditEntry {
        Objects.requireNonNull(action, "La acción es obligatoria");
        Objects.requireNonNull(actor, "El actor es obligatorio");
        Objects.requireNonNull(reservationId, "El recurso es obligatorio");
        Objects.requireNonNull(outcome, "El resultado es obligatorio");
        Objects.requireNonNull(resourceVersion,
                "La versión es obligatoria (usar Optional.empty() si la operación no escribió)");
        Objects.requireNonNull(occurredAt, "El instante es obligatorio");
    }

    /** Operación que se concretó y dejó el recurso en {@code version}. */
    public static AuditEntry allowed(AuditAction action, Email actor, String reservationId,
                                     long version, Instant occurredAt) {
        return new AuditEntry(action, actor, reservationId, AuditOutcome.ALLOWED,
                Optional.of(version), occurredAt);
    }

    /** Intento rechazado. No hay versión: no se escribió nada. */
    public static AuditEntry denied(AuditAction action, Email actor, String reservationId, Instant occurredAt) {
        return new AuditEntry(action, actor, reservationId, AuditOutcome.DENIED,
                Optional.empty(), occurredAt);
    }
}
