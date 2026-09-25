package com.edteam.reservations.infrastructure.adapter.out.airport;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maestro de aeropuertos en memoria: el camino de «arrancar sin catálogo».
 *
 * <p>Se usa cuando no hay {@code reservations.airport-catalog.base-url}, que
 * es el modo de los tests y del arranque local. Es el mismo criterio que con
 * Redis y con el broker: la aplicación tiene que levantar y poder probarse sin
 * las dependencias externas.
 *
 * <p>Resuelve el conjunto entero de una vez, igual que el resolutor real: así
 * la cadena de arriba —cache, presupuesto, fallback— es la misma en los dos
 * modos y lo que se prueba en local es lo que corre en producción.
 */
public class StaticAirportCatalog implements CityResolver {

    private final Set<String> codes;

    public StaticAirportCatalog(Set<String> codes) {
        this.codes = codes.stream().map(String::toUpperCase).collect(Collectors.toUnmodifiableSet());
    }

    /** Los códigos que resuelve la API de catálogo más los previos a ella. */
    public static StaticAirportCatalog withDefaults() {
        return new StaticAirportCatalog(Set.copyOf(Arrays.asList(
                // Ciudades que resuelve la API de catálogo.
                "BUE",
                "MIA",
                "SCL",
                "NYC",
                "PAR",
                "LON",
                // Códigos previos al catálogo: sirven para levantar y probar.
                "EZE",
                "AEP",
                "COR",
                "MDZ",
                "BRC",
                "GRU",
                "MVD",
                "LIM",
                "BOG",
                "MEX",
                "JFK",
                "MAD",
                "BCN")));
    }

    @Override
    public Map<String, CityResolution> resolve(Collection<String> requested) {
        Map<String, CityResolution> resolutions = new LinkedHashMap<>();
        for (String code : requested) {
            resolutions.put(code, CityResolution.of(code != null && codes.contains(code.toUpperCase())));
        }
        return resolutions;
    }
}
