package com.edteam.reservations.infrastructure.logging;

import com.edteam.reservations.infrastructure.observability.BusinessMetrics;
import com.edteam.reservations.infrastructure.resilience.Degradation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Una línea por pedido: {@code event=http.request}.
 *
 * <h2>El agujero que tapa</h2>
 * Antes de este filtro, un arranque completo más 15 pruebas de la API
 * producían <b>cero</b> líneas que describieran un pedido HTTP. Un 401, un
 * 403, un 404, un 409, un 429 y un 304 no dejaban absolutamente ninguna
 * huella: no había forma de saber que el pedido había existido. Un {@code POST}
 * exitoso dejaba una sola línea —«Reserva creada»— que no decía ni la ruta, ni
 * el status, ni cuánto tardó, ni quién lo hizo.
 *
 * <p>Es también la precondición de tres registros que la auditoría pedía por
 * separado: el fallo de autenticación, el rechazo por cuota y el conflicto de
 * versión son un campo más en una línea que recién ahora existe.
 *
 * <h2>Dónde va en la cadena, y por qué importa</h2>
 * Después de {@code CorrelationIdFilter} —para que la línea lleve el id— y
 * <b>envolviendo</b> a {@code DegradationHeaderFilter}: cuando este filtro
 * recupera el control, {@code Degradation.sources()} ya está completo y el
 * campo {@code degraded} dice de verdad si la respuesta salió con datos
 * viejos. Al revés, el campo saldría vacío siempre.
 *
 * <p>Se registra con {@code DispatcherType.ERROR} además de {@code REQUEST}:
 * sin eso, un 400 de parseo del contenedor o un 404 sin handler pasan por
 * {@code /error} y no dejan línea (hallazgo 13 de la auditoría).
 *
 * <h2>La ruta es la plantilla, no la URI</h2>
 * {@code /v1/reservations/{reservationId}} y no {@code /v1/reservations/10241}.
 * En el log la URI concreta sería tolerable; la plantilla es obligatoria en la
 * etiqueta de la métrica, y tener el mismo valor en los dos lados es lo que
 * permite ir del panel al log sin traducir.
 *
 * <h2>Nivel</h2>
 * {@code INFO} para todo lo que el sistema respondió como corresponde,
 * incluidos los 4xx —un 404 y un 409 son la API haciendo su trabajo— y
 * {@code ERROR} para los 5xx, que son un efecto que el usuario perdió. El
 * criterio es el del §2 del diseño: ¿quién tiene que hacer algo?
 */
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    /**
     * Atributo donde el {@code @RestControllerAdvice} deja el código de error
     * de la respuesta.
     *
     * <p>Es lo que separa un 409 por {@code If-Match} desactualizado —el
     * sistema funcionando— de un 409 por clave de idempotencia reusada, que es
     * un contrato roto del integrador. {@code http.server.requests} no
     * distingue los dos y por eso la justificación del §2.2 del diseño
     * apuntaba a una métrica que no alcanzaba.
     */
    public static final String ERROR_CODE_ATTRIBUTE = RequestLogFilter.class.getName() + ".errorCode";

    /** Ruta que se escribe cuando ningún handler reclamó el pedido. */
    private static final String UNMATCHED = "unmatched";

    private final BusinessMetrics metrics;

    public RequestLogFilter(BusinessMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "Las métricas de negocio son obligatorias");
    }

    /**
     * Actuator queda afuera.
     *
     * <p>Son las sondas del orquestador cada 10 s por instancia y el raspado
     * del recolector cada 15 s: ~17.000 líneas por día que dicen que el proceso
     * sigue vivo. Además entran por el puerto 9090, que es otro contexto.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            record(request, response, (System.nanoTime() - startedAt) / 1_000_000L);
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response, long durationMs) {
        int status = response.getStatus();
        String route = routeOf(request);
        String method = request.getMethod();
        String errorCode = errorCodeOf(request);
        Set<String> degraded = Degradation.sources();

        // El `actorRef` ya está en el MDC: lo puso `JwtActorConverter` cuando
        // la cadena de seguridad resolvió la identidad. Este filtro NO lo pone,
        // y no por elegancia: corre por fuera de la cadena de seguridad, así
        // que para cuando recupera el control el SecurityContext ya está
        // limpio y `SecurityContextHolder` devuelve el anónimo.
        LoggingEventBuilder event = status >= 500 ? log.atError() : log.atInfo();
        event.addKeyValue(LogFields.EVENT, LogFields.HTTP_REQUEST)
                .addKeyValue(LogFields.HTTP_METHOD, method)
                .addKeyValue(LogFields.HTTP_ROUTE, route)
                .addKeyValue(LogFields.HTTP_STATUS, status)
                .addKeyValue(LogFields.DURATION_MS, durationMs);
        // La IP como campo explícito y sólo acá. Está en el MDC —el adaptador
        // de auditoría la lee de ahí— pero el encoder la excluye del `<mdc/>`:
        // es dato personal, y en cada línea es el mismo dato multiplicado por
        // el volumen del log. Acá sí, porque es el único identificador con el
        // que se investiga abuso y se defiende un rechazo.
        String clientIp = MDC.get(LogFields.CLIENT_IP);
        if (clientIp != null) {
            event.addKeyValue(LogFields.CLIENT_IP, clientIp);
        }
        if (errorCode != null) {
            event.addKeyValue(LogFields.ERROR_CODE, errorCode);
        }
        if (!degraded.isEmpty()) {
            event.addKeyValue(LogFields.DEGRADED, degraded);
        }
        // `message` fijo: el dato va en los campos. Que la redacción pueda
        // cambiar sin romper una consulta es media razón de ser del esquema.
        event.log("Pedido atendido");

        metrics.recordRequest(method, route, status, errorCode, !degraded.isEmpty());
    }

    /**
     * La plantilla del {@code HandlerMapping}, que son seis valores de negocio.
     *
     * <p>Cuando no hay handler —una ruta que no existe, un 401 cortado por
     * seguridad antes del dispatcher— el atributo no está. Se escribe un
     * centinela y <b>no</b> la URI: la URI de un pedido que nadie reclamó la
     * elige quien ataca, y de ahí sale directo a una etiqueta de métrica.
     */
    private static String routeOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String template && !template.isBlank() ? template : UNMATCHED;
    }

    private static String errorCodeOf(HttpServletRequest request) {
        Object code = request.getAttribute(ERROR_CODE_ATTRIBUTE);
        return code instanceof String text && !text.isBlank() ? text : null;
    }
}
