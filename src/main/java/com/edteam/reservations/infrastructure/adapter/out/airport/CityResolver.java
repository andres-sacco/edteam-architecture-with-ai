package com.edteam.reservations.infrastructure.adapter.out.airport;

import java.util.Collection;
import java.util.Map;

/**
 * Resuelve un conjunto de códigos contra el origen, sin cache y sin fallback.
 *
 * <p>Es la frontera interna del adaptador del catálogo: por arriba está
 * {@code CachingAirportCatalog}, que implementa el puerto y decide qué
 * contestar; por abajo, el presupuesto, el circuito, el bulkhead, el retry y
 * el cliente HTTP.
 *
 * <p>Devuelve un mapa y no lanza: un fallo de una ciudad es
 * {@link CityResolution.Status#UNAVAILABLE} para esa ciudad y no invalida las
 * demás. Quien decide si eso se puede tapar con un valor viejo o tiene que
 * salir como {@code 503} es la capa de arriba, que es la única que sabe qué
 * hay guardado.
 */
@FunctionalInterface
public interface CityResolver {

    /**
     * @param codes códigos en mayúsculas; puede venir vacío
     * @return una entrada por cada código pedido, siempre
     */
    Map<String, CityResolution> resolve(Collection<String> codes);
}
