package com.edteam.reservations.application.exception;

/**
 * El catálogo respondió {@code 429}: nos está pidiendo explícitamente que
 * bajemos el ritmo.
 *
 * <p>Es un caso de {@link AirportCatalogUnavailableException} —hacia afuera
 * sale el mismo {@code 503} con {@code Retry-After}, y el
 * <em>stale-while-error</em> lo trata igual— pero se distingue por una razón
 * concreta: <strong>cuenta para el circuito y no se reintenta</strong>.
 * Reintentar un {@code 429} es desobedecer al proveedor y empeorar su
 * saturación; contarlo es lo que hace que el circuito abra y pare el tráfico
 * de verdad.
 *
 * <p>Que sea una excepción de la capa de aplicación y no de infraestructura es
 * lo que permite tomar esa decisión sin que ningún servicio importe la
 * librería de resiliencia: la regla vive en el clasificador de fallos, que es
 * infraestructura, y se apoya sólo en el tipo.
 */
public class AirportCatalogThrottledException extends AirportCatalogUnavailableException {

    public AirportCatalogThrottledException(String message) {
        super(message);
    }

    public AirportCatalogThrottledException(String message, Throwable cause) {
        super(message, cause);
    }
}
