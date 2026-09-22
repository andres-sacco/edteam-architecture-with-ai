# Sistema de reservas de vuelos

Backend del sistema de reservas de vuelos. **Java 21 + Spring Boot 3.5 + Maven**, con
arquitectura hexagonal (puertos y adaptadores).

El dominio, los casos de uso, la persistencia sobre PostgreSQL y la API REST están
implementados. El contrato OpenAPI se genera a partir del código con springdoc y se
publica en `/v3/api-docs`, con **Swagger UI** en `/swagger-ui.html`. La seguridad queda
fuera del alcance a propósito (ver [Fuera de alcance](#fuera-de-alcance)).

## Cómo ejecutarlo

Requiere JDK 21 (hay un `.sdkmanrc`: `sdk env install && sdk env`) y Docker.

```bash
docker compose up -d
```

```bash
./mvnw spring-boot:run
```

`docker compose up -d` levanta PostgreSQL, **Redis** (el cache distribuido) y el
catálogo de ciudades. Los tres son opcionales en distinta medida: sin Redis la
aplicación arranca igual y el cache cae al de memoria del proceso
(`CACHE_REDIS_ENABLED=false`), y sin el catálogo se usa el maestro de
aeropuertos en memoria (`reservations.airport-catalog.base-url=`). PostgreSQL sí
hace falta.

La aplicación queda escuchando en **http://localhost:8080** y Flyway crea el esquema en
el arranque. Para verificar que levantó:

```bash
curl http://localhost:8080/actuator/health
```

Y para explorar y probar la API desde el navegador:
**http://localhost:8080/swagger-ui.html** — el botón *Try it out* ejecuta contra
`localhost:8080`, el host desde el que estás mirando la UI, no contra un entorno fijo.

El contrato, en YAML o en JSON:

```bash
curl http://localhost:8080/v3/api-docs.yaml
```

## La API

Cinco operaciones sobre `/v1/reservations`. No hace falta preparar nada en la base: quien
reserva viaja en el cuerpo del alta y se da de alta solo la primera vez.

| Método | Ruta | Qué hace |
|---|---|---|
| `POST` | `/v1/reservations` | Crea una reserva. Requiere `Idempotency-Key` |
| `GET` | `/v1/reservations` | Lista con filtros y paginación |
| `GET` | `/v1/reservations/{id}` | Devuelve una reserva y su `ETag` |
| `PUT` | `/v1/reservations/{id}` | Cambia el itinerario. Requiere `If-Match` |
| `DELETE` | `/v1/reservations/{id}` | Cancela (baja lógica). Requiere `If-Match` |

Crear una reserva:

```bash
curl -i -X POST http://localhost:8080/v1/reservations -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d '{"user":{"email":"ana.perez@example.com","firstName":"Ana","lastName":"Pérez"},"itinerary":{"price":"1250.50","currency":"USD","segments":[{"originAirportCode":"BUE","destinationAirportCode":"SCL","airline":"AEROLINEAS ARGENTINAS","departureAt":"2027-03-15T22:40:00Z"}]},"passengers":[{"firstName":"Ana","lastName":"Pérez","birthDate":"1990-05-20","documentNumber":"30123456"}]}'
```

La respuesta trae `Location` y `ETag: "0"`. Ese `ETag` es lo que hay que mandar en
`If-Match` para modificar o cancelar:

```bash
curl -i -X DELETE http://localhost:8080/v1/reservations/1 -H 'If-Match: "0"'
```

Listar con filtros. El usuario se identifica por email, el mismo que se mandó al crear:

```bash
curl -G http://localhost:8080/v1/reservations --data-urlencode 'userId=ana.perez@example.com' -d 'status=PENDING' -d 'sort=firstDepartureAt,asc' -d 'page=0' -d 'size=20'
```

Los tests, separados por lo que necesitan:

```bash
./mvnw test
```

```bash
./mvnw verify
```

`test` corre los 352 unitarios: rápidos y sin Docker. `verify` agrega los 49 de
integración, que levantan un PostgreSQL con Testcontainers. Ninguno de los dos
necesita Redis: los de integración corren con el cache en memoria, que es
también la forma de verificar en cada build que la aplicación arranca sin el
cache distribuido.

## Estructura

```
com.edteam.reservations
├── domain                      # El centro. Sin Spring, sin JPA, sin HTTP.
│   ├── model                   #   Reservation (agregado), Itinerary, Segment,
│   │                           #   Passenger, User, value objects
│   ├── event                   #   Eventos de dominio (interfaz sellada)
│   └── exception               #   Errores de negocio
├── application                 # Orquestación. Depende sólo del dominio.
│   ├── port/in                 #   Casos de uso + comandos (lo que entra)
│   ├── port/out                #   Contratos hacia afuera (lo que necesita)
│   ├── service                 #   Implementación de los casos de uso
│   ├── query                   #   Criterio de búsqueda y página de resultados
│   ├── outbox                  #   Modelo del outbox de eventos
│   └── exception               #   Errores de orquestación
└── infrastructure              # Detalles reemplazables.
    ├── adapter/in/rest         #   Controllers, DTOs, mappers y manejo de errores
    ├── adapter/in/scheduling   #   Disparador del despacho de notificaciones
    ├── adapter/out/persistence #   PostgreSQL: entidades JPA, mappers, adapter
    ├── adapter/out/airport     #   Maestro de aeropuertos (stub) + cache
    ├── adapter/out/notification#   Notificaciones (stub que loguea)
    ├── adapter/out/outbox      #   Outbox en memoria (stub)
    ├── cache                   #   Almacén del cache: Redis, memoria y métricas
    └── config                  #   Cableado y properties
```

El modelo de datos está en `src/main/resources/db/migration`, versionado con Flyway.
Las clases usan nombres en inglés (como el resto del código) y las tablas y columnas,
en español (como el modelo de datos): la traducción vive en los mappers y en
`ReservationStatusJpa`.

La regla de dependencia (todo apunta hacia adentro) no depende de la disciplina del
equipo: está verificada por `HexagonalArchitectureTest` con ArchUnit, así que se rompe
el build si alguien la viola.

## Decisiones

**Por qué hexagonal.** El sistema tiene que ser consumido por web, mobile y partners
externos. Con la lógica detrás de puertos de entrada, la API REST es un adaptador más:
mañana se agrega un consumidor de mensajería o un cliente gRPC sin tocar los casos de uso.

**La API es un adaptador, no el sistema.** El contrato OpenAPI se genera desde el código
con springdoc: sale de los mappings, de los tipos de los DTOs, de sus anotaciones de
validación y de las `@Operation` / `@Schema` que los describen. No hay archivo que
mantener, así que no puede quedar viejo.

El costo de generarlo es que **lo documentado vale lo que valgan las anotaciones**: un
endpoint sin `@Operation` aparece vacío y un código de estado que el advice devuelve pero
que nadie declaró no aparece en ningún lado, y nada de eso rompe el build por sí solo. Por
eso `OpenApiContractTest` compara el documento contra tres cosas que no son las
anotaciones: las rutas que Spring tiene registradas (en los dos sentidos), los códigos de
estado que `ReservationControllerTest` ejercita contra el controller real, y el cuerpo de
una respuesta de error de verdad, propiedad por propiedad.

Los DTOs son propios del adaptador y el agregado nunca se serializa —son también los que
el generador describe—. Dos consecuencias concretas: los ids salen como strings opacos (que en la base sean enteros no es asunto
del cliente) y la representación no expone ni la versión ni la clave de idempotencia.

**Quién reserva viaja con la reserva.** El sistema no expone un alta de usuarios, así que
pedir un `userId` en el alta obligaba a que la fila ya estuviera cargada por fuera de la
aplicación: cualquier id que no existiera chocaba contra la clave foránea. El alta ahora
lleva el email, el nombre y el apellido de quien reserva, y el usuario se resuelve por
email: si ya reservó antes se reutiliza su registro, y si no, se lo da de alta.

Es el mismo criterio que el adaptador de persistencia ya aplicaba a segmentos y pasajeros
—reutilizar la fila por su clave natural— y usa el `UNIQUE` sobre `usuario.email` que el
modelo de datos ya tenía. Reservar **no** actualiza el perfil de un usuario existente:
pisarle el nombre con el del último pedido convertiría una reserva en una edición
encubierta, y cualquiera podría renombrar a otro con sólo conocer su email.

**El email es el identificador del usuario en toda la API.** El `userId` de las respuestas
y el del filtro del listado son el email, no el id de la base. Es lo único que el cliente
conoce: manda un email al reservar, así que pedirle después un número que nunca eligió lo
obliga a guardarse una correspondencia que no es asunto suyo. El id interno sigue existiendo
—es la clave foránea— pero no cruza el borde.

Del lado del dominio la reserva referencia al `User` completo, no a un escalar, porque
hacen falta las dos caras: los eventos identifican al destinatario por `UserId` —para que
un cambio de email no obligue a reemitirlos, como documenta `DomainEvent`— y la API lo
expone por email. Guardar una sola mitad obligaría a resolver la otra en cada lectura. El
costo es un join a `usuario` por lectura de reserva, que antes se evitaba a propósito: ese
trade-off se dio vuelta cuando el email pasó a estar en cada respuesta.

Cuando llegue la autenticación, el usuario saldrá del token y el campo `user` del cuerpo
desaparece.

**Idempotencia y concurrencia, en el vocabulario de HTTP.** Los dos mecanismos del
diseño ya existían en los casos de uso; el adaptador los traduce al protocolo en lugar
de inventar los suyos:

| Diseño | HTTP | Dónde vive la traducción |
|---|---|---|
| `idempotencyKey` del comando | Header `Idempotency-Key` en el `POST` | `ReservationController` |
| `expectedVersion` del comando | `ETag` en las respuestas, `If-Match` al escribir | `EntityVersion` |

La clave va en un header y no en el cuerpo porque describe el intento de ejecución, no
el recurso. El alta responde **201** cuando crea y **200** cuando reconoce un reintento;
para poder distinguirlos sin volver a consultar, `CreateReservationUseCase` devuelve
`CreateReservationResult` (la reserva más si fue alta efectiva) en vez de la reserva sola.
Cuando dos pedidos con la misma clave corren a la vez, el perdedor recibe
`DuplicateReservationException` con su transacción ya descartada: el controller reintenta
**una** vez —desde afuera, en una transacción nueva— y devuelve la reserva ganadora.

**Errores: un solo formato.** Todo error sale como `ProblemDetail` (RFC 7807,
`application/problem+json`), incluidos los que genera Spring —método no soportado, JSON
malformado, ruta inexistente—, con un campo `code` estable contra el que los clientes
pueden programar. El `detail` es texto para humanos. Una excepción no prevista se loguea
completa del lado del servidor y afuera sale un 500 sin detalle: ni stack traces ni
mensajes internos.

**Listar sin filtrar Spring Data.** El listado necesitaba paginación, y `Page`,
`Pageable` y `Specification` son tipos del proveedor. Cruzarlos por el puerto ataría los
casos de uso a Spring Data y terminaría serializando su estructura hacia el cliente. En
su lugar la aplicación define `ReservationSearchCriteria` y `ResultPage<T>`, y el
adaptador los traduce. Del lado de JPA, la página se resuelve en dos consultas: primero
los ids (una fila por reserva, `LIMIT` real en la base) y después los agregados de esa
página con `@EntityGraph`. Hacerlo en una sola obligaría a Hibernate a traer todo y
paginar en memoria, que es justo lo que no se quiere en un listado.

**Maestro de aeropuertos: decisión abierta.** Puede ser una tabla propia o un proveedor
externo. Justamente por eso los casos de uso dependen de `AirportCatalogPort`, no de una
implementación. Con `base-url` configurada se consulta la API de catálogo; sin ella queda
un set fijo en memoria. En los dos casos va decorado con `CachingAirportCatalog`: se
consulta una ciudad por cada punta de cada tramo, en cada `POST` y cada `PUT`, y el dato
cambia muy poco.

**Resiliencia del catálogo: timeouts y reintentos.** Es la única dependencia de red del
camino del pedido, así que la cadena está armada por capas y cada una hace una sola cosa
([ADR 0002](docs/adr/0002-timeouts-y-reintentos-del-catalogo.md)):

```
CachingAirportCatalog        ← la mayoría de las consultas mueren acá
  └─ CatalogAirportCatalog
      └─ RetryingCityCatalogClient   ← reintenta sólo lo transitorio, con backoff y jitter
          └─ RestCityCatalogClient   ← clasifica la respuesta HTTP
              └─ RestClient          ← connect 500 ms, read 2 s
```

Los timeouts son de este proveedor y no globales: sin read timeout, una llamada contra un
servicio que acepta la conexión y no contesta cuelga el pedido de reserva hasta que corte
el sistema operativo, y con un pool de 20 conexiones unas pocas de esas convierten la
degradación del catálogo en una caída nuestra. Sólo se reintenta lo transitorio (5xx, 429,
timeout, error de conexión): un 4xx que no es 404 da lo mismo por más que se insista.

Se puede reintentar porque `GET /city/{code}` es una lectura idempotente. Las escrituras
del sistema siguen sin reintentarse: el alta lo es sólo gracias a la `Idempotency-Key` —y
ese reintento lo decide el controller— y el `PUT` depende de una versión que un reintento
ciego pisaría.

**Cache: metadatos y escalares, nunca representaciones.** Tres puntos, salidos de un
análisis de cuellos de botella sobre este código
([ADR 0001](docs/adr/0001-cache-sobre-los-cuellos-de-botella.md)):

| Qué se cachea | Clave | TTL | Invalidación |
|---|---|---|---|
| Existe / no existe la ciudad en el catálogo | `catalog:city:{CODE}` | 30 m · 5 m los negativos | Sólo TTL, con `stale-while-error` |
| El `count` del listado para una combinación de filtros | `rsv:count:{hash}` | 45 s | Sólo TTL |
| La versión de una reserva, para responder `304` | `rsv:ver:{id}` | 60 s | `DEL` post-commit en `PUT` y `DELETE` |

Lo que **no** se cachea es tan importante como lo que sí: los cuerpos de las respuestas
llevan `documentNumber` y `birthDate` de personas físicas, y la API todavía no tiene
autenticación, así que cualquier entrada sería legible por cualquiera que alcance el
endpoint. Por eso las cinco operaciones responden además `Cache-Control: no-store, private`.

Dos detalles que son el corazón del diseño:

- **Una caché caída no puede tumbar el servicio.** Ninguna operación de `CacheStore`
  propaga un error: una lectura que falla es indistinguible de un miss, y todo degrada a
  ir al origen. Por lo mismo `management.health.redis` está apagado: la caché es opcional
  por diseño y no debe sacar la instancia de rotación.
- **Una lectura cacheada no puede provocar un `409` evitable.** Si la versión cacheada
  quedara vieja, un cliente recibiría `304`, se quedaría con su representación anterior y
  su próximo `If-Match` chocaría contra el locking optimista. Lo cierran tres reglas: la
  escritura **borra** la clave en vez de actualizarla, el TTL corto acota una invalidación
  perdida, y el camino de escritura nunca lee de la caché.

**Persistencia.** El esquema lo gobierna Flyway e Hibernate corre con
`ddl-auto: validate`, así que la aplicación no arranca si el mapeo y las tablas se
desincronizan. Las entidades JPA no salen de `adapter.out.persistence`: el dominio nunca
importa `jakarta.persistence` —lo verifica un test de ArchUnit— y los mappers son el
único punto donde los dos modelos se conocen.

Segmentos y pasajeros se comparten entre reservas, así que antes de insertar se reutiliza
la fila existente por su clave natural (`(origen, destino, aerolinea, fecha_vuelo)` y
`documento`). El itinerario, en cambio, se crea siempre: el modelo de datos no le define
clave natural porque la misma combinación de tramos puede venderse a distinto precio.

**Notificaciones asincrónicas.** Las operaciones no llaman al sistema de notificaciones:
registran un evento de dominio que se encola en un outbox (`EventOutboxPort`) dentro de la
misma transacción que la reserva. Un disparador aparte
(`OutboxDispatchScheduler` → `DispatchPendingNotificationsUseCase`) lo envía después. Si el
sistema de notificaciones está caído, las reservas siguen funcionando y los mensajes se
reintentan; agotados los intentos, el mensaje queda en `FAILED` (dead letter).
La entrega es *at-least-once*: el consumidor tiene que ser idempotente.

**Concurrencia.** Son preocupaciones reales desde el día uno, así que el esqueleto ya las
contempla:

| Preocupación | Cómo se resuelve |
|---|---|
| Dos usuarios modificando la misma reserva | Optimistic locking: `If-Match` → `expectedVersion` en los comandos, `@Version` en la entidad → `ConcurrentUpdateException` → 409 |
| Reservas duplicadas por reintentos | `Idempotency-Key` → `idempotency_key` con `UNIQUE`: la búsqueda previa cubre el reintento secuencial y la constraint cierra la carrera |
| Dos altas simultáneas con la misma clave | El perdedor recibe `DuplicateReservationException` y el controller reintenta una vez, en una transacción nueva |
| Dos reservas simultáneas del mismo usuario nuevo | `INSERT ... ON CONFLICT DO NOTHING` sobre `usuario`, igual que con segmentos y pasajeros |
| Paginar un listado con colecciones | Dos consultas (ids paginados + agregados de esa página) y desempate por id, para que la página 2 no repita filas de la página 1 |
| Dos reservas simultáneas del mismo vuelo | `INSERT ... ON CONFLICT DO NOTHING` al resolver segmentos y pasajeros, para no romper la transacción |
| Estado compartido entre hilos | El agregado es inmutable: cada operación devuelve una instancia nueva |
| Latencia y carga sobre el maestro de aeropuertos | Cache con TTL delante del puerto, compartido en Redis |
| Un proveedor externo lento colgando pedidos y agotando el pool | Timeouts propios del catálogo (500 ms / 2 s) |
| Un hipo del catálogo rechazando reservas válidas | Reintentos con backoff exponencial y jitter, sólo para fallos transitorios |
| Estampida contra el origen en cada deploy o scale-out | Cache compartido entre instancias, que sobrevive a los reinicios |
| Refrescos periódicos del mismo recurso | `If-None-Match` → `304`, resuelto contra el cache sin tocar la base |
| Notificaciones lentas compitiendo con los pedidos | Despacho fuera del hilo del usuario, con pool propio |
| Muchos pedidos concurrentes de I/O | Threads virtuales (`spring.threads.virtual.enabled`) |
| Deploys sin cortar pedidos en curso | Graceful shutdown |

**Reloj inyectado.** Los casos de uso no llaman a `Instant.now()`: reciben un `Clock`. Así
las reglas temporales ("no se puede reservar un vuelo que ya partió") son verificables.

## Fuera de alcance

Cada punto tiene su lugar ya preparado:

| Pendiente | Dónde va | Qué hay que hacer |
|---|---|---|
| Seguridad | `adapter/in/rest` + config | No hay autenticación ni autorización: hoy cualquiera puede leer y cancelar cualquier reserva. Va `spring-boot-starter-security` con un esquema Bearer (declarado con `@SecurityScheme`, para que aparezca en el documento generado), los `401`/`403` en las `@ApiResponse` y el filtro de reservas por usuario autenticado en el listado |
| Outbox persistente | `infrastructure/adapter/out/outbox` | El outbox sigue en memoria porque el modelo de datos no tiene su tabla. Con una tabla `outbox_message` escrita en la misma transacción que la reserva, la notificación deja de perderse si se cae el proceso; el `pollPending` pasa a `SELECT ... FOR UPDATE SKIP LOCKED` |
| Recurso de usuarios | `application` + `adapter/in/rest` | El alta de usuarios ocurre como efecto de reservar, que alcanza para que la API sea usable pero no es un ciclo de vida: no hay forma de consultar, corregir ni dar de baja a un usuario. Cuando haga falta, va como recurso propio (`/v1/users`) con sus casos de uso |
| Cambio de email | `application` | Hoy el email identifica al usuario en la API, así que cambiarlo es cambiar de identificador de cara al cliente. Con un recurso de usuarios habrá que decidir si el `userId` de la API pasa a ser un identificador propio y estable, y el email queda como un atributo más |
| Confirmar una reserva | `adapter/in/rest` | `ConfirmReservationUseCase` existe y está testeado, pero no está expuesto: no entra limpio en el contrato sin un verbo en la URL o un `PATCH` de estado, y la transición va a colgar del resultado del pago. Hay que decidir la forma antes de publicarla |
| Contrato: el 500 | `ReservationController` | Las `@ApiResponse` declaran 200/201/400/404/409, que son las respuestas del diseño. La aplicación puede responder 500 ante un error no previsto (cuerpo `ProblemDetail`, código `INTERNAL_ERROR`) y eso todavía no está declarado |
| Índices del listado | `db/migration` | Faltan `reserva(fecha_creacion DESC, id DESC)` —el orden por defecto— y `segmento(fecha_vuelo)`. El cache del `count` tapa parte del costo, pero con `OFFSET` creciente el listado se degrada igual: cada página es una consulta distinta |
| Presupuesto de tiempo del pedido | `AirportExistenceValidator` | Cada consulta al catálogo tiene su techo (timeout × reintentos), pero el itinerario completo no: son hasta 8 ciudades en serie. Las candidatas son resolverlas en paralelo —son independientes— y un circuit breaker que deje de intentar mientras el proveedor esté caído |
| Swagger UI abierta | `application.yml` | La UI está expuesta sin autenticación y permite ejecutar pedidos contra la API. Junto con la seguridad hay que decidir si se publica y para quién (`springdoc.swagger-ui.enabled`) |

## Tests

401 tests. Los unitarios (`mvn test`) no necesitan infraestructura; los de integración
(`mvn verify`) levantan PostgreSQL con Testcontainers. Cuatro quedan apagados por
defecto: son los que pegan contra el catálogo real (`-Dcatalog.live=true`).

**Unitarios (352)**

- **Dominio** — reglas del agregado y de los value objects, con tiempo fijo.
- **Aplicación** — casos de uso con los puertos mockeados: qué se persiste, qué se
  notifica y, sobre todo, qué **no** se hace cuando una validación falla.
- **API** — `ReservationControllerTest` es el slice de la capa web con los puertos
  mockeados: códigos de estado, `Location`, `ETag`, el comando que recibe cada caso de
  uso, y el cuerpo de error de cada una de las traducciones (400/404/409), incluidos los
  errores del propio framework.
- **Contrato** — `OpenApiContractTest` verifica que el documento **generado** describa de
  verdad esta API: operaciones contra rutas registradas en los dos sentidos, códigos de
  estado contra los que el adaptador realmente devuelve, el esquema de error contra el
  cuerpo de un error real, y los parámetros de consulta del listado uno por uno.
- **Mappers** — la traducción entre dominio y JPA, y entre DTOs y comandos, en los dos
  sentidos.
- **Adaptadores** — outbox, notificaciones, y la cadena del catálogo: qué se reintenta y
  qué no, que la espera crezca y lleve jitter, y que un 401 no se reintente nunca.
- **Cache** — cada decorador contra un almacén en memoria con reloj controlado: que el hit
  no vaya al origen, que el TTL expire, que la invalidación borre la clave, que una
  escritura no deje servir datos viejos, que lo guardado sea un escalar y no una
  representación, y que con el almacén caído todo siga funcionando contra el origen.
- **Arquitectura** — ArchUnit sobre las reglas de dependencia entre capas, incluida la de
  que el dominio no importe `jakarta.persistence` y la de que los adaptadores de entrada
  hablen con los puertos y no con los servicios.

**Integración (49)**

- `ReservationPersistenceAdapterIT` — el adaptador contra PostgreSQL: reutilización de
  segmentos y pasajeros, orden de los tramos, `UNIQUE` de idempotencia, clave foránea de
  usuario, optimistic locking, fechas en UTC, y dos tests concurrentes (cuatro hilos
  modificando la misma reserva; cuatro reservando el mismo vuelo a la vez).
- `ReservationsApplicationIT` — levanta el contexto completo, valida que el mapeo coincida
  con el esquema de Flyway y corre el flujo crear → consultar → confirmar → modificar →
  cancelar, más la idempotencia, el alta y la reutilización del usuario, y el despacho del
  outbox.
- `CacheIT` — el cache enchufado y **sin Redis**: que la aplicación arranque con el
  fallback en memoria, que las métricas salgan por Actuator etiquetadas por cache, que
  una lectura condicional responda `304` sin cuerpo, que después de un `PUT` el `ETag`
  viejo deje de dar `304` —y el `If-Match` siguiente no coma un `409` evitable—, y que en
  el cache no quede un solo dato de pasajero.
- `ReservationApiIT` — el mismo flujo pero **por HTTP** contra la base real: el `ETag` de
  una respuesta usado en el `If-Match` de la siguiente, el reintento que devuelve 200 sin
  duplicar filas, el `ETag` viejo que da 409 sin escribir, el listado con sus filtros, su
  orden y su paginación resueltos en SQL, el alta del usuario como parte de la reserva —con
  la base arrancando vacía—, el filtro del listado por email, y el documento OpenAPI
  generado por el contexto completo, con Swagger UI respondiendo.
