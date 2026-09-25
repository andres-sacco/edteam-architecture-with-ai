package com.edteam.reservations.infrastructure.security;

import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ApiErrorCode;
import com.edteam.reservations.infrastructure.logging.LogFields;
import com.edteam.reservations.infrastructure.logging.RequestLogFilter;
import com.edteam.reservations.infrastructure.observability.SecurityMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
    private final SecurityMetrics metrics;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(
            SecurityProperties.RateLimit properties, ObjectMapper objectMapper, Clock clock, SecurityMetrics metrics) {
        this.properties = Objects.requireNonNull(properties);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics, "Las métricas de seguridad son obligatorias");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isEnabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
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
        return !HttpMethod.GET.matches(method)
                && !HttpMethod.HEAD.matches(method)
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
            // Con contador propio: cada vaciado le regala una ventana a todos
            // los clientes legítimos, y sin métrica la única señal era este
            // WARN suelto en medio de un ataque, que es cuando nadie lo lee.
            metrics.quotaRegistryReset();
            log.atWarn()
                    .addKeyValue(LogFields.EVENT, "rate.registry_reset")
                    .addKeyValue("tracked", MAX_TRACKED_CLIENTS)
                    .log("Se superó el tope de clientes con cuota en seguimiento: se reinicia el registro");
            windows.clear();
        }

        Window window = windows.compute(
                key,
                (ignored, current) ->
                        current == null || current.window != currentWindow ? new Window(currentWindow) : current);
        return quota - window.count.incrementAndGet();
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long retryAfter = Math.max(
                1,
                Duration.ofMillis(properties.window().toMillis()
                                - clock.millis() % properties.window().toMillis())
                        .toSeconds());

        // Sigue sin la identidad, y ahora tampoco con la URI cruda: es un log
        // de alto volumen durante un abuso y es el propio abuso el que elige
        // qué escribe. Lo que cambia es que el pivote hacia el cliente ahora
        // existe: el clientIp está en el MDC desde CorrelationIdFilter y el
        // correlationId une esta línea con la de acceso del mismo pedido.
        String route = routeOf(request);
        metrics.rateLimited(request.getMethod(), route);
        request.setAttribute(RequestLogFilter.ERROR_CODE_ATTRIBUTE, ApiErrorCode.RATE_LIMIT_EXCEEDED.name());
        log.atWarn()
                .addKeyValue(LogFields.EVENT, LogFields.RATE_LIMITED)
                .addKeyValue(LogFields.HTTP_METHOD, request.getMethod())
                .addKeyValue(LogFields.HTTP_ROUTE, route)
                .addKeyValue(LogFields.HTTP_STATUS, HttpStatus.TOO_MANY_REQUESTS.value())
                .addKeyValue("retryAfterSeconds", retryAfter)
                .log("Cuota de pedidos superada");

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
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

    /**
     * La plantilla del handler, o un centinela.
     *
     * <p>Este filtro corre <b>antes</b> del {@code DispatcherServlet}, así que
     * el atributo de la plantilla todavía no está. Se derivan a mano los dos
     * prefijos de negocio y todo lo demás cae en {@code other}: la URI cruda
     * no puede entrar a una etiqueta —la elige quien ataca, que es justo el
     * caso en el que este contador se incrementa— y una bomba de cardinalidad
     * disparable desde afuera es peor que no tener la métrica.
     */
    private static String routeOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/v1/reservations")) {
            return "other";
        }
        return "/v1/reservations".equals(uri) || "/v1/reservations/".equals(uri)
                ? "/v1/reservations"
                : "/v1/reservations/{reservationId}";
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
