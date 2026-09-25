package com.edteam.reservations.infrastructure.resilience;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Marca, durante un pedido, qué dependencias respondieron degradadas.
 *
 * <p>Es lo que convierte «servimos un dato viejo» en algo auditable desde
 * afuera: el filtro del borde lee estas marcas y emite el header
 * {@code X-Degraded}. Sin eso, un fallback que sirve datos viejos sin decirlo
 * es indistinguible de un sistema sano, y esa es exactamente la razón por la
 * que nadie se entera de la caída de un proveedor hasta que alguien reclama.
 *
 * <p>El almacenamiento es un {@link ThreadLocal} y no un {@code ScopedValue}
 * ni un bean de scope request por dos razones prácticas: el filtro que lo lee
 * y el decorador que lo escribe corren los dos en el hilo del pedido —el
 * fan-out reparte el trabajo en hilos virtuales, pero <em>recolecta</em> en el
 * hilo llamador, que es donde se marca—, y así el dominio y la aplicación
 * siguen sin enterarse de que esto existe.
 *
 * <p>El filtro limpia siempre en un {@code finally}: con un pool de hilos, una
 * marca que sobrevive al pedido le miente al siguiente.
 */
public final class Degradation {

    private static final ThreadLocal<Set<String>> SOURCES = new ThreadLocal<>();
    private static final ThreadLocal<Consumer<Set<String>>> SINK = new ThreadLocal<>();

    private Degradation() {}

    /**
     * Conecta un consumidor que se entera <strong>en el momento</strong> de
     * cada marca.
     *
     * <p>No alcanza con juntar las marcas y leerlas al final del pedido: para
     * cuando el filtro recupera el control, la respuesta ya puede estar
     * confirmada y un header escrito ahí no llega a ningún lado. El header se
     * escribe cuando la degradación ocurre, que es antes de que el controlador
     * empiece a serializar.
     */
    public static void bind(Consumer<Set<String>> sink) {
        SINK.set(sink);
    }

    /** Registra que {@code source} respondió degradado en este pedido. */
    public static void mark(String source) {
        if (source == null || source.isBlank()) {
            return;
        }
        Set<String> sources = SOURCES.get();
        if (sources == null) {
            sources = new LinkedHashSet<>(2);
            SOURCES.set(sources);
        }
        if (!sources.add(source)) {
            return;
        }
        Consumer<Set<String>> sink = SINK.get();
        if (sink != null) {
            sink.accept(Set.copyOf(sources));
        }
    }

    /** Las dependencias degradadas de este pedido, en orden de aparición. */
    public static Set<String> sources() {
        Set<String> sources = SOURCES.get();
        return sources == null ? Set.of() : Set.copyOf(sources);
    }

    public static void clear() {
        SOURCES.remove();
        SINK.remove();
    }
}
