package com.edteam.reservations.infrastructure.adapter.out.airport.catalog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * El presupuesto de tiempo del itinerario, visible desde adentro de la cadena.
 *
 * <p>El techo lo pone el fan-out, que es quien conoce el itinerario completo.
 * Pero quien tiene que <em>obedecerlo</em> con grano fino es el retry, dos
 * capas más abajo: sin esto, un segundo intento arrancado a 1,5 s del
 * presupuesto de 1,6 s se lanza igual y se lleva puesto el techo del pedido.
 * Con esto, el retry pregunta «¿queda tiempo para un intento completo?» y, si
 * no queda, se rinde temprano — que es el primer recorte de la lista de qué se
 * sacrifica cuando el presupuesto no alcanza, y el que no cambia el resultado.
 *
 * <p>Se propaga por {@link ThreadLocal} y no por parámetro porque atravesaría
 * cuatro firmas —{@code CityCatalogClient} incluida, que es la interfaz que
 * los tests del cliente HTTP usan— sólo para llevar un dato que es del pedido
 * y no de la llamada. El fan-out lo instala <em>dentro</em> de cada tarea, así
 * que cada hilo virtual ve el mismo vencimiento y ninguno lo hereda por
 * accidente.
 *
 * <p>Sin presupuesto instalado no hay corte: los caminos que no pasan por el
 * fan-out —un test del cliente, una llamada suelta— se comportan como antes.
 */
public final class CatalogDeadline {

    private static final ThreadLocal<Instant> DEADLINE = new ThreadLocal<>();

    private CatalogDeadline() {
    }

    /** Ejecuta {@code body} con el vencimiento instalado, y lo quita al salir. */
    public static <T> T within(Instant deadline, Supplier<T> body) {
        Instant previous = DEADLINE.get();
        DEADLINE.set(deadline);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                DEADLINE.remove();
            } else {
                DEADLINE.set(previous);
            }
        }
    }

    /** Cuánto queda, o {@code null} si no hay presupuesto instalado. */
    public static Duration remaining(Clock clock) {
        Instant deadline = DEADLINE.get();
        return deadline == null ? null : Duration.between(clock.instant(), deadline);
    }

    /**
     * ¿Alcanza el presupuesto restante para gastar {@code cost}?
     *
     * <p>Sin presupuesto instalado, siempre sí: el corte es una política del
     * pedido, no del cliente.
     */
    public static boolean allows(Duration cost, Clock clock) {
        Duration remaining = remaining(clock);
        return remaining == null || remaining.compareTo(cost) >= 0;
    }

    public static boolean exhausted(Clock clock) {
        Duration remaining = remaining(clock);
        return remaining != null && (remaining.isNegative() || remaining.isZero());
    }
}
