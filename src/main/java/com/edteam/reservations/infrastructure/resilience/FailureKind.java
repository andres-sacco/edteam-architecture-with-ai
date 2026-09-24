package com.edteam.reservations.infrastructure.resilience;

/**
 * Qué clase de falla es una excepción, desde el punto de vista de la política
 * de resiliencia. Es la única dimensión que el circuito mira.
 */
public enum FailureKind {

    /**
     * El problema es del proveedor y puede haberse resuelto solo: 5xx, timeout,
     * conexión rechazada, {@code nack} del broker, cualquier error de Lettuce.
     * <strong>Cuenta para el circuito.</strong>
     */
    TRANSIENT,

    /**
     * El mismo pedido va a dar el mismo resultado hasta que alguien cambie
     * algo: credencial vencida, cuerpo fuera de contrato, binding que falta,
     * un bug nuestro. <strong>No cuenta y no se reintenta.</strong>
     *
     * <p>Que no cuente no es una concesión: un circuito abierto <em>esconde</em>
     * un fallo permanente detrás de un 503 genérico y retrasa el diagnóstico
     * del único caso que ningún mecanismo automático resuelve.
     */
    PERMANENT,

    /**
     * Lo rechazamos nosotros, no el proveedor: circuito abierto, presupuesto
     * del itinerario agotado. <strong>No cuenta</strong> —el circuito no
     * cuenta sus propios rechazos— <strong>y no se reintenta</strong>.
     */
    SHED
}
