package com.edteam.reservations.infrastructure.adapter.in.rest;

import com.edteam.reservations.application.exception.AirportCatalogIntegrationException;
import com.edteam.reservations.application.exception.AirportCatalogUnavailableException;
import com.edteam.reservations.application.exception.ConcurrentUpdateException;
import com.edteam.reservations.application.exception.DuplicateReservationException;
import com.edteam.reservations.application.exception.ReservationNotFoundException;
import com.edteam.reservations.application.exception.UnknownAirportException;
import com.edteam.reservations.application.exception.UnknownUserException;
import com.edteam.reservations.domain.access.ReservationAccessDeniedException;
import com.edteam.reservations.domain.exception.DomainException;
import com.edteam.reservations.domain.exception.InvalidPassengerException;
import com.edteam.reservations.domain.exception.InvalidReservationException;
import com.edteam.reservations.domain.exception.InvalidUserException;
import com.edteam.reservations.domain.exception.ItineraryAlreadyDepartedException;
import com.edteam.reservations.domain.exception.ReservationAlreadyCancelledException;
import com.edteam.reservations.domain.exception.ReservationNotModifiableException;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ApiErrorCode;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.FieldErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Traduce las excepciones a respuestas HTTP con un cuerpo uniforme.
 *
 * <p>Es la contracara del adaptador de persistencia: allá se traducen los
 * errores de Hibernate a excepciones de la aplicación, y acá las de la
 * aplicación y el dominio a códigos de estado. Gracias a eso los casos de uso
 * no conocen ni un solo código HTTP.
 *
 * <h2>Un solo formato de error</h2>
 * Todas las respuestas de error —las de negocio y las que genera el propio
 * Spring, como un método no soportado o un JSON malformado— salen como
 * {@link ProblemDetail} (RFC 7807, {@code application/problem+json}). Por eso
 * la clase extiende {@link ResponseEntityExceptionHandler}: sin eso, los
 * errores del framework saldrían con el formato por defecto y el cliente
 * tendría que saber parsear dos cosas distintas.
 *
 * <p>A cada respuesta se le agrega un {@code code} estable. Es lo que permite
 * que el cliente decida sin leer el {@code detail}, que es texto para humanos.
 *
 * <h2>Qué no se filtra</h2>
 * Nunca sale un stack trace ni el mensaje de una excepción inesperada: lo que
 * se expone es el mensaje de negocio, que habla de los datos del pedido. Un
 * error no previsto se loguea completo del lado del servidor y del lado del
 * cliente es un 500 genérico.
 *
 * <h2>Correspondencia</h2>
 * <table>
 *   <caption>Excepción a código de estado</caption>
 *   <tr><th>Excepción</th><th>Estado</th></tr>
 *   <tr><td>{@code DomainException} (incluye itinerario ya salido)</td><td>400</td></tr>
 *   <tr><td>{@code UnknownAirportException}, {@code UnknownUserException}</td><td>400</td></tr>
 *   <tr><td>Validación de los DTOs</td><td>400</td></tr>
 *   <tr><td>{@code ReservationNotFoundException}</td><td>404</td></tr>
 *   <tr><td>{@code ConcurrentUpdateException}</td><td>409</td></tr>
 *   <tr><td>{@code ReservationAlreadyCancelledException}</td><td>409</td></tr>
 *   <tr><td>{@code ReservationNotModifiableException}</td><td>409</td></tr>
 *   <tr><td>{@code DuplicateReservationException}</td><td>409</td></tr>
 * </table>
 */
