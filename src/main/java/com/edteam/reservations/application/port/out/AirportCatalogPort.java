package com.edteam.reservations.application.port.out;

import com.edteam.reservations.domain.model.AirportCode;

/**
 * Puerto de salida hacia el maestro de aeropuertos.
 *
 * <p>Este puerto existe justamente porque la decisión está abierta: puede
 * resolverse con una tabla propia o con un proveedor externo. Los casos de uso
 * dependen de esta interfaz, así que la decisión se puede tomar —o cambiar—
 * sin modificar la lógica de negocio.
 *
 * <p>La operación se define por código individual (en lugar de "traer todos
 * los aeropuertos") para que la implementación pueda ser un {@code SELECT}
 * puntual o una llamada HTTP, y para poder decorarla con cache: ver
 * {@code CachingAirportCatalog}.
 */
public interface AirportCatalogPort {

    /**
     * @return {@code true} si el aeropuerto existe y está operativo en el maestro
     */
    boolean exists(AirportCode code);
}
