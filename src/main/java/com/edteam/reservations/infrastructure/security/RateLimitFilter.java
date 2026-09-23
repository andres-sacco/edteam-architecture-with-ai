package com.edteam.reservations.infrastructure.security;

import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ApiErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cuota de pedidos por identidad, con ventana fija.
 *
 * <h2>Qué acota</h2>
 * El pool de conexiones tiene 20 conexiones y un {@code connection-timeout} de
 * 3 segundos: unos cientos de listados concurrentes con {@code OFFSET} alto lo
 * agotan y devuelven 500 a todo el mundo. El alta es peor, porque cada una
 * puede disparar hasta 20 consultas en serie contra el catálogo externo, con
 * peor caso de ~6,5 s cada una. La cuota convierte «un cliente puede tumbar el
 * servicio» en «un cliente se queda sin cuota».
 *
 * <h2>Por qué la escritura tiene una cuota aparte y más chica</h2>
 * Una lectura es una consulta; un alta es una transacción, N llamadas salientes
 * y una fila nueva en el maestro de usuarios. Con una cuota única habría que
 * elegir entre un número tan alto que no protege de las altas o tan bajo que
 * molesta a las lecturas legítimas de una pantalla que refresca.
 *
 * <h2>Lo que este filtro no es</h2>
 * No es el rate limiting del sistema: ese va en el gateway, donde el pedido se
 * rechaza antes de gastar un hilo y donde la cuenta es una sola para todas las
 * instancias. Acá cada instancia lleva la suya, así que con N instancias la
 * cuota efectiva es N veces la configurada. Existe igual porque es la última
 * línea: el día que el gateway se caiga, se desconfigure o alguien alcance el
 * puerto desde adentro de la red, esto sigue estando.
 *
 * <h2>Ventana fija y no token bucket</h2>
 * Una ventana fija admite el doble de la cuota en el borde entre dos ventanas.
 * Se acepta: el objetivo es cortar el abuso sostenido —un scraper recorriendo
 * ids durante minutos—, no suavizar ráfagas, y el costo de una estructura por
 * clave que además hay que purgar no se justifica para la última línea de
 * defensa.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Cota del mapa de contadores: es memoria del proceso, y la llena quien ataca. */
    private static final int MAX_TRACKED_CLIENTS = 50_000;

    private static final String ANONYMOUS = "ip:";

    private final SecurityProperties.RateLimit properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(SecurityProperties.RateLimit properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isEnabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean write = isWrite(request.getMethod());
        int quota = write ? properties.writes() : properties.reads();
        String key = (write ? "w|" : "r|") + clientKey(request);

        long remaining = consume(key, quota);
        response.setHeader("X-RateLimit-Limit", String.valueOf(quota));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(remaining, 0)));

        if (remaining < 0) {
            reject(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isWrite(String method) {
        return !HttpMethod.GET.matches(method) && !HttpMethod.HEAD.matches(method)
                && !HttpMethod.OPTIONS.matches(method);
    }

    /**
     * La clave es la identidad si el pedido está autenticado, y la IP si no.
     *
     * <p>Identidad antes que IP porque es lo que realmente acota el abuso: una
     * flota de IPs es barata, una flota de identidades emitidas por el IdP no.
     * La IP queda como red para todo lo que llega sin token —que es lo que
     * empieza a golpear cuando la autenticación ya está puesta—.
     */
    private static String clientKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof ActorAuthenticationToken actor) {
            return "sub:" + actor.getName();
        }
        return ANONYMOUS + request.getRemoteAddr();
    }

    /** @return cuota restante; negativo si se pasó */
    private long consume(String key, int quota) {
        long now = clock.millis();
        long windowMillis = properties.window().toMillis();
        long currentWindow = now / windowMillis;

        if (windows.size() > MAX_TRACKED_CLIENTS) {
            // El mapa lo llena quien ataca desde muchas IPs: si crece de más se
            // descarta entero. Se pierde la cuenta en curso de los legítimos —a
            // lo sumo les regala una ventana— y no se pierde el proceso.
            log.warn("Se superaron {} clientes con cuota en seguimiento: se reinicia el registro",
                    MAX_TRACKED_CLIENTS);
            windows.clear();
        }

        Window window = windows.compute(key, (ignored, current) ->
                current == null || current.window != currentWindow
                        ? new Window(currentWindow)
                        : current);
        return quota - window.count.incrementAndGet();
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long retryAfter = Math.max(1, Duration.ofMillis(
                properties.window().toMillis() - clock.millis() % properties.window().toMillis()).toSeconds());

        // Sin el path completo ni la identidad: es un log de alto volumen
        // durante un abuso, y es el propio abuso el que elige qué escribe.
        log.warn("Cuota superada en {} (método {})", request.getRequestURI(), request.getMethod());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Se superó la cuota de pedidos. Reintentá en %d segundo(s).".formatted(retryAfter));
        problem.setType(ApiErrorCode.RATE_LIMIT_EXCEEDED.type());
        problem.setTitle(ApiErrorCode.RATE_LIMIT_EXCEEDED.title());
        problem.setProperty("code", ApiErrorCode.RATE_LIMIT_EXCEEDED.name());
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    /** Contador de una ventana. Se reemplaza entero cuando la ventana cambia. */
    private static final class Window {

        private final long window;
        private final AtomicLong count = new AtomicLong();

        private Window(long window) {
            this.window = window;
        }
    }
}
