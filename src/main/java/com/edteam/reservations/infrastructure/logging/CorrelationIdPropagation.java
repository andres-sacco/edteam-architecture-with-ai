package com.edteam.reservations.infrastructure.logging;

import org.slf4j.MDC;
import org.springframework.http.client.ClientHttpRequestInterceptor;

/**
 * El correlation id sale del proceso.
 *
 * <h2>El hallazgo 14</h2>
 * El {@code RestClient} del catálogo llevaba {@code baseUrl}, timeouts,
 * {@code Accept} y la API key, y ningún interceptor de propagación: la traza se
 * cortaba en el borde. Hoy el {@code api-catalog} es un contenedor de terceros
 * que ignora el header, así que el costo es futuro y no actual — y esa es
 * exactamente la razón por la que conviene ponerlo ahora: el día que el
 * catálogo sea un servicio nuestro, la traza se continúa sola sin tocar una
 * línea de este código.
 *
 * <h2>El otro header lo pone la instrumentación</h2>
 * El {@code traceparent} de W3C lo inyecta Micrometer Tracing sobre el mismo
 * {@code RestClient.Builder}, que es la razón por la que el cliente del
 * catálogo se arma clonando el builder autoconfigurado y no desde cero. Los
 * dos identificadores conviven y ninguno reemplaza al otro: el
 * {@code correlationId} está en el 100 % de los pedidos y el {@code traceId}
 * sólo en los muestreados.
 *
 * <h2>De dónde sale el valor</h2>
 * Del MDC, que en el fan-out está poblado gracias al {@code ContextSnapshot} de
 * {@code BudgetedCityCatalogFanout}. Sin ese arreglo, este interceptor no
 * encontraría nada justo en las llamadas que importan: las ocho que un
 * {@code POST} con escala dispara en paralelo sobre hilos virtuales.
 */
public final class CorrelationIdPropagation {

    /** Mismo nombre que el header de entrada: el id es uno solo en todo el camino. */
    public static final String HEADER = "X-Correlation-Id";

    private CorrelationIdPropagation() {}

    public static ClientHttpRequestInterceptor interceptor() {
        return (request, body, execution) -> {
            String correlationId = MDC.get(LogFields.CORRELATION_ID);
            if (correlationId != null && !correlationId.isBlank()) {
                request.getHeaders().set(HEADER, correlationId);
            }
            // Sin id no se inventa uno: un header con un valor fabricado acá
            // apuntaría a un pedido que no existe, y una traza que miente es
            // peor que una que se corta.
            return execution.execute(request, body);
        };
    }
}
