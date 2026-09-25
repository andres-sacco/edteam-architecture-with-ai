package com.edteam.reservations.application.port.out;

import com.edteam.reservations.domain.model.AirportCode;
import java.util.Collection;
import java.util.Set;

/**
 * Maestro de aeropuertos: la fuente de verdad de qué códigos existen.
 *
 * <p>El puerto pregunta por el <strong>conjunto entero</strong> del itinerario
 * y no de a un código por vez, y eso no es un detalle de comodidad. Mientras
 * la pregunta fue {@code boolean exists(AirportCode)}, el adaptador no podía
 * hacer nada mejor que un bucle en serie: el peor caso de un itinerario era la
 * <em>suma</em> del peor caso de cada ciudad, y no había ningún lugar donde
 * poner un techo de tiempo para el pedido completo. Con la pregunta en bloque,
 * el adaptador puede agrupar la lectura de cache, resolver en paralelo lo que
 * falta y cortar por presupuesto — sin que el caso de uso se entere de nada de
 * eso.
 *
 * <p>Devuelve los códigos que <strong>no existen</strong>, no los que existen:
 * es la respuesta que el validador necesita y evita que el llamador tenga que
 * calcular una diferencia de conjuntos para descubrirlo.
 *
 * <p>Si el maestro no se puede consultar y no hay nada con qué responder, el
 * adaptador lanza {@code AirportCatalogUnavailableException}. Nunca devuelve
 * un código como inexistente por no haber podido averiguarlo: eso rechazaría
 * una reserva válida con un error que el cliente no puede corregir.
 */
public interface AirportCatalogPort {

    /**
     * @param codes los códigos a verificar; puede venir vacío
     * @return el subconjunto de {@code codes} que el maestro no conoce
     * @throws com.edteam.reservations.application.exception.AirportCatalogUnavailableException
     *         si alguno de los códigos no se pudo resolver ni contra el origen
     *         ni contra el último valor conocido
     */
    Set<AirportCode> unknown(Collection<AirportCode> codes);
}
