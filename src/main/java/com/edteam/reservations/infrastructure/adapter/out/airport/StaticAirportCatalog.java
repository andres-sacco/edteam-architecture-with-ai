package com.edteam.reservations.infrastructure.adapter.out.airport;

import com.edteam.reservations.application.port.out.AirportCatalogPort;
import com.edteam.reservations.domain.model.AirportCode;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maestro en memoria, con un set fijo de códigos.
 *
 * <p>Es el <strong>fallback</strong> del catálogo de ciudades: se usa cuando no
 * hay {@code reservations.airport-catalog.base-url} configurada, es decir en
 * los tests y en un arranque local sin el servicio de catálogo levantado. El
 * origen real es {@code CatalogAirportCatalog}, que consulta la API.
 *
 * <p>El set por defecto incluye las ciudades que resuelve esa API —para que
 * levantar sin el servicio no cambie qué pedidos se aceptan— más los códigos
 * que venían de antes. Ojo con eso: acá hay códigos que el catálogo real no
 * conoce, así que un pedido que pasa con el stub puede fallar con el servicio
 * prendido. Es la razón por la que el stub es fallback y no el modo normal de
 * trabajo.
 *
 * <p>En cualquiera de los dos casos se mantiene el decorador
 * {@link CachingAirportCatalog}: el maestro cambia muy poco y se consulta en
 * cada creación y modificación.
 */
public class StaticAirportCatalog implements AirportCatalogPort {

    private final Set<String> codes;

    public StaticAirportCatalog(Set<String> codes) {
        this.codes = codes.stream().map(String::toUpperCase).collect(Collectors.toUnmodifiableSet());
    }

    /** Set mínimo para poder levantar y probar la aplicación sin el catálogo. */
    public static StaticAirportCatalog withDefaults() {
        return new StaticAirportCatalog(Set.copyOf(Arrays.asList(
                // Ciudades que resuelve la API de catálogo.
                "BUE", "MIA", "SCL", "NYC", "PAR", "LON",
                // Códigos previos al catálogo: sirven para levantar y probar.
                "EZE", "AEP", "COR", "MDZ", "BRC",
                "GRU", "MVD", "LIM", "BOG",
                "MEX", "JFK", "MAD", "BCN")));
    }

    @Override
    public boolean exists(AirportCode code) {
        return code != null && codes.contains(code.value());
    }
}