@RestControllerAdvice
public class ReservationExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ReservationExceptionHandler.class);

    /** Ventana sugerida al cliente cuando el maestro de aeropuertos no responde. */
    private static final int CATALOG_RETRY_AFTER_SECONDS = 5;

    // ------------------------------------------------------------------
    // 404
    // ------------------------------------------------------------------

    @ExceptionHandler(ReservationNotFoundException.class)
    public ProblemDetail handleReservationNotFound(ReservationNotFoundException e, WebRequest request) {
        return problem(HttpStatus.NOT_FOUND, ApiErrorCode.RESERVATION_NOT_FOUND, e.getMessage(), request);
    }

    // ------------------------------------------------------------------
    // 409: el pedido es válido, pero choca con el estado actual del recurso
    // ------------------------------------------------------------------

    @ExceptionHandler(ConcurrentUpdateException.class)
    public ProblemDetail handleConcurrentUpdate(ConcurrentUpdateException e, WebRequest request) {
        return problem(HttpStatus.CONFLICT, ApiErrorCode.CONCURRENT_UPDATE,
                "%s Volvé a leer la reserva y reintentá con el ETag actualizado.".formatted(e.getMessage()),
                request);
    }

    @ExceptionHandler(ReservationAlreadyCancelledException.class)
    public ProblemDetail handleAlreadyCancelled(ReservationAlreadyCancelledException e, WebRequest request) {
        return problem(HttpStatus.CONFLICT, ApiErrorCode.RESERVATION_ALREADY_CANCELLED, e.getMessage(), request);
    }

    @ExceptionHandler(ReservationNotModifiableException.class)
    public ProblemDetail handleNotModifiable(ReservationNotModifiableException e, WebRequest request) {
        return problem(HttpStatus.CONFLICT, ApiErrorCode.RESERVATION_NOT_MODIFIABLE, e.getMessage(), request);
    }

    /**
     * La carrera por la clave de idempotencia no se pudo resolver.
     *
     * <p>El controller ya reintentó una vez: si la excepción llega hasta acá,
     * el reintento tampoco encontró la reserva ganadora y no tiene sentido
     * seguir insistiendo del lado del servidor.
     */
    @ExceptionHandler(DuplicateReservationException.class)
    public ProblemDetail handleDuplicateReservation(DuplicateReservationException e, WebRequest request) {
        log.warn("No se pudo resolver la carrera por la clave de idempotencia: {}", e.getMessage());
        return problem(HttpStatus.CONFLICT, ApiErrorCode.IDEMPOTENCY_KEY_REUSED, e.getMessage(), request);
    }

    // ------------------------------------------------------------------
    // 403: el pedido es válido y el solicitante es quien dice ser, pero no le
    // corresponde. Es el caso raro: un recurso ajeno responde 404, para que el
    // código de estado no sea un oráculo. Acá el 403 es correcto porque el
    // rechazo no revela nada —el cliente sabe cuál es su propio email—, y una
    // lista vacía en silencio escondería un bug del cliente.
    // ------------------------------------------------------------------

    @ExceptionHandler(ReservationAccessDeniedException.class)
    public ProblemDetail handleAccessDenied(ReservationAccessDeniedException e, WebRequest request) {
        // El mensaje del dominio nombra al solicitante; el detalle que sale es
        // fijo. Un cuerpo de error termina en consolas, capturas de pantalla y
        // tickets de soporte, y no es el lugar donde reflejar un email.
        log.info("Pedido rechazado por alcance en {}: {}", pathOf(request), e.getMessage());
        return problem(HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN,
                "El solicitante no puede consultar reservas de otro usuario.", request);
    }

    // ------------------------------------------------------------------
    // 400: el pedido está mal
    // ------------------------------------------------------------------

    @ExceptionHandler(UnknownAirportException.class)
    public ProblemDetail handleUnknownAirport(UnknownAirportException e, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.UNKNOWN_AIRPORT, e.getMessage(), request);
    }

    /**
     * El usuario del cuerpo no existe.
     *
     * <p>Es 400 y no 404: el 404 habla del recurso identificado por la URI, y
     * acá la URI ({@code /v1/reservations}) existe. Lo que está mal es un dato
     * del cuerpo, igual que un aeropuerto inexistente.
     */
    @ExceptionHandler(UnknownUserException.class)
    public ProblemDetail handleUnknownUser(UnknownUserException e, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.USER_NOT_FOUND, e.getMessage(), request);
    }

    /**
     * Reglas de negocio del dominio.
     *
     * <p>Las dos excepciones de dominio que hablan del estado de una reserva ya
     * existente ({@code ReservationAlreadyCancelled} y
     * {@code ReservationNotModifiable}) tienen su propio handler y salen como
     * 409. El resto describe datos inválidos en el pedido y es 400.
     */
    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleDomain(DomainException e, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, codeOf(e), e.getMessage(), request);
    }

    @ExceptionHandler(EntityVersion.InvalidIfMatchException.class)
    public ProblemDetail handleInvalidIfMatch(EntityVersion.InvalidIfMatchException e, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_ERROR, e.getMessage(), request);
    }

    /**
     * Validación de los DTOs de entrada: cuerpos JSON y parámetros de consulta.
     *
     * <p>Se sobrescribe el handler del framework en lugar de agregar uno nuevo
     * porque {@link ResponseEntityExceptionHandler} ya mapea esta excepción;
     * declararla dos veces sería ambiguo y la aplicación no arrancaría.
     *
     * <p>Al cuerpo estándar se le agrega {@code errors} con un elemento por
     * campo rechazado: sin eso, el cliente sabe que algo estuvo mal pero no
     * qué campo marcar.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_ERROR,
                "El pedido tiene %d campo(s) inválido(s).".formatted(e.getErrorCount()), request);
        body.setProperty("errors", toFieldErrors(e));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).headers(headers).body(body);
    }

    /**
     * Completa el cuerpo de los errores que resuelve el framework: método no
     * soportado, {@code Content-Type} inválido, JSON malformado, header
     * obligatorio ausente, ruta inexistente.
     *
     * <p>Sin esto saldrían como {@code ProblemDetail} pero sin {@code code} ni
     * {@code instance}, y el cliente tendría que tratarlos distinto que a los
     * errores de negocio.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e,
                                                             Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(e, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            enrich(problem, defaultCodeFor(statusCode), request);
        }
        return response;
    }

    /**
     * Red de contención: cualquier cosa no prevista.
     *
     * <p>Se loguea completa —con stack trace— del lado del servidor, y hacia
     * afuera sale un 500 sin detalle. Exponer el mensaje de una excepción
     * inesperada es la forma más común de filtrar nombres de clases, rutas de
     * archivos o fragmentos de SQL.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, WebRequest request) {
        log.error("Error no controlado procesando {}", pathOf(request), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                "Ocurrió un error inesperado procesando el pedido.", request);
    }

    // ------------------------------------------------------------------
    // 5xx: el problema es nuestro o de una dependencia
    // ------------------------------------------------------------------

    /**
     * El maestro de aeropuertos no está disponible (5xx, 429 o error de red).
     *
     * <p>Es la contracara de {@code UNKNOWN_AIRPORT}: ahí sabemos que el
     * aeropuerto no existe, acá no pudimos averiguarlo. Sale como 503 y no
     * como 400 porque el pedido es válido y volver a intentarlo tiene sentido;
     * el {@code Retry-After} le dice al cliente cuándo, para que no nos
     * martille mientras el proveedor se recupera.
     *
     * <p>Se loguea en WARN y sin stack trace: es una falla esperable de una
     * dependencia externa, no un defecto del código.
     */
    @ExceptionHandler(AirportCatalogUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleCatalogUnavailable(AirportCatalogUnavailableException e,
                                                                  WebRequest request) {
        log.warn("Maestro de aeropuertos no disponible procesando {}: {}", pathOf(request), e.getMessage());
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.AIRPORT_CATALOG_UNAVAILABLE,
                "No se pudo validar los aeropuertos del itinerario contra el maestro. Reintentá en unos segundos.",
                request);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(CATALOG_RETRY_AFTER_SECONDS))
                .body(problem);
    }

    /**
     * La integración con el maestro está rota: credencial, permisos o contrato.
     *
     * <p>Es un defecto nuestro, así que va en ERROR con el detalle del lado del
     * servidor y hacia afuera sale un 500 genérico: el cliente no puede hacer
     * nada distinto y el mensaje interno no le sirve —le filtraría cómo está
     * integrado el sistema—.
     */
    @ExceptionHandler(AirportCatalogIntegrationException.class)
    public ProblemDetail handleCatalogIntegration(AirportCatalogIntegrationException e, WebRequest request) {
        log.error("Integración con el maestro de aeropuertos rota procesando {}", pathOf(request), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                "Ocurrió un error inesperado procesando el pedido.", request);
    }

    // ------------------------------------------------------------------
    // Armado del cuerpo
    // ------------------------------------------------------------------

    private static ProblemDetail problem(HttpStatus status, ApiErrorCode code, String detail, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        enrich(problem, code, request);
        return problem;
    }

    private static void enrich(ProblemDetail problem, ApiErrorCode code, WebRequest request) {
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());

        String path = pathOf(request);
        if (path != null) {
            problem.setInstance(URI.create(path));
        }
    }

    private static List<FieldErrorResponse> toFieldErrors(MethodArgumentNotValidException e) {
        return e.getBindingResult().getAllErrors().stream()
                .map(ReservationExceptionHandler::toFieldError)
                .toList();
    }

    private static FieldErrorResponse toFieldError(ObjectError error) {
        String field = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
        return new FieldErrorResponse(field, constraintCodeOf(error), error.getDefaultMessage());
    }

    /**
     * Nombre de la restricción violada, normalizado.
     *
     * <p>Bean Validation lo da como {@code NotBlank} o {@code Pattern}; el
     * contrato lo publica en mayúsculas con guiones bajos, igual que el resto
     * de los códigos.
     */
    private static String constraintCodeOf(ObjectError error) {
        String code = error.getCode();
        if (code == null) {
            return ApiErrorCode.VALIDATION_ERROR.name();
        }
        return code.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static ApiErrorCode codeOf(DomainException e) {
        if (e instanceof ItineraryAlreadyDepartedException) {
            return ApiErrorCode.ITINERARY_ALREADY_DEPARTED;
        }
        if (e instanceof InvalidPassengerException) {
            return ApiErrorCode.INVALID_PASSENGER;
        }
        if (e instanceof InvalidReservationException || e instanceof InvalidUserException) {
            return ApiErrorCode.INVALID_RESERVATION;
        }
        // Itinerario, segmento, importe y código de aeropuerto describen todos
        // un itinerario mal formado desde el punto de vista del cliente.
        return ApiErrorCode.INVALID_ITINERARY;
    }

    /**
     * Código para los errores que resuelve el framework, donde no hay una
     * excepción de negocio de la cual deducirlo.
     */
    private static ApiErrorCode defaultCodeFor(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return ApiErrorCode.INTERNAL_ERROR;
        }
        return switch (status.value()) {
            // Una ruta que no existe, no una reserva que no existe.
            case 404 -> ApiErrorCode.RESOURCE_NOT_FOUND;
            case 400 -> ApiErrorCode.VALIDATION_ERROR;
            default -> ApiErrorCode.UNSUPPORTED_REQUEST;
        };
    }

    /**
     * Ruta del pedido, para el {@code instance} del cuerpo de error.
     *
     * <p>Devuelve {@code null} fuera de un pedido servlet: es preferible no
     * informar el campo antes que inventar una URI con el formato de
     * {@code getDescription}, que no es una.
     */
    private static String pathOf(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI()
                : null;
    }
}
