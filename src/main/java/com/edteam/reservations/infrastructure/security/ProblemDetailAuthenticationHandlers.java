package com.edteam.reservations.infrastructure.security;

import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ApiErrorCode;
import com.edteam.reservations.infrastructure.logging.LogFields;
import com.edteam.reservations.infrastructure.logging.RequestLogFilter;
import com.edteam.reservations.infrastructure.observability.SecurityMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;

/**
 * Respuestas de 401 y 403 con el mismo cuerpo que el resto de la API.
 *
 * <p>Sin esto, Spring Security corta el pedido antes de llegar al
 * {@code @RestControllerAdvice} y devuelve su propia respuesta: un 401 con
 * cuerpo vacío o un 403 con el error HTML del contenedor. Para un cliente que
 * ya programó contra {@code application/problem+json} con un campo
 * {@code code} estable, eso son dos formatos de error nuevos que aparecen sólo
 * en los casos de falla —que son justo los que menos se prueban del otro lado—.
 *
 * <h2>Qué dicen y qué no</h2>
 * El detalle es genérico a propósito. No se informa si el token expiró, si la
 * firma no valida, si el emisor no es el esperado o si falta un claim: cada una
 * de esas distinciones le dice a quien está probando tokens exactamente qué
 * tiene que corregir. El motivo real queda del lado del servidor, en el log.
 *
 * <h2>Y ahora esa promesa se cumple</h2>
 * El javadoc de arriba decía «en el log» y no se escribía ninguna línea: el
 * único rastro de un token rechazado era un {@code DEBUG} de
 * {@code JwtActorConverter}, apagado en producción. Una campaña de credenciales
 * robadas contra la API era, literalmente, invisible. Ahora cada rechazo deja
 * un {@code WARN} con {@code event=auth.failed} y un contador con el motivo.
 *
 * <p>Lo que <b>no</b> entra al log es el token, ni el {@code kid}, ni sus
 * primeros caracteres: un token en un log es una credencial válida replicada a
 * un sistema indexado. El {@code reason} es un enum de tres valores y el
 * {@code clientIp} ya está en el MDC desde {@code CorrelationIdFilter}, que es
 * con lo que se bloquea en el borde. Eso alcanza para la alerta y para la
 * acción que la alerta pide.
 */
public final class ProblemDetailAuthenticationHandlers {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailAuthenticationHandlers.class);

    private static final String BEARER_CHALLENGE = "Bearer realm=\"reservations\"";

    private ProblemDetailAuthenticationHandlers() {
    }

    /** 401: no hay credencial, o la que hay no vale. */
    public static AuthenticationEntryPoint entryPoint(ObjectMapper objectMapper, SecurityMetrics metrics) {
        Objects.requireNonNull(objectMapper, "El ObjectMapper es obligatorio");
        Objects.requireNonNull(metrics, "Las métricas de seguridad son obligatorias");
        return (request, response, exception) -> {
            // Dos motivos y no uno: «nadie mandó token» es un cliente mal
            // configurado o un escaneo, y «el token no vale» es alguien que
            // tiene algo y lo está probando. Sólo el segundo justifica la
            // alerta de presión de credenciales.
            String reason = request.getHeader(HttpHeaders.AUTHORIZATION) == null ? "no_token" : "invalid_token";
            metrics.authFailure(reason);
            log.atWarn()
                    .addKeyValue(LogFields.EVENT, LogFields.AUTH_FAILED)
                    .addKeyValue(LogFields.REASON, reason)
                    .addKeyValue(LogFields.HTTP_METHOD, request.getMethod())
                    .addKeyValue(LogFields.HTTP_STATUS, HttpStatus.UNAUTHORIZED.value())
                    .log("Pedido rechazado por credencial ausente o inválida");

            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE);
            write(objectMapper, request, response, HttpStatus.UNAUTHORIZED, ApiErrorCode.UNAUTHENTICATED,
                    "El pedido requiere un token Bearer válido.");
        };
    }

    /** 403: la credencial vale, pero no alcanza para esta operación. */
    public static AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper, SecurityMetrics metrics) {
        Objects.requireNonNull(objectMapper, "El ObjectMapper es obligatorio");
        Objects.requireNonNull(metrics, "Las métricas de seguridad son obligatorias");
        return (request, response, exception) -> {
            metrics.authFailure("forbidden");
            // WARN y no INFO: «intento de acceso a un recurso ajeno» es una
            // señal de seguridad y tiene que estar en el panel de WARN por
            // minuto, no mezclada con los hechos de negocio. El §2.2 del
            // diseño la dejaba en INFO y el §3.1 la pedía en WARN; se resuelve
            // en favor del §3.1, que es el que aplica el criterio de «¿quién
            // tiene que hacer algo?».
            log.atWarn()
                    .addKeyValue(LogFields.EVENT, LogFields.AUTH_DENIED)
                    .addKeyValue(LogFields.REASON, "insufficient_scope")
                    .addKeyValue(LogFields.HTTP_METHOD, request.getMethod())
                    .addKeyValue(LogFields.HTTP_STATUS, HttpStatus.FORBIDDEN.value())
                    .log("Pedido rechazado por permisos");

            write(objectMapper, request, response, HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN,
                    "El solicitante no tiene permiso para esta operación.");
        };
    }

    private static void write(ObjectMapper objectMapper,
                              HttpServletRequest request,
                              HttpServletResponse response,
                              HttpStatus status,
                              ApiErrorCode code,
                              String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        problem.setInstance(URI.create(request.getRequestURI()));

        // El log de acceso corre por fuera de la cadena de seguridad y no ve
        // esta excepción: sin este atributo, su línea saldría con el status
        // puesto y sin el código de error, y la métrica de negocio no podría
        // distinguir un 'denied' de cualquier otro 4xx.
        request.setAttribute(RequestLogFilter.ERROR_CODE_ATTRIBUTE, code.name());

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // Un cuerpo de error de un endpoint autenticado no se guarda en ningún lado.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
