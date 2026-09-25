# Sistema de reservas de vuelos

Backend del sistema de reservas de vuelos. **Java 21 + Spring Boot 3.5 + Maven**, con
arquitectura hexagonal (puertos y adaptadores).

El dominio, los casos de uso, la persistencia sobre PostgreSQL, la API REST y la capa
de seguridad están implementados. El contrato OpenAPI se genera a partir del código con
springdoc y se publica en `/v3/api-docs`, con **Swagger UI** en `/swagger-ui.html`
(apagada por defecto).

La API exige un **token Bearer** y una reserva sólo la ve su titular: ver
[Seguridad](#seguridad) y el [ADR 0003](docs/adr/0003-autenticacion-autorizacion-y-datos-sensibles.md).

Con quién habla el sistema y de qué contenedores está hecho, en dos diagramas C4:
[`docs/architecture/c4.md`](docs/architecture/c4.md).

## Cómo ejecutarlo

Requiere JDK 21 (hay un `.sdkmanrc`: `sdk env install && sdk env`) y Docker.

```bash
cp .env.example .env
```

```bash
docker compose up -d
```

```bash
./mvnw spring-boot:run
```

`.env` lleva las credenciales locales y no se versiona; `.env.example` sí, con los
nombres de las variables y valores de ejemplo. Sin `.env` todo funciona igual: el
`compose.yaml` y el `application.yml` traen defaults de desarrollo.

**Lo leen los dos lados, y eso hay que decirlo porque no es lo habitual.**
`docker compose` toma el `.env` solo, para sustituir los `${VAR:-default}` del
`compose.yaml`. La aplicación lo toma porque el `application.yml` lo importa:

```yaml
spring:
  config:
    import: optional:file:./.env[.properties]
```

Sin esa línea, `./mvnw spring-boot:run` arrancaría **sin** las variables del archivo
—la contraseña de Redis y la del broker quedarían vacías— y habría que exportarlas a
mano antes de levantar. Es un paso silencioso de olvidar, y el síntoma (un componente
que no conecta) no se parece a la causa.

Tres consecuencias que conviene tener presentes:

- **`optional:`** es lo que hace que la ausencia del archivo no sea un error. En
  cualquier entorno real no hay `.env`: las variables las pone el gestor de secretos.
- **Las variables de entorno de verdad ganan sobre el archivo**, que es el orden
  correcto. Es también lo que permite un `MESSAGING_ENABLED=false ./mvnw spring-boot:run`
  puntual sin editar el `.env`.
- **El `.env` también entra en los tests**, porque comparten el directorio. No los
  afecta —cada test fija explícitamente lo que le importa con `@SpringBootTest(properties=…)`
  y `@DynamicPropertySource`, que tienen más precedencia—, pero es la razón por la que
  esas propiedades están fijadas y no heredadas.

`docker compose up -d` levanta PostgreSQL, **Redis** (el cache distribuido),
**RabbitMQ** (el broker de la mensajería) y el catálogo de ciudades. Los cuatro son
opcionales en distinta medida:

| Servicio | Sin él | Cómo se apaga |
|---|---|---|
| **PostgreSQL** | No arranca. Es la fuente de verdad y también donde vive el outbox | — |
| **RabbitMQ** | Arranca y responde igual. Los eventos se acumulan en `outbox_message` y el relay los publica cuando el broker vuelve; un broker caído **no** puede tumbar el servicio | `MESSAGING_ENABLED=false` deja el publicador que sólo loguea y **no levanta el consumidor**, así que tampoco queda nadie reintentando la conexión |
| **Redis** | Arranca igual; el cache cae al de memoria del proceso | `CACHE_REDIS_ENABLED=false` |
| **Catálogo de ciudades** | Arranca igual; se usa el maestro de aeropuertos en memoria | `reservations.airport-catalog.base-url=` |

La consola de RabbitMQ queda en **http://localhost:15672** (usuario y contraseña
del `.env`, `reservations` / `reservations-local` por defecto). Los puertos del
broker se publican **sólo en loopback**, por el mismo motivo que los de Redis: un
broker alcanzable desde la red es un broker donde cualquiera publica un
`reservation.cancelled` —o se suscribe a la cola de notificaciones y lee la ruta
y la fecha de viaje de cada pasajero—.

