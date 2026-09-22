package com.edteam.reservations.application.query;

import java.util.List;
import java.util.Objects;

/**
 * Una página de resultados, expresada en tipos propios de la aplicación.
 *
 * <p>Existe para que los casos de uso puedan paginar sin conocer
 * {@code org.springframework.data.domain.Page}: ese tipo es del proveedor de
 * persistencia y, si cruzara el puerto, la aplicación quedaría atada a Spring
 * Data y el adaptador REST terminaría serializando su estructura interna.
 *
 * @param items         elementos de la página, ya en el orden pedido
 * @param page          número de página, base 0
 * @param size          tamaño de página solicitado
 * @param totalElements cantidad total de elementos que cumplen el filtro
 */
public record ResultPage<T>(List<T> items, int page, int size, long totalElements) {

    public ResultPage {
        Objects.requireNonNull(items, "Los elementos son obligatorios");
        if (page < 0) {
            throw new IllegalArgumentException("El número de página no puede ser negativo");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("El tamaño de página debe ser positivo");
        }
        if (totalElements < 0) {
            throw new IllegalArgumentException("El total de elementos no puede ser negativo");
        }
        items = List.copyOf(items);
    }

    /** Página vacía, conservando los parámetros con los que se consultó. */
    public static <T> ResultPage<T> empty(int page, int size) {
        return new ResultPage<>(List.of(), page, size, 0L);
    }

    /** Cantidad total de páginas para el tamaño pedido. */
    public int totalPages() {
        return (int) Math.ceilDiv(totalElements, size);
    }
}
