package com.edteam.reservations.infrastructure.logging;

import io.micrometer.context.ThreadLocalAccessor;
import java.util.Map;
import org.slf4j.MDC;

/**
 * Enseña a {@code context-propagation} a llevarse el MDC.
 *
 * <h2>Por qué hace falta escribirlo</h2>
 * {@code io.micrometer:context-propagation} no sabe nada del MDC por sí solo:
 * propaga los {@code ThreadLocal} que alguien haya registrado, y de fábrica el
 * único registrado —cuando hay trazas— es el del scope de observación. El
 * {@code correlationId}, que es el campo sobre el que se investiga todo, es un
 * {@code ThreadLocal} de Logback y se quedaba en el hilo del pedido.
 *
 * <p>Sin este accessor, el {@code ContextSnapshot.wrap()} de
 * {@code BudgetedCityCatalogFanout} propagaría el contexto de traza y no el
 * {@code correlationId}: la mitad del arreglo, y justo la mitad que existe
 * también cuando las trazas están apagadas o el pedido no fue muestreado.
 *
 * <h2>El contrato</h2>
 * {@code setValue} <b>reemplaza</b> el MDC del hilo destino y {@code restore}
 * lo devuelve a lo que había. Es importante que sea reemplazo y no merge: un
 * hilo virtual del fan-out es nuevo y no tiene nada, pero el ejecutor los
 * reutiliza en otras llamadas, y un merge dejaría el {@code correlationId} de
 * un pedido pegado al siguiente que tome ese hilo — que es exactamente el bug
 * que {@code CorrelationIdFilter} evita con su {@code finally}.
 */
public class MdcThreadLocalAccessor implements ThreadLocalAccessor<Map<String, String>> {

    /** Clave del accessor dentro del contexto propagado. */
    public static final String KEY = "mdc";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public Map<String, String> getValue() {
        return MDC.getCopyOfContextMap();
    }

    @Override
    public void setValue(Map<String, String> value) {
        if (value == null || value.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(value);
        }
    }

    /**
     * Se llamó a {@code setValue} con {@code null}: el hilo origen no tenía
     * MDC, así que el destino tampoco tiene que tenerlo.
     */
    @Override
    public void setValue() {
        MDC.clear();
    }

    @Override
    public void restore(Map<String, String> previous) {
        setValue(previous);
    }

    @Override
    public void restore() {
        MDC.clear();
    }
}