La mensajería está documentada en [`docs/messaging/topology.md`](docs/messaging/topology.md),
con sus decisiones en el [ADR 0004](docs/adr/0004-mensajeria-asincronica-y-broker.md) y
el [ADR 0005](docs/adr/0005-garantias-de-entrega-y-remediacion-de-la-mensajeria.md).
Cómo operarla está más abajo, en [Mensajería](#mensajería).

La aplicación queda escuchando en **http://localhost:8080** y Flyway crea el esquema en
el arranque. Actuator va aparte, en el **9090**, que es un puerto que el despliegue no
publica hacia afuera:

```bash
curl http://localhost:9090/actuator/health
```

Para explorar la API desde el navegador hay que encender el documento y la UI
(`API_DOCS_ENABLED=true` y `SWAGGER_UI_ENABLED=true` en el `.env`, que ya vienen así en
el `.env.example`). Los dos están apagados por defecto porque en producción no tienen
que existir; encendidos, **http://localhost:8080/swagger-ui/index.html** abre sin
credencial —si pidiera uno no habría forma de llegar a la pantalla donde cargarlo—.

Lo que la UI *ejecuta* sí la exige: el botón *Try it out* pega contra `/v1/**` como
cualquier otro cliente, así que hay que pegar un token en **Authorize** o la respuesta
es 401. El *Try it out* apunta a `localhost:8080`, el host desde el que estás mirando la
UI, no a un entorno fijo.

El contrato también está versionado en
[`docs/api/openapi.yaml`](docs/api/openapi.yaml) y se regenera con un comando:

```bash
./mvnw test -Dtest=OpenApiDocumentDumpTest -Dopenapi.dump=true
```

## La API

Cinco operaciones sobre `/v1/reservations`, **todas con token**. No hace falta preparar
nada en la base: quien reserva es el dueño del token y se da de alta solo la primera
vez que reserva.

| Método | Ruta | Qué hace |
|---|---|---|
| `POST` | `/v1/reservations` | Crea una reserva. Requiere `Idempotency-Key` |
| `GET` | `/v1/reservations` | Lista con filtros y paginación |
| `GET` | `/v1/reservations/{id}` | Devuelve una reserva y su `ETag` |
| `PUT` | `/v1/reservations/{id}` | Cambia el itinerario. Requiere `If-Match` |
| `DELETE` | `/v1/reservations/{id}` | Cancela (baja lógica). Requiere `If-Match` |

Todas responden **401** sin `Authorization: Bearer`, **429** si se supera la cuota, y
**404** cuando la reserva es de otro usuario —igual que cuando no existe—.

Crear una reserva. El comprador no va en el cuerpo: sale del token.

```bash
curl -i -X POST http://localhost:8080/v1/reservations -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d '{"itinerary":{"price":"1250.50","currency":"USD","segments":[{"originAirportCode":"BUE","destinationAirportCode":"SCL","airline":"AEROLINEAS ARGENTINAS","departureAt":"2027-03-15T22:40:00Z"}]},"passengers":[{"firstName":"Ana","lastName":"Pérez","birthDate":"1990-05-20","documentNumber":"30123456"}]}'
```

La respuesta trae `Location` y `ETag: "0"`. Ese `ETag` es lo que hay que mandar en
`If-Match` para modificar o cancelar:

```bash
curl -i -X DELETE http://localhost:8080/v1/reservations/1 -H "Authorization: Bearer $TOKEN" -H 'If-Match: "0"'
```

Listar. El alcance no se elige: son las reservas del dueño del token. El parámetro
`userId` sólo lo puede usar un cliente con rol de backoffice; para cualquier otro,
mandar el email de otra persona responde **403**.

```bash
curl -G http://localhost:8080/v1/reservations -H "Authorization: Bearer $TOKEN" -d 'status=PENDING' -d 'sort=firstDepartureAt,asc' -d 'page=0' -d 'size=20'
```

Los tests, separados por lo que necesitan:

```bash
./mvnw test
```

```bash
./mvnw verify
```

`test` corre los 484 unitarios: rápidos y sin Docker. `verify` agrega los 119 de
integración, que levantan un PostgreSQL y un RabbitMQ con Testcontainers. Ninguno de los
dos necesita Redis, un broker en `compose.yaml` ni un proveedor de identidad: los de
integración corren con el cache en memoria, con la mensajería apagada salvo los que la
prueban, y con tokens HMAC firmados con la clave de desarrollo. Eso es también la forma
de verificar en cada build que la aplicación arranca sin esas dependencias.

## Estructura

La vista de afuera —el contexto y los contenedores— está en
[`docs/architecture/c4.md`](docs/architecture/c4.md). Lo que sigue es la de adentro:
cómo está organizado el único proceso.

```
com.edteam.reservations
├── domain                      # El centro. Sin Spring, sin JPA, sin HTTP.
│   ├── model                   #   Reservation (agregado), Itinerary, Segment,
│   │                           #   Passenger, User, value objects
│   ├── access                  #   Actor y ReservationAccessPolicy: quién es
│   │                           #   dueño de qué. Sin Spring Security
│   ├── event                   #   Eventos de dominio (interfaz sellada)
│   └── exception               #   Errores de negocio
├── application                 # Orquestación. Depende sólo del dominio.
│   ├── port/in                 #   Casos de uso + comandos (lo que entra,
│   │                           #   incluido QUIÉN lo pide)
│   ├── port/out                #   Contratos hacia afuera (lo que necesita)
│   ├── service                 #   Implementación de los casos de uso
│   ├── query                   #   Criterio de búsqueda y página de resultados
│   ├── audit                   #   Modelo del registro de auditoría
│   ├── outbox                  #   Modelo del outbox de eventos
│   ├── notification            #   Modelo de la notificación emitida
│   └── exception               #   Errores de orquestación
└── infrastructure              # Detalles reemplazables.
    ├── adapter/in/rest         #   Controllers, DTOs, mappers y manejo de errores
    ├── adapter/in/scheduling   #   Disparadores: relay del outbox y purga
    ├── adapter/in/messaging    #   Consumidor AMQP + parser del envelope
    ├── adapter/in/ops          #   Endpoints de gestión de las dos dead letters
    ├── adapter/out/persistence #   PostgreSQL: entidades JPA, mappers, adapter
    ├── adapter/out/audit       #   Registro de auditoría append-only
    ├── adapter/out/airport     #   Maestro de aeropuertos (stub) + cache
    ├── adapter/out/messaging   #   Publicador AMQP, envelope, payload y DLQ
    ├── adapter/out/outbox      #   Outbox durable en PostgreSQL + métricas
    ├── adapter/out/inbox       #   Deduplicación y registro de entregas
    ├── cache                   #   Almacén del cache: Redis, memoria y métricas
    ├── logging                 #   Enmascarado de PII y saneado de datos externos
    ├── security                #   Cadena de filtros, token → Actor, cuota,
    │   └── crypto              #   correlation id, y el cifrado de la PII
    └── config                  #   Cableado y properties
```

Dos paquetes nuevos y la línea que los separa: `domain.access` decide **quién puede ver
o tocar una reserva** —es una regla de negocio— y `infrastructure.security` decide
**quién llega a un endpoint** —es un detalle del transporte—. El dominio y la
aplicación no importan una sola clase de Spring Security, y eso está verificado por
ArchUnit, no por disciplina.

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
llevan `documentNumber` y `birthDate` de personas físicas, y un cache compartido no
distingue de quién es cada representación. Por eso las cinco operaciones responden
además `Cache-Control: no-store, private` —y con autenticación hace todavía más falta:
una respuesta autenticada guardada por un proxy compartido se le puede servir al pedido
siguiente, que trae otro token—.

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
registran un evento de dominio que se encola en la tabla `outbox_message`
(`EventOutboxPort`) **dentro de la misma transacción que la reserva**. Un relay aparte
(`OutboxDispatchScheduler` → `DispatchPendingNotificationsUseCase` →
`EventPublisherPort`) lo publica después a un *topic exchange* de RabbitMQ. Si el broker
o el consumidor están caídos, las reservas siguen funcionando: los mensajes se acumulan
en la tabla y se reintentan con backoff.

La transacción compartida vale en los dos sentidos, y el segundo es el que más cuesta
ver: no sólo evita perder la notificación de algo que se guardó, también evita **emitir
una notificación de algo que no ocurrió** —dos confirmaciones concurrentes en las que
una pierde el conflicto optimista dejaban dos eventos—.

La entrega es *at-least-once*: el consumidor deduplica por `messageId`, que es la PK de
la fila del outbox y no cambia entre reintentos ni entre replays. Todo lo demás
—reintentos clasificados, las dos dead letters, el orden— está en
[Mensajería](#mensajería).

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
| Varias instancias despachando el mismo mensaje | `SELECT ... FOR UPDATE SKIP LOCKED` con cambio de estado en la misma sentencia: cada instancia se lleva un subconjunto disjunto, sin lock distribuido |
| Un proceso que muere con el mensaje reclamado | El reclamo tiene *lease* (`claim-lease: 2m`): al vencer, el mensaje vuelve a ser elegible |
| El mismo mensaje entregado dos veces al consumidor | `INSERT ... ON CONFLICT DO NOTHING` sobre `processed_message`: la decisión la toma la base y no un `SELECT` previo, que tendría una carrera |
| Un destino caído quemando los reintentos en segundos | `next_attempt_at` con backoff exponencial y jitter completo, más un techo de tiempo (`retry-ceiling: 6h`) además del tope de intentos |
| Un mensaje venenoso frenando la cola | Se clasifica: lo permanente va a la dead letter en el primer intento y no bloquea a los sanos de atrás |
| Una cancelación notificada antes de su alta | El relay bloquea por reserva: si un mensaje de una reserva falla, los siguientes de *esa* reserva esperan al próximo lote, sin gastar un intento |
| Un catálogo lento agotando el pool de conexiones | La validación ocurre **fuera** de la transacción, verificado por ArchUnit |
| Muchos pedidos concurrentes de I/O | Threads virtuales (`spring.threads.virtual.enabled`) |
| Deploys sin cortar pedidos en curso | Graceful shutdown |

**Reloj inyectado.** Los casos de uso no llaman a `Instant.now()`: reciben un `Clock`. Así
las reglas temporales ("no se puede reservar un vuelo que ya partió") son verificables.

## Mensajería

Los cuatro hechos de negocio —`reservation.created`, `.confirmed`, `.modified`,
`.cancelled`— salen a un *topic exchange* de RabbitMQ. El diseño completo está en
[`docs/messaging/topology.md`](docs/messaging/topology.md); acá está lo que hace falta
para operarlo.

### El camino de un hecho

```
caso de uso ──┐
              │  misma transacción
   reserva ◄──┴──► outbox_message (PENDING)
                          │
                          │  relay cada 5s, FOR UPDATE SKIP LOCKED
                          ▼
              exchange reservations.events          ── DISPATCHED sólo con el ack
                          │  routing key = tipo        del broker (publisher confirm)
                          ▼
       notifications.reservation-events (quorum)
                          │
          ┌───────────────┼────────────────┐
          ▼               ▼                ▼
        efecto     ...retry (TTL 30s)   ...dlq
      (notificación)      │            (dead letter
                          └──► requeue   del consumidor)
```

Dos cosas que no se ven en el dibujo y son las que sostienen todo: el `INSERT` del
outbox va en la transacción del caso de uso —si la reserva no se guarda, el evento
tampoco, y al revés—, y el `messageId` es la PK de esa fila, así que es la misma en cada
reintento y en cada replay. Es con eso que el consumidor deduplica.

### Las dos dead letters

No son lo mismo y por eso hay dos, con dos herramientas y dos métricas:

| | Qué significa | Dónde está | Cómo se mira | Cómo se reprocesa |
|---|---|---|---|---|
| **Productor** | No pudimos **publicar** (broker caído, cola llena, payload roto) | `outbox_message` con `status = 'FAILED'` | `GET :9090/actuator/outbox` | `POST :9090/actuator/outbox` |
| **Consumidor** | No pudieron **procesar** (esquema inválido, tipo desconocido, hecho vencido, fallo repetido del canal) | Cola `notifications.reservation-events.dlq` | `GET :9090/actuator/messaging-dlq` | `POST :9090/actuator/messaging-dlq` |

La del productor vive en la base y no en el broker a propósito: si lo que está caído es
el broker, una dead letter *dentro* del broker es inalcanzable justo cuando hace falta.

Los endpoints de gestión **también exigen token** —el mismo `$TOKEN` de
[La API](#la-api)—. Es cinturón y tirantes sobre el puerto que el despliegue no
publica: el día que alguien lo exponga por error, un endpoint que reencola mensajes no
queda abierto. Las sondas de `health` son la excepción.

**Inspeccionar y drenar la dead letter del productor:**

```bash
curl -s http://localhost:9090/actuator/outbox -H "Authorization: Bearer $TOKEN" | jq
```

Devuelve `pending`, `lagSeconds`, `dead` y el listado de los mensajes muertos con su
tipo, su reserva, sus intentos y su último error. **No devuelve el payload**: lleva ruta
y fecha de viaje atadas a un usuario, y esto se mira desde una consola. Para el
contenido hay que consultar la tabla.

Reencolar uno, y despacharlo en el acto sin esperar el próximo tick del relay:

```bash
curl -s -X POST http://localhost:9090/actuator/outbox -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"messageId":"0f7a6f2e-6b77-4a3a-9a5f-3c4a6b2f10d1","dispatch":true}' | jq
```

Reencolar todo lo muerto, después de arreglar la causa:

```bash
curl -s -X POST http://localhost:9090/actuator/outbox -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"dispatch":true}' | jq
```

**Inspeccionar y drenar la DLQ del consumidor:**

```bash
curl -s http://localhost:9090/actuator/messaging-dlq -H "Authorization: Bearer $TOKEN" | jq
```

El `peek` **no consume**: saca los mensajes y los devuelve a la cola en la misma
operación, así que mirar la dead letter no puede vaciarla. Un `depth` de `-1` significa
«no se sabe» —no hay broker— y no «está vacía»: la diferencia importa en un tablero con
una alerta en `> 0`.

```bash
curl -s -X POST http://localhost:9090/actuator/messaging-dlq -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"max":100}' | jq
```

El replay **mueve** los mensajes a la cola principal, no los copia, y conserva el
`messageId`: lo que ya se hubiera aplicado se deduplica del otro lado y no produce un
segundo aviso al usuario. Es lo que hace que reprocesar sea seguro en lugar de ser una
apuesta.

Para ver el cuerpo de un mensaje muerto, la consola del broker
(**http://localhost:15672** → *Queues* → `notifications.reservation-events.dlq` →
*Get messages*). El contenido de la DLQ se trata como dato productivo.

### Qué mirar en un tablero

| Métrica | Qué dice | Cuándo alerta |
|---|---|---|
| `reservations.outbox.lag` (segundos) | **El número que importa:** cuánto tarda una notificación desde que el hecho ocurrió | Sostenido por encima de un minuto |
| `reservations.outbox.pending` | Cuántos esperan | Crece sostenido: el broker no acepta o el relay no corre |
| `reservations.outbox.dead` | Dead letter del **productor** | **> 0** |
| `reservations.messaging.dlq.depth` | Dead letter del **consumidor** | **> 0** |
| `reservations.outbox.failed{failure}` | Ritmo y **naturaleza** del fallo: un pico de `permanent` es un problema del payload, uno de `transient` es del broker | Se resuelven en lugares distintos |
| `reservations.messaging.out-of-order` | Hechos que llegaron desordenados y se aplicaron igual | Informativa: sube con cada reintento |
| `reservations.outbox.dispatched.retained` | Despachados sin purgar | Crece sin techo: la purga diaria no corre |

Ninguna de éstas existía antes, y eso es lo que volvía **invisible** al problema: el
sistema se veía igual de sano con la cola vacía y con la cola trabada.

### Qué no viaja en un mensaje

Ni email, ni nombre, ni documento del pasajero, ni datos de pago. El destinatario se
identifica por `userId` interno y el sistema de notificaciones resuelve el contacto, que
es dato suyo: un cambio de email no obliga a reemitir nada. `DomainEventPayloadMapperTest`
lo verifica con una *allowlist* de campos, no con una lista de prohibidos, así que un
campo nuevo rompe el test y obliga a decidir si es dato personal.

Lo que **sí** viaja es ruta y fecha de viaje, porque sin eso el consumidor no puede
redactar el mensaje. Atado a un `userId` eso es dato personal, así que el broker es un
sistema de tratamiento y no un caño: TLS en tránsito, credencial propia por servicio,
cuerpos de mensaje fuera de los logs (INFO registra `type`, `subject` y `messageId`; el
payload va a DEBUG) y retención acotada.

### Probarlo a mano

Con todo levantado, crear una reserva y ver el hecho recorrer el circuito:

```bash
curl -s http://localhost:9090/actuator/metrics/reservations.outbox.lag -H "Authorization: Bearer $TOKEN" | jq
```

**Doble procesamiento** (que el mismo mensaje dos veces no duplique efectos). Se crea
una reserva, se espera el despacho, y se devuelve la fila del outbox a pendiente para
que el relay la publique de nuevo — que es exactamente lo que pasa cuando el ack del
broker se pierde:

```bash
docker exec -it reservations-postgres psql -U reservations -d reservations -c "UPDATE outbox_message SET status = 'PENDING', next_attempt_at = now() WHERE type = 'reservation.created';"
```

```bash
docker exec -it reservations-postgres psql -U reservations -d reservations -c "SELECT reserva_id, count(*) FROM notificacion_entrega GROUP BY reserva_id;"
```

El conteo tiene que seguir en **1** por reserva: el `messageId` no cambió con el reenvío
y el consumidor lo reconoció.

**Caída del consumidor** (que no afecte a la API). Se para el consumidor dejando el
broker en pie:

```bash
MESSAGING_CONSUMER_ENABLED=false ./mvnw spring-boot:run
```

Las altas siguen respondiendo con la misma latencia, el relay sigue marcando
`DISPATCHED` —su trabajo termina con el ack del broker, no con el procesamiento del
consumidor— y los mensajes se acumulan en la cola:

```bash
curl -s -u reservations:reservations-local http://localhost:15672/api/queues/%2F/notifications.reservation-events | jq '.messages'
```

Al volver a levantar con el consumidor encendido, la cola se drena sola.

**Caída del broker** (que no tumbe el servicio):

```bash
docker compose stop rabbitmq
```

La API responde igual, los eventos se acumulan en `outbox_message` y el `lag` crece.
Con `docker compose start rabbitmq` el relay los publica en el siguiente tick.

## Seguridad

Salió de una [auditoría STRIDE](docs/security/threat-model.md) sobre este código y está
documentada en el [ADR 0003](docs/adr/0003-autenticacion-autorizacion-y-datos-sensibles.md).
Lo que hay que saber para trabajar en el repositorio:

**Autenticación.** Resource server OAuth2: la aplicación verifica firmas, no emite
credenciales. En cualquier entorno real se configura `JWT_JWK_SET_URI` (obligatoriamente
HTTPS) más emisor y audiencia; en local se aceptan tokens HMAC firmados con una clave
placeholder, y la aplicación lo avisa con un banner en cada arranque. **No hay ninguna
combinación de propiedades que produzca una API sin validar tokens**: si falta la
configuración, el contexto no levanta.

**Autorización.** La línea importante del diseño:

| Dónde | Qué decide |
|---|---|
| `infrastructure.security.SecurityConfiguration` | Quién llega a un endpoint. `denyAll()` por defecto: un endpoint nuevo nace cerrado |
| `domain.access.ReservationAccessPolicy` | Quién ve o toca **una reserva concreta**. Es una regla de negocio y se prueba sin levantar un contexto |

Los comandos llevan el solicitante (`CreateReservationCommand(actor, …)`), así que la
decisión es del caso de uso y no del controller: el día que entre un consumidor de
mensajería o un cliente gRPC, la autorización ya está. Un `@PreAuthorize` en un servicio
de aplicación rompe el build (`HexagonalArchitectureTest`).

**Una reserva ajena responde 404, no 403.** Distinguirlas convertiría el par de códigos
en un censo: recorriendo los ids se sabría cuántas reservas hay y cuáles están ocupadas.
El 403 queda para el único caso en que el rechazo no revela nada: pedir el listado de
otro usuario.

**Datos sensibles.** El documento del pasajero se cifra con AES-256-GCM en la columna
—un `pg_dump` deja de ser un dump de PII—, el email nunca sale en claro en un log, y la
respuesta del alta refleja lo que el cliente envió en vez de lo almacenado. Esto último
costó la deduplicación global de pasajeros por documento: era una optimización de
almacenamiento que convertía el alta en un oráculo de datos ajenos.

**Trazabilidad.** Tabla `auditoria`, append-only garantizado por un trigger de
PostgreSQL. Se registran las escrituras y los intentos de acceder a una reserva ajena
—esos últimos en su propia transacción, porque el rechazo termina en excepción y con
propagación normal la evidencia se iría con el rollback—. Las lecturas exitosas no se
auditan: sería una fila por `GET`.

**Superficie.** El documento OpenAPI y Swagger UI están apagados por defecto: en
producción no existen. Encendidos se sirven sin token —exigirlo dejaría la UI inusable
sin proteger nada, porque una navegación del navegador no lleva header
`Authorization`—; lo que la UI *ejecuta* sí lo exige. Actuator va en un puerto de
gestión propio que el despliegue no publica.

**Secretos.** Ninguno en el repositorio. Los valores de `application.yml` y de
`.env.example` son de desarrollo, están marcados como tales y la aplicación avisa cuando
los está usando. `.env` está en `.gitignore`.

## Fuera de alcance

Cada punto tiene su lugar ya preparado:

| Pendiente | Dónde va | Qué hay que hacer |
|---|---|---|
| Precio del lado del servidor | `application/port/out` | **La amenaza crítica que queda abierta.** El cliente sigue fijando el precio de su reserva en el cuerpo del alta: un `POST` con `"price":"0.01"` crea una reserva válida por un centavo. Cerrarla necesita un `PricingPort` contra un proveedor que todavía no existe —el precio se cotiza del lado del servidor y el cliente manda la referencia de la cotización, con vencimiento corto—. Es el próximo paso, y bloquea la integración de pagos |
| Esquema JSON del mensaje | `docs/messaging/schemas/` | El contrato del payload está fijado por `DomainEventPayloadMapperTest`, que rompe el build si un campo desaparece. Falta publicarlo como `<type>.v<n>.schema.json` para que el consumidor externo valide del otro lado sin leer nuestro código |
| Orden entre reservas distintas | `application/service` | El relay cuida el orden **dentro** de una reserva; entre reservas distintas no hay garantía y no hace falta. Si algún día la hubiera, es partición por `subject` y no un candado global |
| Recurso de usuarios | `application` + `adapter/in/rest` | El alta de usuarios ocurre como efecto de reservar, que alcanza para que la API sea usable pero no es un ciclo de vida: no hay forma de consultar, corregir ni dar de baja a un usuario. Cuando haga falta, va como recurso propio (`/v1/users`) con sus casos de uso |
| Cambio de email | `application` | Hoy el email identifica al usuario en la API, así que cambiarlo es cambiar de identificador de cara al cliente. Con un recurso de usuarios habrá que decidir si el `userId` de la API pasa a ser un identificador propio y estable, y el email queda como un atributo más |
| Confirmar una reserva | `adapter/in/rest` | `ConfirmReservationUseCase` existe y está testeado, pero no está expuesto: no entra limpio en el contrato sin un verbo en la URL o un `PATCH` de estado, y la transición va a colgar del resultado del pago. Hay que decidir la forma antes de publicarla |
| Contrato: el 500 | `ReservationController` | Las `@ApiResponse` declaran 200/201/304/400/401/403/404/409/429, que son las respuestas del diseño. La aplicación puede responder 500 ante un error no previsto (cuerpo `ProblemDetail`, código `INTERNAL_ERROR`) y eso todavía no está declarado |
| Índices del listado | `db/migration` | Faltan `reserva(fecha_creacion DESC, id DESC)` —el orden por defecto— y `segmento(fecha_vuelo)`. El cache del `count` tapa parte del costo, pero con `OFFSET` creciente el listado se degrada igual: cada página es una consulta distinta |
| Presupuesto de tiempo del pedido | `BudgetedCityCatalogFanout` | **Cerrado.** El itinerario tiene un techo propio de 1,6 s y las ciudades se resuelven en paralelo sobre hilos virtuales, así que once ciudades cuestan la más lenta y no la suma. Lo que queda abierto es el último tramo: el `POST` cierra en 4,1 s contra un objetivo de 4 s y el `PUT` en 7,1 s contra 4,5 s (ver `docs/resilience/implementation.md` §5) |
| Circuit breaker del catálogo | `CircuitBreakingCityCatalogClient` | **Cerrado**, junto con el bulkhead de 50 llamadas en vuelo que es la cuota propia de llamadas salientes que faltaba. Un código IATA inexistente ya no amplifica: el `404` no abre el circuito pero tampoco se reintenta, y el bulkhead acota lo que un atacante puede convertir en carga contra el proveedor |
| Retención y supresión de PII | `db/migration` + `application` | La cancelación es baja lógica: documento y fecha de nacimiento quedan indefinidamente. Falta la purga o anonimización vencido el plazo legal, y el caso de uso de supresión, que entra junto con el recurso de usuarios |
| Cadena de suministro | `pom.xml` + CI | No hay análisis de dependencias ni SBOM. Va `dependency-check` (o equivalente) con un umbral que rompa el pipeline, SBOM por release y escaneo de secretos, que son tres cosas del pipeline y no de este código |
| TLS y rate limiting del borde | Despliegue | La aplicación emite HSTS y trae una cuota por proceso, pero el TLS lo termina el ingress y el rate limiting real va en el gateway: con N instancias, la cuota efectiva de acá es N veces la configurada |
| Alcance PCI-DSS | Cuando entren pagos | Todavía no hay datos de pago, y por eso es el momento de decidir: el PAN no puede tocar este servicio. Checkout hospedado o campos embebidos del proveedor, y acá sólo el token y los últimos cuatro dígitos |

## Tests

603 tests. Los unitarios (`mvn test`) no necesitan infraestructura; los de integración
(`mvn verify`) levantan PostgreSQL y RabbitMQ con Testcontainers. Cuatro quedan apagados
por defecto: son los que pegan contra el catálogo real (`-Dcatalog.live=true`), y uno más
—el que regenera `docs/api/openapi.yaml`— corre sólo con `-Dopenapi.dump=true`, porque
un test que escribe en el repositorio no puede correr en cada build.

**Unitarios (484)**

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
- **Adaptadores** — la cadena del catálogo (qué se reintenta y qué no, que la espera
  crezca y lleve jitter, que un 401 no se reintente nunca) y el borde de la mensajería:
  `InboundEnvelopeParserTest` verifica que el lector sea **tolerante** —ignora campos que
  no conoce, no falla por un opcional ausente— y que los cinco campos sin los que no se
  puede hacer nada sean un fallo permanente y no algo que reintentar.
- **Mensajería** — `OutboxDispatcherServiceTest` fija las tres reglas del relay: que el
  `messageId` y el `sequence` crucen el puerto (sin eso el consumidor no puede deduplicar
  nada), que un fallo transitorio se distinga de uno permanente, y que un fallo de una
  reserva postergue los siguientes **de esa reserva** sin gastarles un intento.
  `ProcessReservationEventServiceTest` prueba la propiedad central con dobles en memoria
  y contando efectos —no verificando invocaciones—: el mismo mensaje cinco veces deja un
  solo efecto, dos mensajes distintos dejan dos, un evento desordenado **se aplica** en
  lugar de descartarse, y el dedupe corre antes de la validación, así que un duplicado no
  puede terminar en la DLQ después de haberse aplicado.
- **Contrato del payload** — `DomainEventPayloadMapperTest` es el equivalente de
  `OpenApiContractTest` para los mensajes: los campos de los cuatro tipos, el importe como
  string (un consumidor que parsee a `double` perdería precisión), los ids como string, y
  una *allowlist* de claves que rompe el build ante un campo nuevo, que es el momento de
  preguntarse si es dato personal.
- **Cache** — cada decorador contra un almacén en memoria con reloj controlado: que el hit
  no vaya al origen, que el TTL expire, que la invalidación borre la clave, que una
  escritura no deje servir datos viejos, que lo guardado sea un escalar y no una
  representación, y que con el almacén caído todo siga funcionando contra el origen.
- **Autorización (dominio)** — `ReservationAccessPolicyTest` prueba «una reserva
  pertenece a un único usuario» con cinco objetos y ningún framework: el titular la
  alcanza, otro titular no, backoffice sí pero sin ser dueño, y el alcance del listado
  se le impone al que no puede elegirlo. Que este test no necesite un contexto es el
  punto de tener la política en el dominio.
- **Seguridad del borde** — `ReservationSecurityTest` prueba lo que tiene que **fallar**:
  las cinco operaciones sin token, el 401 con el mismo `problem+json` que el resto de la
  API, el detalle que no explica por qué falló, el 404 —y no 403— sobre una reserva
  ajena, el 403 al pedir el listado de otro, y los headers de seguridad.
  `ReservationQuotaTest` verifica que la cuota corte **antes** del caso de uso.
- **Token → dominio** — `JwtActorConverterTest`: qué claim significa qué, que un token
  incompleto se rechace en lugar de producir un actor a medias, que un rol desconocido
  no otorgue nada y que el error nunca refleje el valor del claim.
  `JwtDecoderFactoryTest` verifica la invariante del arranque: ninguna combinación de
  propiedades produce una aplicación que no valide tokens.
- **Datos sensibles** — `PiiCipherTest` (ida y vuelta, que el ciphertext no sea
  determinista, que una manipulación falle en vez de devolver basura creíble, que una
  fila anterior al cifrado se siga leyendo), `LogSanitizerTest` (log forging con saltos
  de línea) y `PiiMaskerTest`.
- **Arquitectura** — ArchUnit sobre las reglas de dependencia entre capas, incluida la de
  que el dominio no importe `jakarta.persistence`, la de que los adaptadores de entrada
  hablen con los puertos y no con los servicios, la de que ni el dominio ni la aplicación
  importen Spring Security, y la de que `domain.access` no dependa de nada fuera del
  dominio. Las nuevas de este paso: que ni el dominio ni la aplicación importen
  `org.springframework.amqp` ni `com.rabbitmq` —el broker es un detalle de despliegue—,
  que los eventos de dominio no se contaminen con el envelope del mensaje, y que
  **ninguna clase con un método transaccional pueda alcanzar el catálogo de aeropuertos**,
  que es la regla que convierte «acordate de validar afuera de la transacción» en algo que
  falla en el build.

**Integración (119)**

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
- `ReservationSecurityIT` — la autorización de punta a punta, con tokens firmados de
  verdad y datos reales en la base: que la reserva de otro responda 404 en las tres
  operaciones y que no se escriba nada, que enumerar identificadores no devuelva un solo
  dato de pasajero, que el listado sin filtro traiga sólo lo propio con dos usuarios
  cargados, que una clave de idempotencia filtrada cree la reserva del atacante en lugar
  de devolver la del dueño, que mandar el documento de otro no revele sus datos ni le
  pise los suyos, que el documento esté cifrado en la columna, que un token vencido o
  firmado con otra clave sea 401, y que la auditoría registre el intento rechazado
  —aunque la respuesta sea 404— y rechace que la modifiquen.
- `JdbcEventOutboxIT` — el outbox durable contra PostgreSQL real, agrupado por hallazgo
  de la auditoría: que un rollback se lleve el evento (no se notifica un hecho que no
  ocurrió), que un segundo `pollPending` devuelva vacío porque el primero **reclamó**, que
  cuatro despachadores concurrentes entreguen cada mensaje exactamente una vez, que un
  reclamo con lease vencido vuelva a ser elegible, que un mensaje que acaba de fallar **no**
  vuelva en la corrida siguiente, que la espera crezca y tenga techo, que cincuenta
  mensajes contra un destino caído sigan pendientes en lugar de morir en veinte segundos,
  que un fallo permanente muera en el primer intento, y que el replay reencole con los
  intentos en cero.
- `OutboxOpsIT` — las herramientas de operación **sin broker**, que es la restricción del
  diseño: que la aplicación arranque con el publicador que sólo loguea, que las tres
  preguntas operativas se respondan por Actuator, que un `depth` de `-1` diga «no se sabe»
  y no «está vacía», que el endpoint liste la dead letter y la reencole, y que un proceso
  que muere entre el commit y el envío no pierda la notificación —ni antes ni después de
  haberla reclamado—.
- `MessagingFlowIT` — el circuito completo contra RabbitMQ real: alta → outbox → exchange
  → cola → consumidor → efecto; el mismo mensaje republicado que deja un solo efecto; un
  tipo desconocido que termina en la DLQ y se puede mirar sin vaciarla; el replay de un
  mensaje ya aplicado que no produce un segundo aviso; dos eventos desordenados que se
  aplican los dos; y un mensaje sin cola atada que **falla** en lugar de descartarse en
  silencio.
- `ConsumerResilienceIT` — qué pasa cuando el consumidor no puede procesar: que un
  mensaje agote sus vueltas y termine en la DLQ, que un fallo permanente no gaste
  reintentos, que un mensaje venenoso no frene a los sanos de atrás, y —lo que más
  importa— que con el consumidor **entero caído** la API siga respondiendo con la misma
  latencia y el relay siga despachando, porque su trabajo termina con el ack del broker y
  no con el procesamiento del consumidor.
- `CatalogOutsideTransactionIT` — veinticuatro altas concurrentes contra un catálogo que
  tarda 1,5 s por consulta, midiendo en paralelo un `GET` que no toca el catálogo. Antes
  ese `GET` fallaba con el timeout del pool: las veinte conexiones estaban retenidas por
  transacciones esperando HTTP. Es el hallazgo de mayor impacto transversal y el único que
  no toca la mensajería.
- `ReservationApiIT` — el mismo flujo pero **por HTTP** contra la base real: el `ETag` de
  una respuesta usado en el `If-Match` de la siguiente, el reintento que devuelve 200 sin
  duplicar filas, el `ETag` viejo que da 409 sin escribir, el listado con sus filtros, su
  orden y su paginación resueltos en SQL, el alta del usuario como parte de la reserva —con
  la base arrancando vacía—, el filtro del listado por email, y el documento OpenAPI
  generado por el contexto completo, con Swagger UI respondiendo.
