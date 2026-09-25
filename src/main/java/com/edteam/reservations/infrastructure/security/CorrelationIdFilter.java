package com.edteam.reservations.infrastructure.security;

import com.edteam.reservations.infrastructure.logging.LogSanitizer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Da a cada pedido un identificador que aparece en todos sus logs y en su
 * respuesta.
 *
 * <p>Es la mitad barata de la trazabilidad, y la que hace usable a la otra: la
 * auditoría dice quién canceló una reserva, y el correlation id permite ir del
 * reclamo del pasajero —que tiene el header de la respuesta— a todas las
 * líneas de log de ese pedido exacto, en todas las instancias.
 *
 * <h2>El id del cliente se acepta, pero no se cree</h2>
 * Si el pedido trae uno se reusa, para no cortar la traza que empezó en el
 * frontend o en el gateway. Pero se valida contra un formato estricto antes de
 * tocar nada: el valor termina en el MDC y de ahí en cada línea de log, así que
 * un cliente que mande saltos de línea estaría escribiendo en nuestros logs. Un
 * valor que no cumple el formato se descarta y se genera uno nuevo, sin error:
 * no es culpa del pedido y no hay nada que el cliente pueda arreglar.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    /** Clave del MDC. La lee el adaptador de auditoría y el patrón de log. */
    public static final String MDC_KEY = "correlationId";

    /** Clave del MDC con la IP del cliente, para la auditoría. */
    public static final String MDC_CLIENT_IP = "clientIp";

    /**
     * Clave del MDC con el seudónimo del solicitante. La <b>escribe</b>
     * {@code JwtActorConverter}, cuando la cadena de seguridad resuelve la
     * identidad; se limpia acá porque éste es el filtro más externo y el único
     * que corre siempre, incluido el camino del 401.
     */
    public static final String MDC_ACTOR_REF = "actorRef";

    /** Alfanumérico, guion y guion bajo. Cubre UUID, ULID y los ids de los gateways. */
    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = acceptedOrNew(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId);
        MDC.put(MDC_CLIENT_IP, LogSanitizer.sanitize(request.getRemoteAddr(), 45));
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Sin esto, con un pool de threads el id se filtra al próximo
            // pedido que tome ese thread y la traza queda mintiendo.
            MDC.remove(MDC_KEY);
            MDC.remove(MDC_CLIENT_IP);
            MDC.remove(MDC_ACTOR_REF);
        }
    }

    private static String acceptedOrNew(String claimed) {
        return claimed != null && ACCEPTED.matcher(claimed).matches()
                ? claimed
                : UUID.randomUUID().toString();
    }
}
