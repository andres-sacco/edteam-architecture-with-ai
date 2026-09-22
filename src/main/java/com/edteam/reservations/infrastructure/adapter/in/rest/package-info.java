/**
 * Adaptador de entrada HTTP.
 *
 * <p>El contrato OpenAPI se genera a partir de este código: springdoc lo arma
 * con los mappings, los tipos de los DTOs, sus anotaciones de Bean Validation
 * y las {@code @Operation} / {@code @Schema} que los describen, y lo publica en
 * {@code /v3/api-docs} con Swagger UI en {@code /swagger-ui.html}.
 *
 * <p>Esto convierte a las anotaciones en parte del contrato, no en adorno: un
 * código de estado que se implemente y no se declare queda sin documentar.
 * {@code OpenApiContractTest} compara el documento generado contra las rutas
 * registradas, contra los códigos que el adaptador realmente devuelve y contra
 * el cuerpo de un error real, para que ese hueco no pase inadvertido.
 *
 * <h2>Qué hay acá</h2>
 * <ul>
 *   <li>{@link com.edteam.reservations.infrastructure.adapter.in.rest.ReservationController}
 *       — las cinco operaciones del recurso {@code /v1/reservations}.</li>
 *   <li>{@link com.edteam.reservations.infrastructure.adapter.in.rest.ReservationExceptionHandler}
 *       — traduce excepciones a códigos HTTP con un cuerpo uniforme
 *       ({@code ProblemDetail}, RFC 7807).</li>
 *   <li>{@link com.edteam.reservations.infrastructure.adapter.in.rest.EntityVersion}
 *       — la versión del agregado expresada como {@code ETag} / {@code If-Match}.</li>
 *   <li>{@code dto} — los DTOs de request y response, con su validación y su
 *       descripción para el documento generado.</li>
 *   <li>{@code mapper} — la traducción entre DTOs y comandos/agregados.</li>
 * </ul>
 *
 * <h2>Reglas que sostienen la arquitectura</h2>
 * <ul>
 *   <li>Los controllers hablan con los <b>puertos de entrada</b>
 *       ({@code CreateReservationUseCase} y compañía), nunca con los servicios
 *       concretos ni con los adaptadores de salida. Lo verifica
 *       {@code HexagonalArchitectureTest}.</li>
 *   <li>El agregado <b>no se serializa</b>: los DTOs son propios de esta capa,
 *       para que el contrato pueda evolucionar sin arrastrar al modelo y
 *       viceversa.</li>
 *   <li>Nada de {@code jakarta.persistence} ni de tipos de Spring Web fuera de
 *       {@code infrastructure}.</li>
 * </ul>
 *
 * <h2>Correspondencia de errores</h2>
 * <ul>
 *   <li>{@code DomainException}, {@code UnknownAirportException},
 *       {@code UnknownUserException} y la validación de los DTOs → 400</li>
 *   <li>{@code ReservationNotFoundException} → 404</li>
 *   <li>{@code ConcurrentUpdateException},
 *       {@code ReservationAlreadyCancelledException},
 *       {@code ReservationNotModifiableException} y
 *       {@code DuplicateReservationException} → 409</li>
 * </ul>
 *
 * <p>Falta la seguridad: autenticación y autorización van en un paso posterior.
 */
package com.edteam.reservations.infrastructure.adapter.in.rest;
