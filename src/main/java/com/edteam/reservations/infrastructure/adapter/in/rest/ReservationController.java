package com.edteam.reservations.infrastructure.adapter.in.rest;

import com.edteam.reservations.application.exception.DuplicateReservationException;
import com.edteam.reservations.application.port.in.CancelReservationUseCase;
import com.edteam.reservations.application.port.in.CreateReservationCommand;
import com.edteam.reservations.application.port.in.CreateReservationResult;
import com.edteam.reservations.application.port.in.CreateReservationUseCase;
import com.edteam.reservations.application.port.in.GetReservationUseCase;
import com.edteam.reservations.application.port.in.ListReservationsUseCase;
import com.edteam.reservations.application.port.in.ModifyReservationUseCase;
import com.edteam.reservations.application.query.ResultPage;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.domain.model.ReservationId;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.CreateReservationRequest;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ListReservationsParams;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ReservationPageResponse;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ReservationResponse;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.UpdateReservationRequest;
import com.edteam.reservations.infrastructure.adapter.in.rest.dto.ApiProblem;
import com.edteam.reservations.infrastructure.adapter.in.rest.mapper.ReservationRestMapper;
import com.edteam.reservations.infrastructure.config.OpenApiConfiguration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/**
 * Adaptador de entrada HTTP para el recurso {@code reservations}.
 *
 * <p>Habla sólo con los puertos de entrada: no conoce los servicios que los
 * implementan ni ningún adaptador de salida, y esa regla está verificada por
 * {@code HexagonalArchitectureTest}.
 *
 * <p>Las anotaciones {@code @Operation} y {@code @ApiResponse} no son adorno:
 * son el contrato. springdoc genera el documento OpenAPI a partir de ellas más
 * los mappings y los DTOs, así que un código de estado que se implemente y no
 * se declare acá queda fuera de la documentación. {@code OpenApiContractTest}
 * es el que impide que eso pase inadvertido.
 *
 * <p>El controller no tiene lógica de negocio. Su trabajo es exactamente tres
 * cosas: traducir HTTP a comandos, traducir agregados a DTOs y traducir el
 * resultado a un código de estado. Todo lo que parece una decisión —qué pasa
 * con un reintento, qué pasa con una versión vieja— lo decide el caso de uso;
 * acá sólo se expresa en el vocabulario del protocolo.
 *
 * <h2>Dos detalles del diseño, en términos de HTTP</h2>
 * <ul>
 *   <li><b>Idempotencia</b> → header {@code Idempotency-Key} en el alta. Es del
 *       intento, no del recurso, así que va en un header y no en el cuerpo.</li>
 *   <li><b>Concurrencia optimista</b> → {@code ETag} en las respuestas e
 *       {@code If-Match} en las operaciones que escriben. Es el mecanismo
 *       estándar de HTTP para lo mismo que hace {@code expectedVersion}.</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/v1/reservations", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = OpenApiConfiguration.RESERVATIONS_TAG)
public class ReservationController {

    private static final Logger log = LoggerFactory.getLogger(ReservationController.class);

    /** Clave de idempotencia del alta, generada por el cliente. */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private static final String ETAG_DESCRIPTION =
            "Versión actual del recurso. Se envía tal cual en `If-Match` al actualizar o cancelar.";

    private static final String IF_MATCH_DESCRIPTION = """
            `ETag` de la última representación leída de la reserva. Sirve para detectar
            escrituras concurrentes.

            Ausente o malformado, la respuesta es 400: sin saber sobre qué versión trabajó
            el cliente no hay forma de detectar una escritura concurrente, y dos pedidos
            simultáneos se pisarían en silencio. Se admite también la forma débil
            (`W/"7"`), porque algunos proxies reescriben así el `ETag` que ellos mismos
            comprimieron.""";

    private static final String NOT_FOUND_DESCRIPTION = "No existe una reserva con ese identificador.";

    private final CreateReservationUseCase createReservation;
    private final GetReservationUseCase getReservation;
    private final ListReservationsUseCase listReservations;
    private final ModifyReservationUseCase modifyReservation;
    private final CancelReservationUseCase cancelReservation;
    private final ReservationRestMapper mapper;

    public ReservationController(CreateReservationUseCase createReservation,
                                 GetReservationUseCase getReservation,
                                 ListReservationsUseCase listReservations,
                                 ModifyReservationUseCase modifyReservation,
                                 CancelReservationUseCase cancelReservation,
                                 ReservationRestMapper mapper) {
        this.createReservation = Objects.requireNonNull(createReservation);
        this.getReservation = Objects.requireNonNull(getReservation);
        this.listReservations = Objects.requireNonNull(listReservations);
        this.modifyReservation = Objects.requireNonNull(modifyReservation);
        this.cancelReservation = Objects.requireNonNull(cancelReservation);
        this.mapper = Objects.requireNonNull(mapper);
    }

    /**
     * Crea una reserva, o devuelve la que ya existe para esa clave de
     * idempotencia.
     *
     * <p>Responde {@code 201} con {@code Location} cuando el alta es efectiva y
     * {@code 200} cuando es un reintento: el cliente puede distinguir si su
     * pedido creó algo o si está viendo el resultado de un intento anterior.
     *
     * <p>El header llega tipado como {@link UUID}: si no lo es, la conversión
     * falla antes de entrar acá y la respuesta es un 400, sin que el caso de
     * uso se entere.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createReservation",
            summary = "Crear una reserva",
            description = """
                    Crea una reserva en estado `PENDING` para el usuario indicado.

                    ## El usuario

                    Se lo identifica por su email, que viaja en el cuerpo junto con su
                    nombre. Si ya reservó antes se reutiliza su registro; si es la
                    primera vez, se lo da de alta como parte de la reserva. No hace
                    falta —ni es posible— darlo de alta por separado: la API no expone
                    un recurso de usuarios.

                    El email es también su identificador en el resto de la API: vuelve
                    en `userId` de la respuesta y es lo que se pasa como `userId` para
                    filtrar el listado.

                    ## Idempotencia

                    El header `Idempotency-Key` es obligatorio: el cliente genera un UUID
                    por intento de reserva y lo reenvía en cada reintento.

                    - Clave nueva → se crea la reserva y se responde **201**, con
                      `Location` apuntando al recurso creado.
                    - Clave ya usada → se responde **200** con la reserva que se creó con
                      esa clave. El reintento es seguro y no duplica nada.

                    La clave manda sobre el cuerpo: si un reintento llega con la misma
                    clave y un contenido distinto, se devuelve igual la reserva original
                    y el contenido nuevo se ignora. La clave identifica *el intento*, y
                    dos intentos con la misma clave son el mismo pedido para el cliente.

                    Cuando dos pedidos con la misma clave llegan a la vez, la restricción
                    de unicidad del almacenamiento deja pasar a uno solo; el otro se
                    resuelve internamente y también recibe **200** con la reserva
                    ganadora.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reserva creada.",
                    headers = {
                            @Header(name = "Location", description = "URI de la reserva creada.",
                                    schema = @Schema(type = "string", example = "/v1/reservations/1042")),
                            @Header(name = "ETag", description = ETAG_DESCRIPTION,
                                    schema = @Schema(type = "string", example = "\"0\""))
                    }),
            @ApiResponse(responseCode = "200",
                    description = "Reintento: la clave ya había sido usada. Devuelve la "
                            + "reserva creada con esa clave, sin crear una nueva.",
                    headers = @Header(name = "ETag", description = ETAG_DESCRIPTION,
                            schema = @Schema(type = "string", example = "\"0\""))),
            @ApiResponse(responseCode = "400",
                    description = """
                            El pedido es inválido. Además de los errores de formato, incluye
                            los datos que existen pero no resuelven:

                            - `USER_NOT_FOUND`: el usuario del cuerpo no se pudo
                              resolver. Es 400 y no 404 porque el recurso que identifica
                              la URI —la colección de reservas— sí existe: lo que está
                              mal es un dato del cuerpo.
                            - `UNKNOWN_AIRPORT`: algún código de aeropuerto no está en el
                              catálogo.
                            - `ITINERARY_ALREADY_DEPARTED`: el primer tramo ya salió.""",
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class))),
            @ApiResponse(responseCode = "409",
                    description = "No se pudo resolver la carrera por la `Idempotency-Key`. "
                            + "Es una situación excepcional: el caso normal de clave "
                            + "repetida responde 200.",
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class)))
    })
    public ResponseEntity<ReservationResponse> create(
            @Parameter(name = IDEMPOTENCY_KEY_HEADER, in = ParameterIn.HEADER, required = true,
                    description = """
                            UUID generado por el cliente que identifica el intento de
                            creación. Debe mantenerse constante entre reintentos del mismo
                            pedido y ser distinto entre pedidos distintos.""",
                    example = "3f1a9c7e-0f6e-4a39-9d2c-8b5f0c1e7a44")
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) UUID idempotencyKey,
            @Valid @RequestBody CreateReservationRequest request) {

        CreateReservationCommand command = mapper.toCommand(request, idempotencyKey);
        CreateReservationResult result = createOnce(command);
        Reservation reservation = result.reservation();

        ResponseEntity.BodyBuilder response = result.created()
                ? ResponseEntity.created(locationOf(reservation))
                : ResponseEntity.ok();

        return response.eTag(EntityVersion.toETag(reservation.version()))
                .body(mapper.toResponse(reservation));
    }

    /**
     * Ejecuta el alta resolviendo la carrera por la clave de idempotencia.
     *
     * <p>Cuando dos pedidos con la misma clave corren a la vez, la búsqueda
     * previa del caso de uso no alcanza —ninguno ve al otro todavía— y el
     * {@code UNIQUE} de la base deja al perdedor con
     * {@link DuplicateReservationException}, con su transacción ya descartada.
     *
     * <p>El reintento tiene que ocurrir acá y no adentro del caso de uso: una
     * violación de constraint deja la transacción marcada como
     * <em>rollback-only</em>, así que volver a consultar en ese mismo contexto
     * no serviría de nada. Desde el adaptador, en cambio, el segundo intento
     * abre una transacción nueva y encuentra la reserva ganadora.
     *
     * <p>Un solo reintento: si vuelve a fallar no es una carrera sino un
     * problema real, y se responde 409 en lugar de insistir.
     */
    private CreateReservationResult createOnce(CreateReservationCommand command) {
        try {
            return createReservation.create(command);
        } catch (DuplicateReservationException e) {
            log.info("Carrera por la clave de idempotencia {}: se reintenta para devolver la reserva ganadora",
                    command.idempotencyKey());
            return createReservation.create(command);
        }
    }

    /** Devuelve la reserva, con su {@code ETag} para poder modificarla después. */
    @GetMapping("/{reservationId}")
    @Operation(operationId = "getReservation",
            summary = "Obtener una reserva",
            description = """
                    Devuelve la representación completa de la reserva, con su itinerario y
                    sus pasajeros resueltos.

                    El `ETag` de la respuesta es el que hay que enviar en `If-Match` para
                    actualizar o cancelar la reserva.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reserva encontrada.",
                    headers = @Header(name = "ETag", description = ETAG_DESCRIPTION,
                            schema = @Schema(type = "string", example = "\"7\""))),
            @ApiResponse(responseCode = "400", description = "El identificador no es válido.",
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND_DESCRIPTION,
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class)))
    })
    public ResponseEntity<ReservationResponse> getById(
            @Parameter(description = "Identificador opaco de la reserva.", example = "1042")
            @PathVariable long reservationId) {
        Reservation reservation = getReservation.getById(ReservationId.of(reservationId));

        return ResponseEntity.ok()
                .eTag(EntityVersion.toETag(reservation.version()))
                .body(mapper.toResponse(reservation));
    }

    /**
     * Lista reservas con filtros y paginación.
     *
     * <p>No lleva {@code ETag}: el header identifica la versión de <em>un</em>
     * recurso, y una página es una composición de varios. Para modificar una
     * reserva de la lista hay que leerla primero.
     */
    @GetMapping
    @Operation(operationId = "listReservations",
            summary = "Listar reservas",
            description = """
                    Devuelve una página de reservas, de la más reciente a la más antigua.

                    Todos los filtros son opcionales y se combinan con AND. La paginación
                    es obligatoria e incondicional: si el cliente no envía `page` y `size`
                    se aplican los valores por defecto, nunca se devuelve la colección
                    completa.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Página de reservas. Puede venir vacía; eso no es un error."),
            @ApiResponse(responseCode = "400", description = "Algún parámetro de consulta es inválido.",
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class)))
    })
    public ResponseEntity<ReservationPageResponse> list(
            // @ParameterObject expande el record en sus parámetros de consulta.
            // Sin esto el documento declara un único parámetro 'params' de tipo
            // objeto, que no le dice a nadie cómo se llama la API.
            @ParameterObject @Valid @ModelAttribute ListReservationsParams params) {
        ResultPage<Reservation> page = listReservations.list(mapper.toCriteria(params));
        return ResponseEntity.ok(mapper.toResponse(page));
    }

    /**
     * Reemplaza el itinerario de la reserva.
     *
     * <p>{@code If-Match} es obligatorio: sin él no hay forma de saber sobre
     * qué versión trabajó el cliente, y dos pedidos concurrentes se pisarían en
     * silencio. Si la versión no coincide, el caso de uso corta con
     * {@code ConcurrentUpdateException} y la respuesta es 409, sin escribir.
     */
    @PutMapping(path = "/{reservationId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateReservation",
            summary = "Actualizar el itinerario de una reserva",
            description = """
                    Reemplaza el itinerario de una reserva vigente. Es un reemplazo
                    completo: la lista de segmentos enviada pasa a ser la única del
                    itinerario.

                    Los pasajeros y el usuario no se modifican por esta operación: son
                    parte de la identidad comercial de la reserva. Cambiarlos implica
                    cancelar y volver a reservar.

                    Requiere `If-Match` con el `ETag` obtenido en la última lectura. Si la
                    reserva cambió mientras tanto, la operación se rechaza con **409** y
                    no escribe nada; el cliente debe releer y decidir.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reserva actualizada.",
                    headers = @Header(name = "ETag", description = ETAG_DESCRIPTION,
                            schema = @Schema(type = "string", example = "\"8\""))),
            @ApiResponse(responseCode = "400",
                    description = "El cuerpo es inválido, el `If-Match` está ausente o "
                            + "malformado, o el itinerario vigente ya despegó "
                            + "(`ITINERARY_ALREADY_DEPARTED`).",
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND_DESCRIPTION,
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class))),
            @ApiResponse(responseCode = "409",
                    description = """
                            La operación choca con el estado actual del recurso:

                            - `CONCURRENT_UPDATE`: el `If-Match` no coincide con la versión
                              almacenada.
                            - `RESERVATION_NOT_MODIFIABLE`: la reserva está cancelada.""",
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class)))
    })
    public ResponseEntity<ReservationResponse> update(
            @Parameter(description = "Identificador opaco de la reserva.", example = "1042")
            @PathVariable long reservationId,
            @Parameter(name = "If-Match", in = ParameterIn.HEADER, required = true,
                    description = IF_MATCH_DESCRIPTION, example = "\"7\"")
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody UpdateReservationRequest request) {

        long expectedVersion = EntityVersion.parseIfMatch(ifMatch);
        Reservation modified = modifyReservation.modify(
                mapper.toCommand(reservationId, expectedVersion, request.itinerary()));

        return ResponseEntity.ok()
                .eTag(EntityVersion.toETag(modified.version()))
                .body(mapper.toResponse(modified));
    }

    /**
     * Cancela la reserva.
     *
     * <p>La baja es lógica —la reserva queda en {@code CANCELLED} y se sigue
     * pudiendo consultar—, así que la respuesta es 200 con la representación
     * resultante y no 204: el cliente necesita ver el estado final y la fecha
     * de cancelación.
     */
    @DeleteMapping("/{reservationId}")
    @Operation(operationId = "cancelReservation",
            summary = "Cancelar una reserva",
            description = """
                    Cancela la reserva. La cancelación es **lógica**: la reserva pasa a
                    `CANCELLED` y se conserva por trazabilidad, penalidades y reintegros.
                    El recurso sigue siendo accesible por `GET` después de cancelarlo.

                    Por eso la respuesta es **200** con la representación resultante y no
                    un `204`: el cliente necesita ver el estado final y la fecha de
                    cancelación.

                    Requiere `If-Match`, igual que la actualización.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reserva cancelada.",
                    headers = @Header(name = "ETag", description = ETAG_DESCRIPTION,
                            schema = @Schema(type = "string", example = "\"8\""))),
            @ApiResponse(responseCode = "400",
                    description = "El `If-Match` está ausente o malformado, o el itinerario "
                            + "ya despegó (`ITINERARY_ALREADY_DEPARTED`).",
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND_DESCRIPTION,
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class))),
            @ApiResponse(responseCode = "409",
                    description = """
                            La operación choca con el estado actual del recurso:

                            - `CONCURRENT_UPDATE`: el `If-Match` no coincide con la versión
                              almacenada.
                            - `RESERVATION_ALREADY_CANCELLED`: la reserva ya estaba
                              cancelada.""",
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class)))
    })
    public ResponseEntity<ReservationResponse> cancel(
            @Parameter(description = "Identificador opaco de la reserva.", example = "1042")
            @PathVariable long reservationId,
            @Parameter(name = "If-Match", in = ParameterIn.HEADER, required = true,
                    description = IF_MATCH_DESCRIPTION, example = "\"7\"")
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch) {

        long expectedVersion = EntityVersion.parseIfMatch(ifMatch);
        Reservation cancelled = cancelReservation.cancel(
                mapper.toCancelCommand(reservationId, expectedVersion));

        return ResponseEntity.ok()
                .eTag(EntityVersion.toETag(cancelled.version()))
                .body(mapper.toResponse(cancelled));
    }

    private static URI locationOf(Reservation reservation) {
        return UriComponentsBuilder.fromPath("/v1/reservations/{id}")
                .buildAndExpand(reservation.requireId().value())
                .toUri();
    }
}
