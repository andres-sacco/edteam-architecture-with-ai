package com.edteam.reservations.application.exception;

/**
 * No se pudo consultar el maestro de aeropuertos por una falla transitoria:
 * respuesta 5xx, 429 (nos están limitando) o error de red.
 *
 * <p>Se distingue de {@link AirportCatalogIntegrationException} porque acá el
 * pedido no tiene nada de malo: hacia afuera sale como 503 con
 * {@code Retry-After}, que es la única respuesta honesta —el cliente puede
 * volver a intentar y tiene sentido que lo haga—. Devolver 400 "aeropuerto
 * desconocido" cuando en realidad no pudimos preguntar sería mentirle.
 */
public class AirportCatalogUnavailableException extends AirportCatalogException {

    public AirportCatalogUnavailableException(String message) {
        super(message);
    }

    public AirportCatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
