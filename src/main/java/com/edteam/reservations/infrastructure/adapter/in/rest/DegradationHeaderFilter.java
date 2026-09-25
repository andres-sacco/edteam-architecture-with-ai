package com.edteam.reservations.infrastructure.adapter.in.rest;

import com.edteam.reservations.infrastructure.cache.CircuitBreakingCacheStore;
import com.edteam.reservations.infrastructure.observability.BusinessMetrics;
import com.edteam.reservations.infrastructure.resilience.Degradation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Emite {@code X-Degraded} cuando la respuesta se armó con algún dato que no
 * vino del origen.
 *
 * <p>Es la pieza que convierte la degradación en algo auditable desde afuera.
 * Antes el único rastro de que una reserva se había creado con el catálogo
 * caído era un {@code log.warn} del lado del servidor: un frontend no podía
 * avisar «datos de catálogo desactualizados» y un test de integración no podía
 * afirmar que el <em>stale</em> se había usado. Una respuesta degradada
 * indistinguible de una sana es la razón por la que las caídas de proveedores
 * se descubren por un reclamo.
 *
 * <p>El header se escribe antes de que la respuesta se confirme, y la marca se
 * limpia siempre: con un pool de hilos, una marca que sobrevive al pedido le
 * miente al siguiente.
 *
 * <p>Va fuera de la cadena de seguridad, como el del correlation id: un
 * {@code 503} por catálogo caído también tiene que llevar la marca.
 *
 * <h2>Y cuenta una vez por respuesta</h2>
 * En el {@code finally} incrementa {@code reservations.requests.degraded}, que
 * <b>no</b> es {@code reservations.degraded.responses}. Aquél cuenta eventos:
 * un {@code POST} con tres ciudades servidas de la ventana de gracia lo
 * incrementa tres veces, y con eso no se puede decir qué porcentaje de las
 * respuestas salió degradado. Este cuenta respuestas, y este filtro es el
 * único lugar del sistema donde la lista deduplicada de dependencias y el
 * final del pedido existen a la vez.
 */
public class DegradationHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Degraded";

    /** Misma ruta que escribe el log de acceso: el panel y el log se cruzan sin traducir. */
    private static final String UNMATCHED = "unmatched";

    private final BusinessMetrics metrics;

    public DegradationHeaderFilter(BusinessMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "Las métricas de negocio son obligatorias");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Degradation.clear();
        // El header se escribe en el instante en que la degradación ocurre y
        // no al final del pedido: para cuando este filtro recupera el
        // control, el cuerpo ya puede estar escrito y la respuesta
        // confirmada, y un header puesto ahí no sale.
        Degradation.bind(sources -> {
            if (!response.isCommitted()) {
                response.setHeader(HEADER, String.join(",", sources));
            }
        });
        try {
            chain.doFilter(request, response);
        } finally {
            countDegradedResponse(request);
            Degradation.clear();
            // El aviso de cache degradado es uno por pedido: acá se rearma
            // para el próximo, igual que se limpia la marca de degradación.
            CircuitBreakingCacheStore.resetWarningScope();
        }
    }

    private void countDegradedResponse(HttpServletRequest request) {
        Set<String> sources = Degradation.sources();
        if (sources.isEmpty()) {
            return;
        }
        // La plantilla del handler y no la URI: en una etiqueta, la URI
        // concreta es una serie por reserva.
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = pattern instanceof String template && !template.isBlank() ? template : UNMATCHED;
        for (String dependency : sources) {
            metrics.recordDegradedResponse(dependency, route);
        }
    }
}
