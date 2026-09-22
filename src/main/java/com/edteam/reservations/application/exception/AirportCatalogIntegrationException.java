package com.edteam.reservations.application.exception;

/**
 * El maestro de aeropuertos respondió, pero la integración está rota: un 4xx
 * que no es 404 (credencial vencida, sin permisos, pedido malformado) o un
 * cuerpo que no coincide con el contrato publicado.
 *
 * <p>Es un defecto nuestro o del proveedor, no una falla pasajera: por eso se
 * loguea con el estado y un extracto del cuerpo, y hacia afuera sale como 500
 * genérico. No hay nada que el cliente de la API pueda corregir reintentando.
 */
public class AirportCatalogIntegrationException extends AirportCatalogException {

    public AirportCatalogIntegrationException(String message) {
        super(message);
    }

    public AirportCatalogIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
