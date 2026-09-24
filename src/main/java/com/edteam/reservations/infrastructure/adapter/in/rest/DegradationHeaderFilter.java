package com.edteam.reservations.infrastructure.adapter.in.rest;

import com.edteam.reservations.infrastructure.resilience.Degradation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

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
 */
public class DegradationHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Degraded";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
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
            Degradation.clear();
        }
    }
}
