package com.edteam.reservations.infrastructure.adapter.in.rest;

import com.edteam.reservations.application.exception.DuplicateReservationException;
import com.edteam.reservations.application.port.in.CancelReservationUseCase;
import com.edteam.reservations.application.port.in.CreateReservationCommand;
import com.edteam.reservations.application.port.in.CreateReservationResult;
import com.edteam.reservations.application.port.in.CreateReservationUseCase;
import com.edteam.reservations.application.port.in.GetReservationQuery;
import com.edteam.reservations.application.port.in.GetReservationUseCase;
import com.edteam.reservations.application.port.in.ListReservationsQuery;
import com.edteam.reservations.application.port.in.ListReservationsUseCase;
import com.edteam.reservations.application.port.in.ModifyReservationUseCase;
import com.edteam.reservations.application.query.ResultPage;
import com.edteam.reservations.domain.access.Actor;
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
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springdoc.core.annotations.ParameterObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import java.util.OptionalLong;
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
 *
 * <h2>Lo que este adaptador hace con el cache</h2>
 * Dos cosas, y las dos son de protocolo:
 *
 * <ul>
 *   <li><b>Peticiones condicionales.</b> El {@code ETag} ya se emitía; ahora
 *       se acepta {@code If-None-Match} en la lectura por id y se responde
 *       {@code 304 Not Modified} cuando el cliente ya tiene esa versión.
 *       {@link ReservationVersionCache} permite resolver ese 304 sin tocar la
 *       base: es lo único que se cachea de este endpoint, porque el cuerpo
 *       lleva documento y fecha de nacimiento de personas físicas.</li>
 *   <li><b>{@code Cache-Control: no-store, private} en toda respuesta de
 *       reserva.</b> Por la misma razón: la API todavía no tiene
 *       autenticación, así que una representación guardada por un proxy
 *       compartido, un CDN o el disco del navegador queda legible para
 *       cualquiera que la alcance. El ahorro del 304 no se pierde: el cliente
 *       que reenvía el {@code ETag} es el mismo que ya tiene que conservarlo
 *       para poder mandar {@code If-Match}, y eso es estado de la aplicación,
 *       no un cache HTTP.</li>
 * </ul>
 *
 * <p>Toda operación que escribe invalida la versión cacheada después de que el
 * caso de uso devolvió —o sea, con la transacción ya confirmada—. Ver
 * {@link ReservationVersionCache} por qué se borra y no se actualiza, y por
 * qué esto no puede generar un {@code 409} evitable.
 */
