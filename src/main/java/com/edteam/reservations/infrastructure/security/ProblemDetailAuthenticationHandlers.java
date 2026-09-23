package com.edteam.reservations.infrastructure.security;

import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ApiErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 */
public final class ProblemDetailAuthenticationHandlers {

    private static final String BEARER_CHALLENGE = "Bearer realm=\"reservations\"";

    private ProblemDetailAuthenticationHandlers() {
    }

    /** 401: no hay credencial, o la que hay no vale. */
    public static AuthenticationEntryPoint entryPoint(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "El ObjectMapper es obligatorio");
        return (request, response, exception) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE);
            write(objectMapper, request, response, HttpStatus.UNAUTHORIZED, ApiErrorCode.UNAUTHENTICATED,
                    "El pedido requiere un token Bearer válido.");
        };
    }

    /** 403: la credencial vale, pero no alcanza para esta operación. */
    public static AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "El ObjectMapper es obligatorio");
        return (request, response, exception) ->
                write(objectMapper, request, response, HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN,
                        "El solicitante no tiene permiso para esta operación.");
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

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // Un cuerpo de error de un endpoint autenticado no se guarda en ningún lado.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
