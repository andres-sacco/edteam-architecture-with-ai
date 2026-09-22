package com.edteam.reservations.application.exception;

/**
 * Raíz de los fallos al consultar el maestro de aeropuertos.
 *
 * <p>Vive en la capa de aplicación —y no en el adaptador— porque es parte del
 * contrato del puerto {@code AirportCatalogPort}: quien depende del puerto
 * tiene que poder distinguir "el maestro dijo que no existe" de "no pude
 * preguntarle", sin enterarse de que del otro lado hay HTTP.
 *
 * <p>Hay dos subtipos y la diferencia es operativa, no cosmética:
 *
 * <ul>
 *   <li>{@link AirportCatalogUnavailableException}: <em>transitorio</em>. El
 *       proveedor está caído o nos está limitando; el mismo pedido puede
 *       funcionar dentro de unos segundos.</li>
 *   <li>{@link AirportCatalogIntegrationException}: <em>defecto</em>. La
 *       credencial, la URL o el contrato están mal de nuestro lado; repetir el
 *       pedido va a fallar igual y lo que hace falta es un cambio.</li>
 * </ul>
 */
public abstract class AirportCatalogException extends ApplicationException {

    protected AirportCatalogException(String message) {
        super(message);
    }

    protected AirportCatalogException(String message, Throwable cause) {
        super(message, cause);
    }
}
