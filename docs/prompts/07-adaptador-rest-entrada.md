# 07 — Implementación del adaptador REST de entrada

**Etapa:** Implementación

**Salida esperada:** Controllers, DTOs, mappers, manejo de errores y tests del adaptador de entrada

---

## Rol

Actúa como desarrollador backend Java/Spring Boot con foco en arquitectura hexagonal, implementando el adaptador de entrada HTTP a partir de un contrato OpenAPI ya acordado.

## Contexto

Existe un proyecto Maven con **Java 21 + Spring Boot 3.5** y arquitectura hexagonal (un solo módulo, separación por paquetes):

```
com.edteam.reservations
├── domain          # model, event, exception — sin Spring, sin JPA, sin HTTP
├── application     # port/in (casos de uso + comandos), port/out, service, outbox, exception
└── infrastructure  # adapter/in/rest (vacío), adapter/in/scheduling,
                    # adapter/out/persistence, adapter/out/airport,
                    # adapter/out/notification, adapter/out/outbox, config
```

El dominio, los casos de uso y la persistencia sobre PostgreSQL ya están implementados y testeados. Lo único que falta es la punta HTTP: **`infrastructure/adapter/in/rest` está vacío a propósito**, con un `package-info.java` que documenta lo que va ahí.

Los puertos de entrada disponibles son `CreateReservationUseCase`, `GetReservationUseCase`, `ModifyReservationUseCase`, `ConfirmReservationUseCase`, `CancelReservationUseCase` y `DispatchPendingNotificationsUseCase`. Sus comandos (`CreateReservationCommand`, `ModifyReservationCommand`, `ConfirmReservationCommand`, `CancelReservationCommand`) y sus DTOs de entrada (`ItineraryData`, `SegmentData`, `PassengerData`) hablan en tipos primitivos, no en value objects del dominio.

Ya existe el contrato OpenAPI 3.0 generado en el paso anterior, que define los endpoints de crear, obtener, listar, actualizar y cancelar reservas, con los códigos 200/201/400/404/409.

Hay dos detalles del diseño que el adaptador tiene que respetar sí o sí:

- **Idempotencia:** `CreateReservationCommand` recibe una `idempotencyKey` generada por el cliente; dos pedidos con la misma clave deben producir una sola reserva. Cuando dos pedidos con la misma clave corren en paralelo, el perdedor recibe `DuplicateReservationException`.
- **Concurrencia optimista:** los comandos de modificación reciben `expectedVersion`, que es la versión que el cliente leyó. Si no coincide con la almacenada, el caso de uso lanza `ConcurrentUpdateException`.

## Tarea

1. Implementar los `@RestController` del paquete `infrastructure.adapter.in.rest` que cumplan exactamente el contrato OpenAPI del paso anterior, hablando **sólo** con los puertos de entrada.
2. Definir los DTOs de request y response propios de esta capa, más los mappers entre esos DTOs y los comandos/agregados de la aplicación.
3. Implementar un `@RestControllerAdvice` que traduzca las excepciones a códigos HTTP y a un cuerpo de error uniforme:
   - `DomainException` y `UnknownAirportException` / `UnknownUserException` → **400**
   - errores de validación de los DTOs (`MethodArgumentNotValidException`) → **400**
   - `ReservationNotFoundException` → **404**
   - `ConcurrentUpdateException`, `ReservationAlreadyCancelledException` y `ReservationNotModifiableException` → **409**
4. Resolver el transporte HTTP de los dos detalles del diseño: la clave de idempotencia como header del `POST` y `expectedVersion` como `ETag` / `If-Match` en las operaciones que modifican.
5. Agregar el caso de uso y el puerto de salida que falten para poder **listar reservas** con paginación, sin filtrar tipos del proveedor de persistencia (`Page`, `Specification`) hacia la aplicación.
6. Exponer el contrato OpenAPI desde la aplicación y verificar que lo que sirve el runtime coincide con el YAML acordado. Exponerlo por medio de alguna librería que se genere de manera automática y exponga un Swagger.
7. Escribir los tests del adaptador (slice de controllers con los puertos mockeados) y extender el test de integración de punta a punta para que ejercite el flujo completo vía HTTP.
8. Actualizar el `package-info.java`, el `README.md` y la sección de _Fuera de alcance_ para reflejar que los endpoints ya están implementados.

## Restricciones

- El agregado del dominio **no se serializa directamente**: los DTOs son propios del adaptador para que el contrato pueda evolucionar sin arrastrar al modelo.
- Los controllers hablan con los puertos de entrada, nunca con los servicios concretos ni con los adaptadores de salida. La regla está verificada por `HexagonalArchitectureTest` (ArchUnit): el build tiene que seguir en verde.
- Nada de `jakarta.persistence` ni de tipos de Spring Web fuera de `infrastructure`.
- Validación declarativa de los DTOs de entrada (agregar `spring-boot-starter-validation` si hace falta), de modo que los datos inválidos no lleguen al caso de uso.
- Cuerpo de error uniforme para todos los endpoints; usar `ProblemDetail` (RFC 7807) y no filtrar stack traces ni mensajes internos.
- Sin seguridad todavía: la autenticación y autorización van en un paso posterior.
- Fechas y horas en UTC, en ISO-8601, tal como ya las maneja la persistencia.
- Si alguna firma de los puertos de entrada tiene que cambiar, justificar el cambio y mantener los tests existentes pasando.

## Formato de salida

Lista de archivos a crear o modificar, agrupados por paquete, con el contenido completo de cada uno (controllers, DTOs, mappers, `@RestControllerAdvice`, puerto y caso de uso de listado, configuración, tests) y los comandos para verificar que el build y los tests siguen pasando.
