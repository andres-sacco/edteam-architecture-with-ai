package com.edteam.reservations.infrastructure.adapter.out.airport.catalog;

import com.edteam.reservations.application.exception.AirportCatalogIntegrationException;
import com.edteam.reservations.application.exception.AirportCatalogUnavailableException;
import java.util.Optional;

/**
 * Cliente de la API de catálogo ({@code GET /city/{code}}).
 *
 * <p>Es una interfaz propia del adaptador y no un puerto de la aplicación: el
 * puerto es {@link com.edteam.reservations.application.port.out.AirportCatalogPort},
 * que habla de "existe o no existe". Esta interfaz existe un escalón más
 * abajo, para poder testear la traducción HTTP contra un servidor simulado y
 * para poder reemplazar el transporte —o al proveedor— sin tocar la clase que
 * implementa el puerto.
 *
 * <h2>Contrato de fallos</h2>
 * El resultado "vacío" es el catálogo diciendo que no conoce el código: un 404
 * según el contrato publicado, o un 200 sin cuerpo, que es como contesta hoy
 * el servicio. Todo lo demás es una excepción, porque confundir "no existe"
 * con "no pude preguntar" es lo que después se traduce en una reserva
 * rechazada por un dato que en realidad era válido.
 *
 * <p>Un cuerpo presente pero que no cumple el contrato no entra en "vacío":
 * eso falla.
 */
public interface CityCatalogClient {

    /**
     * @param code código de la ciudad a resolver (por ejemplo {@code BUE})
     * @return la ciudad si la API respondió 200; vacío si respondió 404
     * @throws AirportCatalogUnavailableException  si la API respondió 5xx o 429,
     *                                             o si falló la conexión (fallo transitorio)
     * @throws AirportCatalogIntegrationException  si respondió otro 4xx —credencial,
     *                                             permisos, pedido malformado— o un cuerpo que
     *                                             no cumple el contrato: ilegible o sin
     *                                             {@code code}
     */
    Optional<CatalogCity> findByCode(String code);
}
