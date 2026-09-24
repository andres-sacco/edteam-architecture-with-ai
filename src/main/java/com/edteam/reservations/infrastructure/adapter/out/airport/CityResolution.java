package com.edteam.reservations.infrastructure.adapter.out.airport;

import java.util.Objects;

/**
 * Qué se pudo averiguar de una ciudad. Tres resultados y no dos: la diferencia
 * entre «no existe» y «no pude averiguarlo» es la que separa un
 * {@code 400 UNKNOWN_AIRPORT} —que le dice al usuario que corrija algo— de un
 * {@code 503} reintentable.
 *
 * <p>Es un tipo de infraestructura y no cruza el puerto: hacia la aplicación
 * sale el conjunto de códigos desconocidos, o una excepción. Existe para que
 * la resolución pueda ser <strong>por ciudad</strong>: con una sola excepción
 * para todo el itinerario, un fallo en la séptima ciudad tiraría abajo las
 * seis que sí se habían resuelto y el fallback no tendría a qué agarrarse.
 */
public record CityResolution(Status status, String reason) {

    public enum Status {
        /** El catálogo la conoce. */
        EXISTS,
        /** El catálogo respondió que no existe: {@code 404} o {@code 200} vacío. */
        ABSENT,
        /** No se pudo averiguar. El motivo va en {@link #reason()} y termina en la métrica. */
        UNAVAILABLE
    }

    public CityResolution {
        Objects.requireNonNull(status, "El estado es obligatorio");
    }

    private static final CityResolution EXISTS = new CityResolution(Status.EXISTS, null);
    private static final CityResolution ABSENT = new CityResolution(Status.ABSENT, null);

    /** No se llama {@code exists()} para no chocar con el accesor de abajo. */
    public static CityResolution present() {
        return EXISTS;
    }

    public static CityResolution absent() {
        return ABSENT;
    }

    public static CityResolution unavailable(String reason) {
        return new CityResolution(Status.UNAVAILABLE, reason == null ? "desconocido" : reason);
    }

    public static CityResolution of(boolean exists) {
        return exists ? EXISTS : ABSENT;
    }

    public boolean isKnown() {
        return status != Status.UNAVAILABLE;
    }

    public boolean exists() {
        return status == Status.EXISTS;
    }
}