@RestController
@RequestMapping(path = "/v1/reservations", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = OpenApiConfiguration.RESERVATIONS_TAG)
// Las cinco operaciones requieren token. Se declara en el controller y no
// operación por operación para que un endpoint nuevo lo herede sin que nadie
// se acuerde de agregarlo: el esquema está definido en OpenApiConfiguration.
@SecurityRequirement(name = OpenApiConfiguration.BEARER_SCHEME)
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

    private static final String NOT_FOUND_DESCRIPTION = """
            No existe una reserva con ese identificador **para quien la pide**.

            Una reserva de otro usuario responde exactamente esto y no un 403: si los dos
            casos se distinguieran, recorrer los identificadores diría cuántas reservas
            hay en el sistema y cuáles están ocupadas.""";

    private static final String UNAUTHORIZED_DESCRIPTION = """
            Falta el token Bearer, está vencido o no valida. La respuesta no dice cuál de
            los tres: el detalle le indicaría a quien está probando tokens qué corregir.""";

    private static final String FORBIDDEN_DESCRIPTION = """
            El token es válido pero no alcanza para esta operación.""";

    private static final String TOO_MANY_REQUESTS_DESCRIPTION = """
            Se superó la cuota de pedidos de la identidad (o de la IP, si el pedido no está
            autenticado). Reintentable respetando el header `Retry-After`.""";

    private static final String IF_NONE_MATCH_DESCRIPTION = """
            `ETag` de la representación que el cliente ya tiene. Si coincide con la
            versión actual, la respuesta es **304** sin cuerpo.

            A diferencia de `If-Match`, es opcional y tolerante: un valor que no se
            entiende no es un 400, simplemente devuelve la representación completa. Se
            admite la lista separada por comas, la forma débil (`W/\"7\"`) y el comodín
            `*`.""";

    /**
     * Ninguna representación de reserva puede quedar guardada fuera del
     * cliente: llevan documento y fecha de nacimiento de los pasajeros.
     *
     * <p>Sigue siendo {@code no-store} ahora que hay autenticación, y con más
     * razón: una respuesta autenticada guardada por un proxy compartido se le
     * puede servir al pedido siguiente, que trae otro token.
     */
    private static final CacheControl NO_STORE = CacheControl.noStore().cachePrivate();

    private final CreateReservationUseCase createReservation;
    private final GetReservationUseCase getReservation;
    private final ListReservationsUseCase listReservations;
    private final ModifyReservationUseCase modifyReservation;
    private final CancelReservationUseCase cancelReservation;
    private final ReservationRestMapper mapper;
    private final ReservationVersionCache versionCache;

    public ReservationController(CreateReservationUseCase createReservation,
                                 GetReservationUseCase getReservation,
                                 ListReservationsUseCase listReservations,
                                 ModifyReservationUseCase modifyReservation,
                                 CancelReservationUseCase cancelReservation,
                                 ReservationRestMapper mapper,
                                 ReservationVersionCache versionCache) {
        this.createReservation = Objects.requireNonNull(createReservation);
        this.getReservation = Objects.requireNonNull(getReservation);
        this.listReservations = Objects.requireNonNull(listReservations);
        this.modifyReservation = Objects.requireNonNull(modifyReservation);
        this.cancelReservation = Objects.requireNonNull(cancelReservation);
        this.mapper = Objects.requireNonNull(mapper);
        this.versionCache = Objects.requireNonNull(versionCache);
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
                    Crea una reserva en estado `PENDING` a nombre de quien la pide.

                    ## El usuario

                    Sale del token, no del cuerpo. El email del claim `email` (o del `sub`,
                    si es un email) identifica al comprador; `given_name` y `family_name`
                    completan su alta la primera vez que reserva. Si ya reservó antes se
                    reutiliza su registro y su nombre almacenado no se modifica. No hace
                    falta —ni es posible— darlo de alta por separado: la API no expone un
                    recurso de usuarios.

                    **No hay forma de reservar a nombre de otro.** Ese era el problema del
                    esquema anterior, donde el email viajaba en el cuerpo: cualquiera creaba
                    una reserva a nombre de una víctima y la notificación de "tu reserva" le
                    llegaba a ella desde nuestro canal.

                    El email es también su identificador en el resto de la API: vuelve en
                    `userId` de la respuesta.

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
                            schema = @Schema(implementation = ApiProblem.class))),
            @ApiResponse(responseCode = "401", description = UNAUTHORIZED_DESCRIPTION,
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class))),
            @ApiResponse(responseCode = "429", description = TOO_MANY_REQUESTS_DESCRIPTION,
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
            @Valid @RequestBody CreateReservationRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Actor actor) {

        CreateReservationCommand command = mapper.toCommand(request, idempotencyKey, actor);
        CreateReservationResult result = createOnce(command);
        Reservation reservation = result.reservation();

        // No invalida nada, a diferencia del PUT y el DELETE. Un alta efectiva
        // estrena un id, que por definición no tiene versión cacheada; y un
        // reintento devuelve la reserva ya creada sin cambiarle la versión, así
        // que lo que hubiera en el cache sigue siendo correcto.

        ResponseEntity.BodyBuilder response = result.created()
                ? ResponseEntity.created(locationOf(reservation))
                : ResponseEntity.ok();

        return response.eTag(EntityVersion.toETag(reservation.version()))
                .cacheControl(NO_STORE)
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

    /**
     * Devuelve la reserva, con su {@code ETag} para poder modificarla después,
     * o {@code 304} si el cliente ya tiene esa versión.
     *
     * <p>El 304 se resuelve en dos niveles, y el orden importa:
     * <ol>
     *   <li>Si la versión está en {@link ReservationVersionCache} y coincide
     *       con el {@code If-None-Match}, se responde sin llamar al caso de
     *       uso: ni consulta, ni hidratación de cinco tablas, ni
     *       serialización de un solo dato de pasajero. Es el ahorro que
     *       justifica el cache.</li>
     *   <li>Si no está —cache frío o recién invalidado—, se lee del origen y
     *       se compara contra la versión real. Ahí el 304 ahorra el payload
     *       pero no la consulta, y de paso deja la versión cacheada para la
     *       próxima.</li>
     * </ol>
     *
     * <p>Una entrada desactualizada no puede provocar un {@code 409} evitable:
     * ver el razonamiento completo en {@link ReservationVersionCache}.
     */
    @GetMapping("/{reservationId}")
    @Operation(operationId = "getReservation",
            summary = "Obtener una reserva",
            description = """
                    Devuelve la representación completa de la reserva, con su itinerario y
                    sus pasajeros resueltos.

                    El `ETag` de la respuesta es el que hay que enviar en `If-Match` para
                    actualizar o cancelar la reserva.

                    ## Lecturas condicionales

                    Reenviando ese mismo `ETag` en `If-None-Match`, una reserva que no
                    cambió se responde con **304** y sin cuerpo. Es lo recomendado para
                    las pantallas que refrescan periódicamente.

                    ## Por qué la respuesta es `no-store`

                    El cuerpo incluye documento y fecha de nacimiento de los pasajeros, y
                    la API todavía no tiene autenticación. `Cache-Control: no-store,
                    private` impide que un proxy compartido, un CDN o el disco del
                    navegador conserven esos datos. Conservar el `ETag` en el estado de la
                    aplicación cliente —que es lo que ya hace falta para poder mandar
                    `If-Match`— no está afectado por eso.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reserva encontrada.",
                    headers = @Header(name = "ETag", description = ETAG_DESCRIPTION,
                            schema = @Schema(type = "string", example = "\"7\""))),
            @ApiResponse(responseCode = "304",
                    description = "La reserva no cambió respecto del `ETag` enviado en "
                            + "`If-None-Match`. No lleva cuerpo.",
                    headers = @Header(name = "ETag", description = ETAG_DESCRIPTION,
                            schema = @Schema(type = "string", example = "\"7\""))),
            @ApiResponse(responseCode = "400", description = "El identificador no es válido.",
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND_DESCRIPTION,
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class))),
            @ApiResponse(responseCode = "401", description = UNAUTHORIZED_DESCRIPTION,
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class))),
            @ApiResponse(responseCode = "429", description = TOO_MANY_REQUESTS_DESCRIPTION,
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class)))
    })
    public ResponseEntity<ReservationResponse> getById(
            @Parameter(description = "Identificador opaco de la reserva.", example = "1042")
            @PathVariable long reservationId,
            @Parameter(name = "If-None-Match", in = ParameterIn.HEADER,
                    description = IF_NONE_MATCH_DESCRIPTION, example = "\"7\"")
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @Parameter(hidden = true) @AuthenticationPrincipal Actor actor) {

        ReservationId id = ReservationId.of(reservationId);

        // El atajo del 304 sigue exigiendo que el cliente traiga el ETag de
        // ESA versión, que sólo pudo obtener de una lectura autorizada previa.
        // No es un bypass de la autorización: es una respuesta vacía a alguien
        // que ya tenía el contenido.
        OptionalLong knownVersion = ifNoneMatch == null ? OptionalLong.empty() : versionCache.find(id);
        if (knownVersion.isPresent() && EntityVersion.matchesIfNoneMatch(ifNoneMatch, knownVersion.getAsLong())) {
            return notModified(knownVersion.getAsLong());
        }

        Reservation reservation = getReservation.get(new GetReservationQuery(id, actor));
        versionCache.remember(id, reservation.version());

        if (EntityVersion.matchesIfNoneMatch(ifNoneMatch, reservation.version())) {
            return notModified(reservation.version());
        }

        return ResponseEntity.ok()
                .eTag(EntityVersion.toETag(reservation.version()))
                .cacheControl(NO_STORE)
                .body(mapper.toResponse(reservation));
    }

    /** {@code 304} con el {@code ETag} que el cliente tiene que seguir usando. */
    private static ResponseEntity<ReservationResponse> notModified(long version) {
        return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .eTag(EntityVersion.toETag(version))
                .cacheControl(NO_STORE)
                .build();
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
                    Devuelve una página de **tus** reservas, de la más reciente a la más
                    antigua.

                    El alcance no se elige: se deriva del token. Un cliente sin rol de
                    backoffice ve sus reservas y sólo las suyas, mande lo que mande en
                    `userId`; con el email de otro, la respuesta es 403. Un cliente de
                    backoffice puede filtrar por cualquier usuario, o por ninguno.

                    Los demás filtros son opcionales y se combinan con AND. La paginación
                    es obligatoria e incondicional: si el cliente no envía `page` y `size`
                    se aplican los valores por defecto, nunca se devuelve la colección
                    completa.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Página de reservas. Puede venir vacía; eso no es un error."),
            @ApiResponse(responseCode = "400", description = "Algún parámetro de consulta es inválido.",
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class))),
            @ApiResponse(responseCode = "403",
                    description = "Se pidió el listado de otro usuario sin el rol que lo permite.",
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class))),
            @ApiResponse(responseCode = "401", description = UNAUTHORIZED_DESCRIPTION,
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class))),
            @ApiResponse(responseCode = "429", description = TOO_MANY_REQUESTS_DESCRIPTION,
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class)))
    })
    public ResponseEntity<ReservationPageResponse> list(
            // @ParameterObject expande el record en sus parámetros de consulta.
            // Sin esto el documento declara un único parámetro 'params' de tipo
            // objeto, que no le dice a nadie cómo se llama la API.
            @ParameterObject @Valid @ModelAttribute ListReservationsParams params,
            @Parameter(hidden = true) @AuthenticationPrincipal Actor actor) {
        ResultPage<Reservation> page = listReservations.list(
                new ListReservationsQuery(mapper.toCriteria(params), actor));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(mapper.toResponse(page));
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
                            schema = @Schema(implementation = ApiProblem.class))),
            @ApiResponse(responseCode = "401", description = UNAUTHORIZED_DESCRIPTION,
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class))),
            @ApiResponse(responseCode = "429", description = TOO_MANY_REQUESTS_DESCRIPTION,
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class)))
    })
    public ResponseEntity<ReservationResponse> update(
            @Parameter(description = "Identificador opaco de la reserva.", example = "1042")
            @PathVariable long reservationId,
            @Parameter(name = "If-Match", in = ParameterIn.HEADER, required = true,
                    description = IF_MATCH_DESCRIPTION, example = "\"7\"")
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody UpdateReservationRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal Actor actor) {

        long expectedVersion = EntityVersion.parseIfMatch(ifMatch);
        Reservation modified = modifyReservation.modify(
                mapper.toCommand(reservationId, expectedVersion, request.itinerary(), actor));

        // Después del caso de uso, o sea con la transacción ya confirmada: si
        // se borrara antes, una lectura concurrente podría repoblar el cache
        // con la versión vieja entre el borrado y el commit.
        versionCache.forget(ReservationId.of(reservationId));

        return ResponseEntity.ok()
                .eTag(EntityVersion.toETag(modified.version()))
                .cacheControl(NO_STORE)
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
                            schema = @Schema(implementation = ApiProblem.class))),
            @ApiResponse(responseCode = "401", description = UNAUTHORIZED_DESCRIPTION,
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class))),
            @ApiResponse(responseCode = "429", description = TOO_MANY_REQUESTS_DESCRIPTION,
                    content = @Content(mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = ApiProblem.class)))
    })
    public ResponseEntity<ReservationResponse> cancel(
            @Parameter(description = "Identificador opaco de la reserva.", example = "1042")
            @PathVariable long reservationId,
            @Parameter(name = "If-Match", in = ParameterIn.HEADER, required = true,
                    description = IF_MATCH_DESCRIPTION, example = "\"7\"")
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Parameter(hidden = true) @AuthenticationPrincipal Actor actor) {

        long expectedVersion = EntityVersion.parseIfMatch(ifMatch);
        Reservation cancelled = cancelReservation.cancel(
                mapper.toCancelCommand(reservationId, expectedVersion, actor));

        versionCache.forget(ReservationId.of(reservationId));

        return ResponseEntity.ok()
                .eTag(EntityVersion.toETag(cancelled.version()))
                .cacheControl(NO_STORE)
                .body(mapper.toResponse(cancelled));
    }

    private static URI locationOf(Reservation reservation) {
        return UriComponentsBuilder.fromPath("/v1/reservations/{id}")
                .buildAndExpand(reservation.requireId().value())
                .toUri();
    }
}
