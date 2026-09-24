# Diseño de la observabilidad

> Salida del prompt [19 — Diseño de la observabilidad](../prompts/19-diseno-de-observabilidad.md).
> Entrada del prompt [20 — Auditoría de la observabilidad](../prompts/20-auditoria-de-observabilidad.md).

Diseño hecho sobre el código de este repositorio y no sobre el enunciado: los niveles salen
de leer las 97 llamadas a `log.*` que hay hoy en `src/main/java`, el inventario de métricas
de los 28 nombres declarados como constante, y los huecos de correlación de seguir el MDC
por los tres hilos que lo pierden.

- §0 [Punto de partida real](#0-punto-de-partida-real)
- §1 [Esquema de campos del log](#1-esquema-de-campos-del-log)
- §2 [Niveles de severidad](#2-niveles-de-severidad)
- §3 [Qué se loguea, qué no, y qué nunca](#3-qué-se-loguea-qué-no-y-qué-nunca)
- §4 [Catálogo de métricas](#4-catálogo-de-métricas)
- §5 [Trazas](#5-trazas)
- §6 [Alertas](#6-alertas)
- §7 [Qué cambia en el código y en el compose](#7-qué-cambia-en-el-código-y-en-el-compose)
- §8 [El costo del esquema](#8-el-costo-del-esquema)

---

## 0. Punto de partida real

El enunciado describe el estado del sistema con tres imprecisiones que cambian el diseño, y
omite tres huecos que lo condicionan. Conviene fijarlos antes de decidir nada.

| Lo que dice el enunciado | Lo que hay en el código | Consecuencia |
|---|---|---|
| «No hay registry de Prometheus» | `micrometer-registry-prometheus` **está** en el `pom.xml` y `prometheus` **está** en `management.endpoints.web.exposure.include` | No hay que agregar el registry: hay que agregar **quién raspa** y los histogramas, que sí faltan |
| «No hay métricas del camino del pedido» | Existen `reservations.catalog.{fanout,errors,retries,budget_exhausted}`, `reservations.degraded.{responses,stale.age,exhausted}`, `reservations.outbox.dispatch.{skipped,probes}`, `reservations.messaging.{out-of-order,dead-lettered}` | Falta menos de lo que parece: la latencia **por llamada** (hoy sólo se mide el itinerario entero), la tasa de error **por endpoint de negocio**, y el conteo **por respuesta** degradada |
| «No hay backend de observabilidad en el `compose.yaml`» | Cierto | §7 |
| «Los logs no son estructurados» | Cierto: no hay `logback-spring.xml` y el único control es `logging.level.com.edteam.reservations: INFO` | §1 |

Y los tres huecos que el enunciado no menciona:

1. **El correlation id no llega a todos lados.** El MDC de Logback es un `ThreadLocal` no
   heredable. `BudgetedCityCatalogFanout` reparte cada ciudad en un hilo virtual con
   `CompletableFuture.supplyAsync(..., workers)`, y **ningún log escrito adentro de ese hilo
   tiene `correlationId`**: eso incluye los cinco `WARN`/`ERROR` de `RestCityCatalogClient`,
   que son justamente los que uno va a buscar cuando un `POST` sale degradado. Lo mismo pasa
   con las dos tareas de `adapter/in/scheduling`, que no nacen de un pedido HTTP y hoy no
   tienen ningún id.
2. **El correlation id no sale del proceso hacia el catálogo.** `RestCityCatalogClient` no
   propaga el header. Si mañana el catálogo es un servicio nuestro, la traza se corta en el
   borde.
3. **El 401 no deja rastro.** `ProblemDetailAuthenticationHandlers.entryPoint` escribe el
   `problem+json` y no loguea nada; el único log de un token rechazado es un `DEBUG` en
   `JwtActorConverter`, apagado en producción. Hoy una campaña de credenciales robadas contra
   la API es invisible en los logs, y el enunciado la pide como evento obligatorio (§3).

Lo que ya está bien y no se toca: `CorrelationIdFilter` (validación estricta del id del
cliente, limpieza en el `finally`), `PiiMasker`, `LogSanitizer`, el rastro de auditoría en
base como pieza separada, Actuator en un puerto no publicado, y los health indicators de
`redis` y `rabbit` apagados.

---

## 1. Esquema de campos del log

### 1.1 La decisión de formato

**Un solo formato, JSON, en todos los entornos**, escrito a `stdout` por
`logstash-logback-encoder` configurado en `src/main/resources/logback-spring.xml`. No hay
variante «linda» para local: un formato distinto en desarrollo es un esquema que sólo se
prueba en producción, y el día que un campo falta nadie lo nota hasta que hace falta. En la
terminal se lee con `./mvnw spring-boot:run | jq -r '...'`; en el navegador, con el Grafana
del §7.

La pieza que hace esto compatible con la hexagonal es la **API fluida de SLF4J 2**:

```java
// application/service/ConfirmReservationService.java
log.atInfo()
   .addKeyValue("event", "reservation.confirmed")
   .addKeyValue("reservationId", saved.requireId())
   .addKeyValue("reservationVersion", saved.version())
   .log("Reserva confirmada");
```

`addKeyValue` es `org.slf4j`, no `net.logstash`. La capa de aplicación declara **qué dato
acompaña al hecho**; que ese par termine siendo un campo JSON, un campo de un formato
binario o nada lo decide el encoder, que vive en `infrastructure` y se configura en un XML.
`HexagonalArchitectureTest` gana una regla que lo sostiene (§7.1). Es también lo que cumple
la restricción «un campo por dato»: el `message` queda como texto humano fijo, sin
interpolar, y deja de ser el transporte de información.

### 1.2 Campos obligatorios en todo registro

| Campo | Tipo | Obl./Ctx. | Origen | Ejemplo |
|---|---|---|---|---|
| `@timestamp` | string ISO-8601 UTC, ms | obligatorio | Logback (`LogstashEncoder`) | `2026-09-24T14:03:11.482Z` |
| `level` | string enum | obligatorio | Logback | `INFO` |
| `logger` | string | obligatorio | Logback, abreviado a 40 chars | `c.e.r.a.service.CreateReservationTransaction` |
| `message` | string **fijo, sin interpolar** | obligatorio | literal del `log(...)` | `Reserva creada` |
| `thread` | string | obligatorio | Logback | `http-nio-8080-exec-3` |
| `service` | string | obligatorio | `<customFields>` desde `spring.application.name` | `flight-reservations` |
| `env` | string enum | obligatorio | `<customFields>` desde `reservations.environment` (nueva, `APP_ENV`) | `local` |
| `version` | string | obligatorio | `<customFields>` desde `build.version` (`build-info` de Maven) | `0.0.1-SNAPSHOT` |
| `instance` | string | obligatorio | `<customFields>` desde `HOSTNAME` | `reservations-7d9c4-fjx2q` |
| `correlationId` | string `[A-Za-z0-9_-]{8,64}` | obligatorio | **MDC**, puesto por `CorrelationIdFilter`, repuesto por `OutboxDispatcherService` y `ReservationEventListener`, generado por el decorador de tareas (§7.1) | `9f1c2e8a-5b11-4f0d-9a3e-77c0c1d4e210` |
| `traceId` / `spanId` | string hex | obligatorio **cuando la traza está muestreada** | MDC, puesto por Micrometer Tracing (§5) | `4bf92f3577b34da6a3ce929d0e0e4736` |
| `stack_trace` | string | contextual: sólo si hay excepción | `Throwable` del `log.*(..., e)`, con `ShortenedThrowableConverter` (30 líneas, causas colapsadas) | `java.net.SocketTimeoutException: …` |

`clientIp` también está en el MDC hoy, pero **no** es obligatorio en todo registro: sólo
aparece en el log de acceso (§1.3) y en la fila de auditoría. Un `DEBUG` de cache no necesita
la IP del cliente, y escribirla en cada línea multiplica un dato personal por el volumen del
log sin comprar nada.

### 1.3 Campos contextuales por tipo de evento

Todo registro que describe un hecho —y no una línea de diagnóstico suelta— lleva un campo
`event` con un nombre estable de un vocabulario cerrado. Es el campo sobre el que se filtra,
y el que permite que el `message` pueda cambiar de redacción sin romper una consulta.

**A. Pedido HTTP** (`event=http.request`) — una línea por pedido, la escribe el nuevo
`RequestLogFilter` en `infrastructure/logging`.

| Campo | Tipo | Origen | Ejemplo |
|---|---|---|---|
| `http.method` | string | `HttpServletRequest` | `POST` |
| `http.route` | string **plantilla** | atributo `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` | `/v1/reservations/{reservationId}` |
| `http.status` | int | `HttpServletResponse` | `201` |
| `duration_ms` | long | reloj del filtro | `842` |
| `clientIp` | string | MDC (`CorrelationIdFilter`) | `172.18.0.1` |
| `actorId` | long | MDC, puesto por `JwtActorConverter` — **id interno, nunca el email** | `4471` |
| `degraded` | array de string | `Degradation.sources()` | `["api-catalog"]` |
| `errorCode` | string | `ApiErrorCode` de la respuesta, si hubo | `VERSION_CONFLICT` |

**B. Llamada saliente** (`event=catalog.call`) — `RestCityCatalogClient`. Nivel `DEBUG` en el
camino feliz (ver §8), `WARN`/`ERROR` cuando falla.

| Campo | Tipo | Origen | Ejemplo |
|---|---|---|---|
| `dependency` | string enum | constante del adaptador | `api-catalog` |
| `operation` | string plantilla | constante | `GET /city/{code}` |
| `cityCode` | string | parámetro, ya validado como IATA | `EZE` |
| `attempt` | int | `RetryingCityCatalogClient` | `2` |
| `duration_ms` | long | reloj del cliente | `2003` |
| `outcome` | string enum | clasificación existente: `found`, `absent`, `unavailable`, `throttled`, `integration`, `circuit_open`, `bulkhead_full`, `budget_exhausted` | `unavailable` |
| `http.status` | int | respuesta, si la hubo | `503` |
| `reason` | string | `e.getMessage()` **pasado por `LogSanitizer`** | `Read timed out` |

**C. Evento de dominio** (`event=reservation.created|confirmed|modified|cancelled`,
`event=outbox.dispatched`, `event=consumer.applied`).

| Campo | Tipo | Origen | Ejemplo |
|---|---|---|---|
| `reservationId` | long | agregado | `10241` |
| `userId` | long | agregado — **id interno** | `4471` |
| `reservationVersion` | int | agregado | `3` |
| `itinerary.origin` / `.destination` | string IATA | agregado, sólo en alta y modificación | `EZE` / `MAD` |
| `passengers` | int (**cantidad**, no los pasajeros) | agregado | `2` |
| `eventType` | string enum | `OutboxMessage.type()` | `reservation.created` |
| `messageId` | uuid | `OutboxMessage.id()` | `0b4f…` |
| `sequence` | long | `OutboxMessage.sequence()` | `7` |
| `attempts` | int | outbox, en los fallos | `4` |

**D. Tarea programada** (`event=outbox.relay.tick`, `event=messaging.purge`).

| Campo | Tipo | Origen | Ejemplo |
|---|---|---|---|
| `job` | string enum | constante de la clase | `outbox-relay` |
| `job.runId` | string | el mismo valor que el `correlationId` sintético de la corrida | `job-outbox-relay-3f2a…` |
| `dispatched` / `failed` / `deferred` | int | `OutboxDispatchResult` | `12` / `0` / `3` |
| `duration_ms` | long | reloj del scheduler | `310` |
| `skipReason` | string enum | `circuit_open`, `probe`, si aplica | `circuit_open` |

El `correlationId` de una tarea programada es **sintético y por corrida**:
`job-<nombre>-<uuid>`. No es un id de pedido y no pretende serlo; su trabajo es que las N
líneas de una misma vuelta del relay se puedan agrupar, y que la restricción «todo registro
lleva identificador de correlación» no tenga excepciones. Cuando el relay despacha un mensaje
concreto, `OutboxDispatcherService` ya **pisa** ese id con el del pedido que originó el hecho
(`message.correlationId()`), que es el comportamiento correcto y ya está implementado.

### 1.4 Un registro de ejemplo por tipo

**A. Pedido HTTP** — un `POST` que salió degradado porque el catálogo no respondió:

```json
{
  "@timestamp": "2026-09-24T14:03:11.482Z",
  "level": "INFO",
  "logger": "c.e.r.infrastructure.logging.RequestLogFilter",
  "message": "Pedido atendido",
  "thread": "http-nio-8080-exec-3",
  "service": "flight-reservations",
  "env": "prod",
  "version": "1.4.2",
  "instance": "reservations-7d9c4-fjx2q",
  "correlationId": "9f1c2e8a-5b11-4f0d-9a3e-77c0c1d4e210",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7",
  "event": "http.request",
  "http.method": "POST",
  "http.route": "/v1/reservations",
  "http.status": 201,
  "duration_ms": 842,
  "clientIp": "172.18.0.1",
  "actorId": 4471,
  "degraded": ["api-catalog"]
}
```

**B. Llamada saliente** — el catálogo devolvió 503 en el segundo intento:

```json
{
  "@timestamp": "2026-09-24T14:03:10.910Z",
  "level": "WARN",
  "logger": "c.e.r.a.o.airport.catalog.RestCityCatalogClient",
  "message": "El catálogo respondió con error",
  "thread": "VirtualThread[#142]/runnable@ForkJoinPool-1-worker-4",
  "service": "flight-reservations",
  "env": "prod",
  "version": "1.4.2",
  "instance": "reservations-7d9c4-fjx2q",
  "correlationId": "9f1c2e8a-5b11-4f0d-9a3e-77c0c1d4e210",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "b7ad6b7169203331",
  "event": "catalog.call",
  "dependency": "api-catalog",
  "operation": "GET /city/{code}",
  "cityCode": "MAD",
  "attempt": 2,
  "duration_ms": 2003,
  "outcome": "unavailable",
  "http.status": 503,
  "reason": "Service Unavailable"
}
```

El `correlationId` en ese registro es el aporte del arreglo del hueco 1 del §0: hoy esa línea
sale sin él, porque el hilo virtual del fan-out no heredó el MDC.

**C. Evento de dominio** — el alta que originó el pedido anterior:

```json
{
  "@timestamp": "2026-09-24T14:03:11.402Z",
  "level": "INFO",
  "logger": "c.e.r.a.service.CreateReservationTransaction",
  "message": "Reserva creada",
  "thread": "http-nio-8080-exec-3",
  "service": "flight-reservations",
  "env": "prod",
  "version": "1.4.2",
  "instance": "reservations-7d9c4-fjx2q",
  "correlationId": "9f1c2e8a-5b11-4f0d-9a3e-77c0c1d4e210",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7",
  "event": "reservation.created",
  "reservationId": 10241,
  "userId": 4471,
  "reservationVersion": 0,
  "itinerary.origin": "EZE",
  "itinerary.destination": "MAD",
  "passengers": 2
}
```

Ni el email del solicitante, ni el nombre, ni el documento, ni la fecha de viaje. El
`userId` es el identificador interno: fuera de nuestra base no identifica a nadie, y adentro
se resuelve con un `SELECT`, que es exactamente la propiedad que se quiere (§3.3).

**D. Tarea programada** — una vuelta del relay del outbox:

```json
{
  "@timestamp": "2026-09-24T14:03:15.001Z",
  "level": "INFO",
  "logger": "c.e.r.a.in.scheduling.OutboxDispatchScheduler",
  "message": "Outbox despachado",
  "thread": "reservations-sched-1",
  "service": "flight-reservations",
  "env": "prod",
  "version": "1.4.2",
  "instance": "reservations-7d9c4-fjx2q",
  "correlationId": "job-outbox-relay-3f2a91c4",
  "event": "outbox.relay.tick",
  "job": "outbox-relay",
  "job.runId": "job-outbox-relay-3f2a91c4",
  "dispatched": 12,
  "failed": 0,
  "deferred": 3,
  "duration_ms": 310
}
```

Sin `traceId`: una tarea programada no nace de una traza, y fabricarle una que no tiene padre
llena Tempo de trazas de un solo span que nadie va a mirar. Las publicaciones individuales
que hace la vuelta **sí** llevan la traza del pedido original, porque el envelope la propaga
(§5.3).

---

## 2. Niveles de severidad

El criterio que separa los niveles es **una sola pregunta: ¿quién tiene que hacer algo, y
cuándo?** No es la gravedad técnica del evento ni si hubo excepción.

> Una dependencia que degrada y se recupera es `WARN`, por más que haya tirado una
> `SocketTimeoutException`: el sistema la absorbió, el usuario recibió una respuesta y nadie
> tiene que levantarse. Un pedido que el usuario perdió es `ERROR`, por más que el stack
> trace sea aburrido: hay un efecto que no ocurrió y alguien tiene que decidir qué hacer con
> él.

| Nivel | Qué significa acá | Ejemplo real del código | Quién lo mira y cuándo |
|---|---|---|---|
| **ERROR** | Se perdió un efecto que el sistema prometió, o quedó un estado que ningún mecanismo automático va a resolver. **Siempre hay una acción humana pendiente.** Cada `ERROR` tiene que poder responder «¿y ahora qué hago?» | `OutboxDispatcherService:184` — fallo permanente publicando: el mensaje va a la dead letter del productor sin reintentos. La notificación de esa reserva **no va a salir** hasta que alguien la reencole por `/actuator/outbox`.<br>`ReservationEventListener:108` — mensaje ilegible a la DLQ.<br>`RestCityCatalogClient:179` — el catálogo rechaza con 4xx: credencial vencida o contrato roto, no hay reintento que lo arregle.<br>`ReservationExceptionHandler:252` — error no controlado: el usuario recibió un 500. | Guardia, en el momento: cada uno de estos está detrás de una alerta del §6. La tasa de `ERROR` es la señal más simple de que algo empezó |
| **WARN** | El sistema se desvió del camino feliz y **se compensó solo**. Nadie actúa por una línea; se actúa por la **tasa**. Es el nivel de toda la resiliencia | `DegradationRecorder:72` — `[degradado] api-catalog respondió por fallback (retries_exhausted)`: el usuario recibió su reserva, con un dato de catálogo viejo.<br>`Circuit:93` — transición de estado de un circuito.<br>`RetryingCityCatalogClient:142` — se reintenta en 300 ms.<br>`RateLimitFilter:155` — cuota superada: el rechazo es el comportamiento correcto.<br>`JdbcEventOutbox:242` — el reclamo del relay venció y otra instancia lo tomó: el diseño lo previó | Quien investiga, después. Un `WARN` suelto no se mira; un panel de `WARN` por minuto por `event` es de las primeras cosas que se abre cuando algo anda raro |
| **INFO** | Ocurrió un **hecho de negocio** o un cambio de estado del proceso que hay que poder reconstruir meses después. Volumen acotado y previsible: uno por operación, no uno por paso | `CreateReservationTransaction:125` — reserva creada.<br>`ConfirmReservationService:74` — reserva confirmada.<br>`CancelReservationService:83` — reserva cancelada.<br>`OutboxDispatchScheduler:81` — resultado de la vuelta del relay (y **sólo si hubo trabajo**: el `if (result.total() > 0)` ya está escrito).<br>`MessagingConfiguration:87` y los demás del arranque — qué cableado quedó activo | Todos, siempre. Es el nivel que está encendido en producción (`logging.level.com.edteam.reservations: INFO`) y el que sostiene la investigación de un reclamo, junto con la tabla de auditoría |
| **DEBUG** | Por qué el sistema tomó una decisión que desde afuera se ve sola. **Apagado en producción**; se enciende por paquete y por un rato | `JwtActorConverter:80` — token rechazado por solicitante inválido.<br>`CachingAirportCatalog:156` — la ciudad se sirvió de la ventana de gracia.<br>`OutboxDispatchScheduler:103` — tick salteado por circuito abierto (**y el comentario del código explica por qué no es `WARN`: 720 líneas iguales en una caída de una hora**).<br>`RestCityCatalogClient:153` — el catálogo no conoce el código | Quien está depurando un caso concreto, con el paquete encendido a mano por unos minutos |
| **TRACE** | Detalle por operación de un componente de alto volumen. **Nunca en producción, ni un minuto**: es el nivel que multiplica por diez el costo del log | `CircuitBreakingCacheStore:182` — `Cache degradado en get: 'rsv:city:EZE' se sirve desde memoria`: en un `POST` esto son hasta 16 líneas.<br>`CachingReservationSearchQuery:102` — total del listado resuelto contra la base | Nadie fuera de una corrida local reproduciendo un bug de cache |

### 2.1 Reclasificaciones que este diseño propone

Cuatro llamadas del código actual no cumplen el criterio y se mueven:

| Dónde | Hoy | Debe ser | Por qué |
|---|---|---|---|
| `RestCityCatalogClient:131` (`RestClientException`) | `ERROR` | `ERROR` — se confirma | Es integración rota, no red. Queda como está |
| `RestCityCatalogClient:126` (`ResourceAccessException`) | `WARN` | `WARN` — se confirma | Hay fallback y el circuito lo cuenta |
| `JdbcNotificationDeliveryLog:53` (`[notificaciones] emitida`) | `INFO` | `DEBUG` | Duplica exactamente lo que ya dice `ProcessReservationEventService:115` (`[consumidor] aplicado`) sobre el mismo `messageId`. Son 10.500 líneas por día que no agregan un dato que la otra no tenga (§8) |
| `OutboxDispatcherService:133` (`N mensajes liberados sin gastar intento`) | `INFO` | `DEBUG`, y el dato sube al resumen del tick | El tick ya reporta `deferred` en su línea `INFO`. Dos líneas por vuelta para el mismo número |
| `ProblemDetailAuthenticationHandlers.entryPoint` | **no loguea** | `WARN` con `event=auth.failed` | Hueco 3 del §0. Sin esto no hay forma de ver un ataque de credenciales. Va con `reason` de un enum cerrado (`no_token`, `invalid_token`), **nunca con el token ni con parte de él** |

### 2.2 Lo que un nivel no es

`ERROR` no es «hubo una excepción». `ReservationExceptionHandler` maneja
`ReservationNotFoundException`, `ConcurrentUpdateException` y las de validación: todas son
excepciones, todas producen un 4xx, y **ninguna se loguea a nivel `ERROR`** —varias no se
loguean en absoluto—. Un 404 y un 409 son respuestas correctas del sistema a pedidos
incorrectos; su volumen es una métrica (`http.server.requests` con `status`), no una línea de
log por ocurrencia. La excepción es `ReservationExceptionHandler:153`
(`ScopeViolationException`, hoy `INFO`), que **sí** deja línea porque además del 404 hay una
fila de auditoría `DENIED` que la acompaña: es evidencia, no diagnóstico.

---

## 3. Qué se loguea, qué no, y qué nunca

### 3.1 Eventos que sí o sí dejan registro

Cada uno con su `event`, su nivel y su contrapartida en métrica. Ninguno es opcional: si la
línea no está, el sistema tiene un punto ciego declarado.

| Evento | `event` | Nivel | Dónde | Por qué es obligatorio |
|---|---|---|---|---|
| Alta de reserva | `reservation.created` | INFO | `CreateReservationTransaction` | Es el hecho que el negocio vende. Sin él no hay forma de reconstruir un reclamo |
| Confirmación | `reservation.confirmed` | INFO | `ConfirmReservationService` | Cambio de estado con efecto de dinero |
| Modificación | `reservation.modified` | INFO | `ModifyReservationTransaction` | Cambia el itinerario ya vendido |
| Cancelación | `reservation.cancelled` | INFO | `CancelReservationService` | El reclamo más frecuente es «yo no cancelé». Log + fila de auditoría + evento |
| Fallo de autenticación | `auth.failed` | WARN | `ProblemDetailAuthenticationHandlers` (**nuevo**) | Detección de credential stuffing. Hoy no existe |
| Rechazo por permisos | `auth.denied` | WARN | `ProblemDetailAuthenticationHandlers` / `ScopeViolationException` | Intento de acceso a recurso ajeno |
| Cuota superada | `rate.limited` | WARN | `RateLimitFilter:155` | Ya existe. Se le agrega el contador (§4) |
| Apertura de circuito | `circuit.state` | WARN | `Circuit:93` | Ya existe. Una transición, no un log por llamada bloqueada |
| Respuesta degradada | `degraded.served` | WARN | `DegradationRecorder:72` | Ya existe, y es el único camino por el que sale un fallback |
| Sin fallback posible | `degraded.exhausted` | WARN | `DegradationRecorder:90` | Ya existe |
| Mensaje a dead letter (productor) | `outbox.dead_lettered` | ERROR | `JdbcEventOutbox:269` / `OutboxDispatcherService:184` | Notificación perdida hasta intervención manual |
| Mensaje a dead letter (consumidor) | `consumer.dead_lettered` | ERROR | `ReservationEventListener:108,124` | Ídem |
| Reencolado manual | `outbox.requeued` | INFO | `JdbcEventOutbox:361,370`, `RabbitDeadLetterQueue:131` | Es una acción de un humano sobre datos de producción: tiene que quedar escrita |
| Error no controlado | `unhandled.error` | ERROR | `ReservationExceptionHandler:252` | El usuario recibió un 500 |
| Cableado del arranque | `startup.wiring` | INFO / WARN | `MessagingConfiguration`, `CacheConfiguration`, `AdapterConfiguration`, `JwtDecoderFactory`, `PiiCipher` | Media docena de líneas una vez por proceso que dicen si el broker está apagado, si el cache es el de memoria o si la clave de cifrado es la de desarrollo. Es el primer lugar donde se mira cuando «en este ambiente no anda» |

### 3.2 Lo que es ruido y no se escribe

| Qué | Por qué no | Qué lo reemplaza |
|---|---|---|
| Un `INFO` por consulta al catálogo | 8 por `POST` en un ida y vuelta con escala: **más líneas por el detalle que por el hecho** | `DEBUG` por llamada (`event=catalog.call`), un resumen del fan-out a `DEBUG`, y las métricas `reservations.catalog.*` del §4, que responden lo mismo agregado |
| Hit/miss de cache | Hasta 16 por `POST` | `reservations.cache.gets{result}`, que ya existe |
| Tick del relay sin trabajo | 17.280 líneas/día diciendo «no hice nada» | El `if (result.total() > 0)` ya escrito, más el gauge `reservations.outbox.pending` |
| `WARN` por tick durante una caída del broker | 720 líneas iguales en una hora | `DEBUG` (ya está así, con el comentario explicándolo) + la transición del circuito, que se loguea una vez |
| Entrada/salida de método, `"entrando a create()"` | El tiempo entre spans lo dice la traza, y el log de acceso ya da la duración total | §5 |
| Cuerpo de pedidos y respuestas | Es donde viven los pasajeros. Además dispara el costo | El contrato está en OpenAPI; lo que hizo el pedido está en el evento de dominio |
| 404 y 409 de negocio | Son respuestas correctas | `http.server.requests{status}` |
| Health checks del orquestador | Cada 10 s por instancia | Se excluyen por ruta en `RequestLogFilter`, y de todas formas entran por el puerto 9090 |

### 3.3 Política de datos sensibles

El piso es `PiiMasker` y `LogSanitizer`, que ya existen. Lo que este diseño agrega es que la
regla sea **por campo y no por criterio de quien escribe la línea**.

| Campo | Tratamiento | Por qué |
|---|---|---|
| Documento del pasajero | **se omite** | No hay ningún problema de operación que se resuelva viendo un documento en un log. `PiiMasker.redact()` ya lo dice |
| Nombre y apellido del pasajero | **se omite** | Ídem. Lo que se escribe es la **cantidad** de pasajeros |
| Fecha de nacimiento del pasajero | **se omite** | Dato de categoría especial combinado con el nombre; por separado no sirve para nada |
| Email del solicitante | **se omite en los logs**; se enmascara `an***@example.com` sólo donde ya está (`UserPersistenceAdapter:80`, a `DEBUG`) | El log lleva `actorId`/`userId`, que es el id interno. El email enmascarado sigue siendo correlacionable entre registros y no hace falta para operar |
| Email en la tabla de auditoría | **se escribe en claro** | La auditoría es otra pieza: vive en nuestra base, bajo el mismo tratamiento que el resto de los datos del usuario, y su razón de ser es identificar sin ambigüedad quién hizo qué. No sale del perímetro como los logs |
| `Authorization`, cualquier JWT o fragmento | **nunca** | Un token en un log es una credencial válida replicada a un sistema indexado. Ni siquiera los primeros caracteres: el `kid` y el `sub` alcanzan para identificar la sesión si hace falta |
| `reservations.security.pii.key` | **nunca** | Es la clave con la que se cifran los documentos. `PiiCipher:85` ya avisa del uso de la de desarrollo **sin imprimirla** |
| Contraseñas de base, Redis y broker | **nunca** | Vienen del entorno; ningún log las lee |
| `clientIp` | **se escribe**, sólo en `event=http.request` y en la auditoría, con retención de 30 días | Es dato personal, y es el único identificador con el que se investiga abuso y se defiende un rechazo. El costo se acota restringiendo dónde aparece y por cuánto tiempo, no enmascarándolo: media IP no sirve para nada |
| `correlationId` | **se escribe** | Lo genera el sistema o lo valida contra `[A-Za-z0-9_-]{8,64}` antes de aceptarlo. No es dato personal y es la costura de todo |
| Clave de idempotencia | **se enmascara**: `sha256(key)` truncado a 12 hex | Hoy se escribe en claro (`CreateReservationTransaction:109`, `ReservationController:349`) y **es un valor que elige el cliente**: nada impide que un integrador ponga ahí un email o un número de tarjeta. El hash conserva lo único que sirve —poder decir «es la misma clave»— y no importa qué haya puesto el cliente adentro |
| Origen, destino y fechas del itinerario | **se escriben**, pero nunca en la misma línea que un identificador de pasajero | Por separado es un dato operativo. Atado a una persona es su viaje: es exactamente la distinción que ya hace `LoggingEventPublisher:51` vs `:53`, donde el payload completo va a `DEBUG` |
| `payload` del evento | **`DEBUG`**, nunca `INFO` | Lleva ruta y fecha atadas al usuario. Ya está así en `RabbitEventPublisher:107` y `LoggingEventPublisher:53`. El diseño lo ratifica y agrega que `DEBUG` está apagado en producción por configuración, no por costumbre |
| Cuerpos de error de terceros | **se escriben pasados por `LogSanitizer`**, truncados a 256–512 | Es dato de origen externo: puede traer saltos de línea que fabriquen registros falsos. `DegradationRecorder` y `RestCityCatalogClient` ya lo hacen |
| `http.route` | **se escribe la plantilla**, no la URI | `/v1/reservations/{reservationId}`, no `/v1/reservations/10241`. En el log la URI concreta es tolerable; en una etiqueta de métrica es una serie por reserva (§4.4) |

Con JSON, además, el vector que `LogSanitizer` mitiga deja de existir estructuralmente: un
`\n` adentro de un valor es un carácter escapado dentro de un campo, no un separador de
registros. El saneado **se queda igual**, porque el `javadoc` de la clase ya explica que el
visor de logs no es el único que lee estas líneas, y porque el truncado sigue siendo una
defensa de costo.

---

## 4. Catálogo de métricas

Las preguntas que el catálogo tiene que poder responder son seis, y se ordenan de afuera
hacia adentro: **cuántas reservas por minuto**, **qué porcentaje falla**, **cuánto tarda el
`POST`**, **cuánto tarda el catálogo**, **cuántas respuestas salieron degradadas**, **cuánto
lag tiene el outbox**. Tres ya tienen respuesta parcial en el código; las otras tres no.

### 4.1 Lo que ya existe y se conserva

| Nombre | Tipo | Etiquetas | Qué pregunta responde | Estado |
|---|---|---|---|---|
| `reservations.cache.gets` | contador | `cache`, `result`∈{hit,miss} | ¿El cache sirve? ¿Cuándo dejó de servir? | existe |
| `reservations.cache.puts` / `.evictions` | contador | `cache` | ¿Se está llenando y desalojando? (el `maxmemory 30mb` del compose) | existe |
| `reservations.cache.errors` | contador | `cache`, `operation` | ¿Redis está fallando aunque el health esté apagado a propósito? | existe |
| `reservations.cache.size` | gauge | `cache` | Tamaño del fallback en memoria | existe |
| `reservations.outbox.enqueued` / `.claimed` / `.dispatched` | contador | — | Caudal del relay | existe |
| `reservations.outbox.failed` | contador | `failure`∈{transient,permanent} | ¿Se está gastando el presupuesto de reintentos? | existe |
| `reservations.outbox.deferred` | contador | — | Mensajes liberados sin gastar intento | existe |
| `reservations.outbox.pending` | gauge | — | Cuántos hechos esperan salir | existe |
| **`reservations.outbox.lag`** | gauge (segundos) | — | **Cuánto lag tiene el outbox** — pregunta 6 | existe |
| `reservations.outbox.dead` | gauge | — | Notificaciones perdidas esperando intervención | existe |
| `reservations.outbox.dispatched.retained` | gauge | — | Si la purga diaria está corriendo | existe |
| `reservations.outbox.dispatch.skipped` / `.probes` | contador | — | Cuánto tiempo estuvo el relay en gate por circuito | existe |
| `reservations.messaging.consumed` | contador | `type`, `outcome` | Caudal y resultado del consumidor | existe |
| `reservations.messaging.out-of-order` / `.dead-lettered` | contador | `type`, … | Salud del consumidor idempotente | existe |
| `reservations.messaging.dlq.depth` | gauge | — | Profundidad de la DLQ del consumidor | existe |
| `reservations.messaging.enabled` | gauge 0/1 | — | Si el publicador real está cableado (el `LoggingEventPublisher` es una pérdida silenciosa fuera de local) | existe |
| `reservations.catalog.errors` | contador | `kind`∈{circuit_open,bulkhead_full,throttled,retries_exhausted,integration} | Qué le pasa al catálogo, y en particular `kind=integration`, que es el que necesita una persona | existe |
| `reservations.catalog.retries` | contador | — | Cuánto se está reintentando | existe |
| `reservations.catalog.budget_exhausted` | contador | — | Ciudades abandonadas por el presupuesto del itinerario | existe |
| `reservations.catalog.fanout` | **timer** | — | Cuánto tarda resolver el itinerario **completo** | existe |
| `reservations.degraded.responses` | contador | `dependency`, `reason` | Cuántos **eventos** de degradación hubo | existe |
| `reservations.degraded.stale.age` | timer | `dependency` | Cuán viejo es el dato que servimos | existe |
| `reservations.degraded.exhausted` | contador | `dependency`, `reason` | Cuántos pedidos no tuvieron fallback y fallaron de frente | existe |
| `resilience4j.circuitbreaker.*` | gauge/timer | `name`, `state`, `kind` | Estado y transiciones de cada circuito | existe (vía `resilience4j-micrometer`) |
| `jvm.*`, `hikaricp.*`, `process.*` | varios | — | Memoria, GC, y **el pool de 20 conexiones**, que es el recurso que el diseño de resiliencia identifica como el que se agota | existe (autoconfigurado por Actuator) |

### 4.2 Lo que falta y hay que agregar

| Nombre | Tipo | Etiquetas | Qué pregunta responde y qué decisión permite | Estado |
|---|---|---|---|---|
| `http.server.requests` **con histograma** | histograma | `method`, `uri` (plantilla), `status`, `outcome`, `exception` | **Preguntas 2 y 3.** El medidor lo crea Actuator solo, pero hoy sólo publica `count`/`sum`/`max`: sin `percentiles-histogram` **no se puede calcular un p95 agregado entre instancias**, y por lo tanto no se puede alertar por latencia. Decisión: si el p95 del `POST` pasa el SLO, se mira `reservations.catalog.call` para saber si es el catálogo o nosotros | **agregar** (sólo configuración) |
| `reservations.operations` | contador | `operation`∈{create,confirm,modify,cancel,get,list}, `outcome`∈{ok,duplicate,conflict,not_found,denied,rejected,degraded,unavailable} | **Preguntas 1 y 2**, en lenguaje de negocio. `http.server.requests` no distingue un 409 por `If-Match` desactualizado —que es el sistema funcionando— de un 503 por catálogo caído. Decisión: si suben los `conflict`, el problema es de contrato con el frontend; si suben los `unavailable`, es una dependencia | **agregar** |
| `reservations.catalog.call` | **timer** | `outcome`∈{found,absent,unavailable,throttled,integration,circuit_open,bulkhead_full} | **Pregunta 4.** Hoy sólo se mide el itinerario entero (`fanout`), que con 8 ciudades en paralelo esconde si una tardó 6 s o si las ocho tardaron 700 ms. Decisión: separar «el catálogo está lento» de «nuestro presupuesto es corto» | **agregar** |
| `reservations.requests.degraded` | contador | `dependency`, `route` (plantilla) | **Pregunta 5**, y no es la que ya existe. `reservations.degraded.responses` cuenta **eventos**: un `POST` con tres ciudades servidas del *stale* lo incrementa tres veces. Este cuenta **respuestas**, una vez por pedido, desde `DegradationHeaderFilter`, que ya tiene la lista deduplicada en `Degradation.sources()`. Decisión: «el 4 % de las respuestas salió degradada» es una frase que se le puede decir a un producto; «hubo 1.200 degradaciones» no | **agregar** |
| `reservations.security.auth.failures` | contador | `reason`∈{no_token,invalid_token,forbidden} | ¿Hay una campaña de credenciales? Hoy el 401 no deja ni log ni métrica. Decisión: si pasa el umbral, se mira `clientIp` en los logs y se bloquea en el borde | **agregar** |
| `reservations.security.rate_limited` | contador | `method`, `route` (plantilla) | ¿El rate limiter está frenando tráfico legítimo o abuso? `RateLimitFilter:155` sólo loguea. Decisión: subir o bajar la cuota | **agregar** |
| `reservations.outbox.dispatch.duration` | timer | — | ¿La vuelta del relay entra cómoda en los 5 s del intervalo? Decisión: bajar el `batchSize` antes de que las vueltas se pisen | **agregar** |
| `reservations.catalog.inflight` | gauge | — | Ocupación del bulkhead. Decisión: dimensionarlo | **agregar** |

### 4.3 Etiquetas comunes

Un `MeterRegistryCustomizer` en `infrastructure/config` agrega a **todo** medidor:
`application=flight-reservations`, `env=${reservations.environment}`,
`instance=${HOSTNAME}`. Son las tres que permiten que dos entornos escriban en el mismo
Prometheus sin mezclarse y que un panel diga «esta instancia es la que está mal».

`instance` es la única etiqueta común cuya cardinalidad depende del despliegue: está acotada
por el tamaño de la flota, pero en un entorno con autoscaling agresivo y pods efímeros se
convierte en churn de series. Queda anotado: si la flota pasa de ~50 instancias, `instance`
se saca de las etiquetas comunes y se deja que la ponga el scraper como `pod`, donde el
retention de series muertas lo maneja Prometheus.

### 4.4 Cardinalidad: la regla y los valores prohibidos

**Regla:** una etiqueta sólo puede tomar valores de un conjunto enumerado en el código. Si
para saber qué valores puede tomar hay que mirar la base de datos, no es una etiqueta.

| Valor | ¿Etiqueta? | Por qué |
|---|---|---|
| `reservationId`, `userId`, `messageId`, `correlationId` | **prohibido** | Una serie por reserva. Con 5.000 reservas/día y 30 días de retención, un solo contador se convierte en 150.000 series |
| Email, documento, IP del cliente | **prohibido** | Ilimitado, y además es dato personal replicado a un sistema con retención propia |
| Código IATA de ciudad | **prohibido** | ~9.000 valores posibles y los elige el cliente en el cuerpo del pedido: es cardinalidad controlada por un tercero. Hoy el código **no** es etiqueta en ninguna métrica (`reservations.catalog.errors` usa `kind`) y así se queda. Qué ciudad falló se responde con el log, que sí lo lleva |
| URI concreta (`/v1/reservations/10241`) | **prohibido** | Ídem el id. Por eso `route` es siempre la plantilla del `HandlerMapping`, que son 6 valores |
| Mensaje de excepción | **prohibido** | Texto libre, muchas veces con el valor que causó el error adentro |
| `operation`, `outcome`, `kind`, `reason`, `dependency`, `cache`, `type`, `failure`, `state` | permitido | Enums en el código. El más grande es `outcome` de `reservations.operations`, con 8 valores |
| `status` HTTP | permitido | Acotado por el conjunto de respuestas que la API sabe producir (~10) |
| `route` (plantilla) | permitido | 6 rutas de negocio |

**Presupuesto resultante**, por instancia: `http.server.requests` domina, con ~7 combinaciones
de método y ruta × ~10 status × ~35 buckets del histograma ≈ **2.500 series**. Todo el resto
del catálogo —incluidas las 8 métricas nuevas— suma menos de 300. Total **~2.800 series por
instancia**, que un Prometheus local absorbe sin configuración especial y que deja margen
para crecer sin revisar la decisión.

Lo que hay que vigilar es el histograma: es el único medidor cuyo costo se multiplica por el
número de buckets. Por eso se define con **SLOs explícitos** y no con
`percentiles-histogram` a secas: 200 ms, 500 ms, 1 s, 2 s, **4 s**, **4,5 s** y 8 s. Los dos
del medio no son redondos por casualidad —son los objetivos del `POST` y del `PUT` que fija
`docs/resilience/design.md` §6 y que `LatencyBudgetTest` sostiene como constantes—, y tener
un bucket justo en el objetivo es lo que hace que el porcentaje de pedidos dentro del SLO sea
una lectura directa y no una interpolación. Siete buckets, no los ~70 por defecto de
Micrometer.

---

## 5. Trazas

### 5.1 La decisión: sí, con un alcance chico y explícito

**El sistema necesita trazas distribuidas.** La objeción obvia —«es un solo servicio, el
`correlationId` alcanza»— es correcta para cinco de los seis caminos y falsa para el que más
duele.

El caso que la justifica está en `BudgetedCityCatalogFanout`: un `POST` con escala resuelve
hasta 8 ciudades **en paralelo, en hilos virtuales, contra un presupuesto de tiempo
compartido**, y cada ciudad atraviesa tres decoradores (`Retrying` → circuito + bulkhead →
`RestClient`) más un cache con ventana de gracia. Cuando ese `POST` tarda 3,2 s, la pregunta
operativa es *cuál de las ocho consumió el presupuesto y en qué decorador se fue el tiempo*.
Con logs, eso son ocho líneas `DEBUG` sin orden garantizado entre sí y sin duración
individual; hay que reconstruir el paralelismo a mano leyendo timestamps. Con una traza es
un gráfico de barras. Es exactamente la clase de problema para la que existen las trazas, y
este sistema lo tiene por diseño, no por accidente.

Los otros dos argumentos, más chicos pero reales: el salto asincrónico
`pedido → outbox → relay → broker → consumidor` hoy se sigue por `correlationId`, que dice
*que* pasó pero no *cuánto tardó cada tramo* —y el lag del outbox es una métrica agregada que
no sirve para un caso puntual—; y el sistema está a un paso de dejar de ser un solo servicio
(el catálogo ya es un proceso aparte).

Lo que **no** justifica trazas, y por eso el alcance es chico: el `GET` por id, el listado,
y cualquier camino que sólo toque la base. Ahí `http.server.requests` + los gauges de Hikari
responden todo, y un span por sentencia JDBC multiplicaría el volumen de la traza por diez
para decir lo que el pool ya dice.

### 5.2 Qué es un span, y dónde empieza y termina cada uno

| Span | Tipo | Empieza | Termina | Atributos |
|---|---|---|---|---|
| `POST /v1/reservations` | SERVER | Filtro de observación de Spring MVC, al entrar el pedido | Al confirmarse la respuesta | `http.route`, `http.status_code`, `actor.id`, `degraded` |
| `catalog.fanout` | INTERNAL | `BudgetedCityCatalogFanout.resolve`, antes de repartir | En el `finally`, junto al `fanout.record(...)` que ya está | `catalog.cities` (cantidad), `catalog.budget_ms`, `catalog.out_of_budget` |
| `GET /city/{code}` | CLIENT | `RestCityCatalogClient.findByCode`, por ciudad y **por intento** | Al clasificar la respuesta o al tirar la excepción | `city.code`, `attempt`, `http.status_code`, `outcome` |
| `db.transaction` | INTERNAL | Al abrir la transacción del caso de uso | Al hacer commit o rollback | `db.operation`, `reservation.id` |
| `outbox.publish` | PRODUCER | `OutboxDispatcherService.publish`, por mensaje | Al confirmar el publisher confirm | `messaging.message_id`, `messaging.type`, `attempt` |
| `reservation-events process` | CONSUMER | `ReservationEventListener.onMessage` | Al decidir el destino del mensaje | `messaging.message_id`, `outcome`, `round` |

Un span **no** es un método: es un tramo con una duración que alguien puede querer explicar.
Instrumentar cada clase de `application` produciría trazas de 40 spans donde 36 son
llamadas de nanosegundos, y el gráfico se vuelve ilegible justo cuando hace falta.

La pieza técnica que hay que resolver —y que ya es un bug hoy, sin trazas— es la
**propagación al hilo virtual**. `CompletableFuture.supplyAsync(..., workers)` no lleva ni el
MDC ni el contexto de traza. Con `io.micrometer:context-propagation` (que el BOM de Spring
Boot ya administra) se captura una vez en el hilo llamador y se restituye en cada tarea:

```java
ContextSnapshot snapshot = ContextSnapshotFactory.builder().build().captureAll();
futures.add(CompletableFuture.supplyAsync(snapshot.wrap(() -> …), workers));
```

Un solo `wrap` arregla las dos cosas: el `correlationId` faltante en los `WARN` del catálogo
(hueco 1 del §0) y el padre del span cliente.

### 5.3 Propagación hacia afuera

**Hacia el catálogo, por HTTP.** Formato **W3C Trace Context** (`traceparent`,
`tracestate`), que es el default de Micrometer Tracing con el bridge de OpenTelemetry. Se
inyecta con un `ClientHttpRequestInterceptor` en el `RestClient` de
`AdapterConfiguration`, junto con **`X-Correlation-Id`**, que hoy tampoco se propaga. El
`api-catalog` de hoy es un contenedor de terceros que ignora los dos headers: la traza
termina en nuestro span cliente y eso ya resuelve el caso del §5.1. El día que el catálogo
sea nuestro, la traza se continúa sola sin tocar este código.

**Hacia el consumidor, por el broker.** El `EventEnvelope` ya lleva `correlationId`; se le
agrega **`traceparent`** como un campo más del envelope —no como header de AMQP— por la
misma razón por la que el `correlationId` viaja ahí: el envelope es el contrato del mensaje y
sobrevive a un cambio de broker. El relay lo escribe al despachar y `ReservationEventListener`
lo lee y abre el span consumidor como **hijo remoto** del span productor.

Hay un matiz que conviene dejar escrito: entre el `POST` y el despacho pasan hasta 5
segundos, y el span servidor ya cerró. El span `outbox.publish` no es hijo del span HTTP
—sería un span que empieza después de que su padre terminó—: cuelga del span de la vuelta
del relay y lleva un **span link** al contexto del pedido original. Es la construcción que
OpenTelemetry define para exactamente este caso y es lo que hace que la traza del pedido
muestre «de acá salió un mensaje que se despachó más tarde» sin mentir sobre las duraciones.

### 5.4 Relación con el `correlationId`: conviven, y ninguno reemplaza al otro

| | `correlationId` | `traceId` |
|---|---|---|
| Quién lo genera | `CorrelationIdFilter`, o el cliente si manda uno con formato válido | Micrometer Tracing |
| Cobertura | **100 % de los pedidos, siempre** | Sólo los pedidos muestreados |
| Dónde vive además del log | Header de respuesta, columna `correlation_id` de la tabla `auditoria`, campo del `EventEnvelope` | Sólo en el backend de trazas y en el MDC |
| Para qué sirve | Ir del reclamo de un pasajero —que tiene el header— a sus líneas de log y a su fila de auditoría | Ver el gráfico de tiempos de un pedido |
| Vida útil | La del log y la de la auditoría (años) | La de la traza (días) |

**El `correlationId` no se reemplaza por el `traceId`.** La razón es concreta: el muestreo.
Con 10 % de muestreo, el 90 % de los `traceId` no existe en ningún backend, y la columna
`correlation_id` de la tabla de auditoría —que es un artefacto de cumplimiento con retención
de años— quedaría apuntando a trazas que nunca se guardaron. Un identificador que a veces no
resuelve no sirve para lo que la auditoría necesita.

Los dos campos están en el MDC y en cada línea de log, así que el pivote es directo: del
`correlationId` del reclamo se llega al log, del log se lee el `traceId`, y si esa traza fue
muestreada se abre en Tempo desde el mismo Grafana.

**Muestreo:** `1.0` en local (todo, para poder ver lo que se está construyendo) y `0.1` en
producción. El 10 % es suficiente para caracterizar latencia; para los casos puntuales está
el log, que es del 100 %. El muestreo por error —quedarse siempre con las trazas que
fallaron— es *tail sampling* y no se hace en la aplicación: es una decisión del colector, y
se deja anotada como el próximo paso cuando haya volumen que lo justifique.

### 5.5 Qué costaría no hacerlo todavía

Si la decisión fuera posponer —y es defendible, porque es la pata más cara de las tres—, el
disparador para volver a discutirla sería cualquiera de estos tres, y conviene dejarlos
escritos: **(a)** que el catálogo pase a ser un servicio propio, **(b)** que aparezca un
segundo servicio que consuma `reservations.events` y participe del mismo flujo de negocio,
o **(c)** que el p95 del `POST` viole su SLO más de una vez y la causa no se pueda atribuir
con `reservations.catalog.call`. Este diseño elige no esperar a (c), porque el fan-out con
presupuesto ya existe hoy y (c) es cuestión de tiempo.

---

## 6. Alertas

Seis alertas. El criterio para que una exista es que se pueda contestar **qué está roto para
el usuario** y **qué hace quien la recibe**; si alguna de las dos respuestas es «hay que
investigar», no es una alerta, es un panel.

Dos severidades: **P1 despierta a alguien** (página, a cualquier hora) y **P2 abre un
ticket** (se ve en el horario de trabajo).

| # | Alerta | Métrica y umbral | Ventana | Sev. | Qué está roto para el usuario | Acción esperada |
|---|---|---|---|---|---|---|
| 1 | **Integración con el catálogo rota** | `rate(reservations_catalog_errors_total{kind="integration"}[5m]) > 0` | 5 min | **P1** | Ningún `POST` ni `PUT` puede completarse: es el único fallo del catálogo que **no tiene fallback** y se propaga como 502. Nadie puede crear ni modificar una reserva | Verificar credencial y contrato del catálogo. Es un cambio del proveedor o una credencial vencida: no se arregla solo y ningún reintento lo mejora |
| 2 | **Las escrituras están fallando** | `sum(rate(http_server_requests_seconds_count{uri=~"/v1/reservations.*",method=~"POST\|PUT\|DELETE",status=~"5.."}[10m])) / sum(rate(http_server_requests_seconds_count{uri=~"/v1/reservations.*",method=~"POST\|PUT\|DELETE"}[10m])) > 0.02` | 10 min | **P1** | Más de 1 de cada 50 intentos de reservar, modificar o cancelar termina en error. El usuario ve un formulario que falla y reintenta, multiplicando la carga | Mirar `reservations.operations{outcome}` para separar base, catálogo y defecto propio. Si es la base, mirar `hikaricp.connections.pending` |
| 3 | **El `POST` se salió del presupuesto** | `histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{uri="/v1/reservations",method="POST"}[10m])) > 4` | 15 min | **P1** | Crear una reserva tarda más que el techo que el diseño se puso para 1 de cada 20 usuarios. A esa altura la gente abandona el formulario o lo manda dos veces | Contrastar con `reservations.catalog.call` y `reservations.catalog.fanout`: si el catálogo está lento, bajar el presupuesto del itinerario para fallar al *stale* antes; si no, es la base |
| 4 | **Las notificaciones no salen** | `reservations_outbox_lag > 300` | 10 min | **P1** | Un pasajero reservó y no recibió el mail de confirmación hace más de 5 minutos. La reserva existe y es correcta: lo que falta es que el usuario se entere | Mirar el estado del circuito del broker y `reservations.outbox.dispatch.skipped`. Si el broker está caído, no hay que hacer nada más que confirmarlo: el outbox drena solo al volver. Si el broker está sano y el lag sube igual, el relay está trabado |
| 5 | **Hay notificaciones muertas** | `increase(reservations_outbox_dead[30m]) > 0` o `reservations_messaging_dlq_depth > 0` | 30 min | P2 | Uno o más pasajeros **nunca** van a recibir su notificación sin intervención manual. No hay reintento pendiente: el mensaje agotó su presupuesto | Inspeccionar con `/actuator/outbox` y `/actuator/messaging-dlq`, entender por qué murió y reencolar. Es la contracara de estos dos endpoints, que existen para esto |
| 6 | **Presión de credenciales** | `rate(reservations_security_auth_failures_total{reason="invalid_token"}[10m]) > 5` y `> 20×` la línea de base del día anterior | 15 min | P2 | Nada todavía. Es la alerta que llega **antes** de que algo se rompa: alguien está probando tokens contra la API | Buscar el `clientIp` de los `event=auth.failed` en los logs, bloquear en el borde o bajar la cuota del rate limiter. Si aparecen `auth.denied` con tokens válidos, escalar a P1 |

No hay una alerta equivalente a la 3 para el `PUT`, y la omisión es deliberada: el objetivo
del diseño es 4,5 s y el peor caso medido hoy es **7,1 s** (hallazgo 12 parcial de
`docs/resilience/implementation.md`). Una alerta contra un techo que el sistema todavía no
cumple es una alerta que suena siempre, y una alerta que suena siempre deja de leerse. El
`PUT` entra al panel desde el primer día, y la alerta se escribe el día que los 2,6 s de la
lectura previa se recorten. El histograma con el bucket en 4,5 s es lo que va a permitir
verificar ese recorte.

### 6.1 Sobre qué **no** se alerta, a propósito

Esta lista es parte del diseño, no una omisión:

- **Redis caído.** El cache es opcional por diseño y su health indicator está apagado justo
  para que no saque la instancia de rotación. Una caída de Redis se ve en
  `reservations.cache.errors` y en el circuito, y llega al usuario como latencia, que es lo
  que mide la alerta 3. Alertar por Redis sería despertar a alguien por un componente cuyo
  único aporte es ahorrar tiempo.
- **RabbitMQ caído.** Mismo razonamiento, y es explícito en el `application.yml`: el daño
  real lo mide `reservations.outbox.lag`, que es la alerta 4. El broker puede estar caído
  diez minutos sin consecuencia para nadie.
- **Circuito abierto.** Un circuito abierto es el sistema **funcionando**: decidió dejar de
  pagar timeouts. Lo que importa es si la degradación llegó al usuario, y eso lo miden las
  alertas 1 y 3. Un circuito abierto durante horas sí merece revisión, pero como panel, no
  como página.
- **Tasa de 4xx.** Un 409 por `If-Match` desactualizado o un 404 por reserva ajena son la API
  haciendo su trabajo. Se miran en `reservations.operations{outcome}` cuando cambian de
  forma, no cuando existen.
- **Respuestas degradadas.** `reservations.requests.degraded` es el panel que se abre cuando
  llega cualquiera de las alertas anteriores, y es lo que se le muestra a producto para
  decidir si el fallback es aceptable. No dispara nada por sí mismo: una respuesta degradada
  es una respuesta.
- **Hit rate del cache.** Es una métrica de eficiencia, no de salud.

La disponibilidad del proceso (liveness/readiness) no aparece en la tabla porque no la
resuelve Prometheus: la resuelven las sondas de `/actuator/health/{liveness,readiness}` que
ya están habilitadas, y el orquestador, que reinicia y saca de rotación antes de que una
alerta alcance a evaluarse.

---

## 7. Qué cambia en el código y en el `compose`

### 7.1 Por paquete

| Paquete / archivo | Qué pasa | Detalle |
|---|---|---|
| `pom.xml` | **se agrega** | `net.logstash.logback:logstash-logback-encoder` (Apache 2.0, versión fija: el BOM de Boot no la administra), `micrometer-tracing-bridge-otel` y `io.opentelemetry:opentelemetry-exporter-otlp` (ambas administradas por el BOM), `io.micrometer:context-propagation` (idem). Se agrega el goal `build-info` del plugin de Spring Boot, para que el campo `version` del log salga del build y no de una constante |
| `src/main/resources/logback-spring.xml` | **se agrega** | El archivo que hoy no existe. Encoder compuesto con providers explícitos —`timestamp`, `logLevel`, `loggerName` (abreviado), `message`, `threadName`, `mdc`, `keyValuePairs`, `stackTrace` con `ShortenedThrowableConverter`— y `<customFields>` con `service`, `env`, `version`, `instance`. Providers explícitos y no los defaults del encoder: los defaults cambian entre versiones de la librería y el esquema del log es un contrato |
| `application.yml` | **se modifica** | `reservations.environment: ${APP_ENV:local}`; `management.metrics.distribution.percentiles-histogram.http.server.requests: true` con `slo` explícitos (200 ms, 500 ms, 1 s, 2 s, **4 s**, **4,5 s**, 8 s); `management.tracing.sampling.probability: ${TRACING_SAMPLING:1.0}`; `management.otlp.tracing.endpoint`. `logging.level.com.edteam.reservations: INFO` **se conserva tal cual** |
| `infrastructure/logging/PiiMasker` | **se conserva** | Sin cambios |
| `infrastructure/logging/LogSanitizer` | **se conserva** | Sin cambios. El javadoc que dice «esto no reemplaza la solución de fondo, que es loguear en JSON» pasa a describir algo que ya se hizo; se actualiza el párrafo y se deja la clase |
| `infrastructure/logging/IdempotencyKeyDigest` | **se agrega** | `sha256(key)` truncado a 12 hex, para las dos llamadas que hoy escriben la clave en claro |
| `infrastructure/logging/RequestLogFilter` | **se agrega** | La línea `event=http.request`. Va después de `CorrelationIdFilter` y **envuelve** a `DegradationHeaderFilter`, para que la lista de `degraded` esté completa cuando escribe. Excluye `/actuator/**` |
| `infrastructure/logging/MdcTaskDecorator` | **se agrega** | `TaskDecorator` sobre el `ThreadPoolTaskScheduler` de `SchedulingConfiguration`: pone `correlationId=job-<nombre>-<uuid>` y `job=<nombre>` al entrar, los saca en el `finally`. Centralizado ahí y no en cada `@Scheduled`, para que una tarea nueva no pueda nacer sin id |
| `infrastructure/security/CorrelationIdFilter` | **se conserva** | Sin cambios. Es la pieza que ya estaba bien |
| `infrastructure/security/ProblemDetailAuthenticationHandlers` | **se modifica** | Gana el `WARN` con `event=auth.failed` / `auth.denied` y el contador `reservations.security.auth.failures`. Sigue sin decirle nada al cliente sobre el motivo: el motivo va al log, que es lo que su propio javadoc ya promete y hoy no cumple |
| `infrastructure/security/RateLimitFilter` | **se modifica** | Se le agrega el contador `reservations.security.rate_limited`. El `WARN` se conserva tal cual, incluida la decisión de no escribir la identidad |
| `infrastructure/adapter/out/airport/BudgetedCityCatalogFanout` | **se modifica** | `ContextSnapshot.wrap()` alrededor de cada tarea. Es el arreglo del hueco 1 del §0, y vale por sí solo aunque no se agregaran trazas |
| `infrastructure/adapter/out/airport/catalog/RestCityCatalogClient` | **se modifica** | Los 10 `log.*` pasan a la forma fluida con `event=catalog.call` y sus campos; el timer `reservations.catalog.call` se registra acá; el interceptor del `RestClient` inyecta `traceparent` y `X-Correlation-Id` |
| `infrastructure/adapter/in/rest/DegradationHeaderFilter` | **se modifica** | En el `finally` que ya tiene, incrementa `reservations.requests.degraded` una vez por dependencia y por **respuesta** |
| `infrastructure/adapter/in/scheduling/*` | **se modifica** | Sólo la forma de los logs (`event=outbox.relay.tick`, `messaging.purge`) y el timer de duración. La lógica de gate por circuito y el `if (result.total() > 0)` no se tocan |
| `infrastructure/adapter/out/messaging` + `out/outbox` | **se modifica** | `traceparent` como campo del `EventEnvelope`; span productor. El `correlationId` del envelope y su reposición en el MDC ya están y no se tocan |
| `infrastructure/adapter/in/messaging/ReservationEventListener` | **se modifica** | Abre el span consumidor desde el `traceparent` del envelope. El manejo de MDC existente se conserva |
| `infrastructure/adapter/out/audit/JdbcAuditTrailAdapter` | **se conserva** | El rastro de auditoría sigue siendo una pieza separada de los logs, en base, con su propia semántica transaccional. No se toca |
| `infrastructure/config/ObservabilityConfiguration` | **se agrega** | El `MeterRegistryCustomizer` con las etiquetas comunes y el registro de los medidores nuevos que no tienen dueño natural |
| `application/service/*` | **se modifica** | Los 14 `log.*` pasan a `log.atInfo().addKeyValue(...)`. **Sólo `org.slf4j`**: ni Logback, ni logstash, ni Micrometer. La capa no se entera de que el log es JSON |
| `domain/**` | **se conserva** | El dominio sigue sin loguear: hoy no tiene ni un `Logger` y eso pasa a estar verificado |
| `HexagonalArchitectureTest` | **se modifica** | Dos reglas nuevas: `domain` no puede depender de `org.slf4j` (la observabilidad es infraestructura, y el dominio ni siquiera se entera de que existe), y `domain`+`application` no pueden depender de `ch.qos.logback..`, `net.logstash..`, `io.micrometer..`, `io.opentelemetry..` ni de `infrastructure.logging..`. Las dos pasan con el código de hoy: son candados, no correcciones |

La regla nueva de ArchUnit es la que hace que la restricción de la hexagonal sea verificable
y no una convención. Sin ella, la forma más cómoda de escribir un campo estructurado desde un
servicio de aplicación es importar `net.logstash.logback.argument.StructuredArguments.kv`, y
a partir de ahí el caso de uso conoce el formato del log.

### 7.2 Qué se agrega al `compose.yaml`

Cuatro contenedores, todos OSS y gratis, todos detrás de un **profile** para que el
`docker compose up -d` de siempre siga levantando sólo lo que hace falta para correr la
aplicación:

```bash
docker compose --profile observability up -d
```

| Servicio | Imagen | Puerto (loopback) | Para qué |
|---|---|---|---|
| `prometheus` | `prom/prometheus:v3.1.0` | `127.0.0.1:9091` | Raspa `/actuator/prometheus` cada 15 s y evalúa las reglas del §6 |
| `grafana` | `grafana/grafana-oss:11.5.1` | `127.0.0.1:3000` | Los paneles. Datasources y dashboards **provisionados por archivo** en `docker/grafana/provisioning/`: un Grafana que hay que configurar a mano después de cada `down -v` no lo usa nadie |
| `loki` | `grafana/loki:3.3.2` | `127.0.0.1:3100` | Índice de logs. Con el JSON del §1, `correlationId` y `event` son etiquetas de consulta directas |
| `tempo` | `grafana/tempo:2.7.0` | `127.0.0.1:4318` (OTLP/HTTP) | Trazas del §5 |

Detalles que no son decorativos:

- **Los cuatro se publican sólo en loopback**, igual que Redis y RabbitMQ y por el mismo
  motivo que el `compose.yaml` ya explica en esos dos. Un Grafana sin contraseña alcanzable
  desde la red es una consola con acceso de lectura a todo.
- **Prometheus tiene que poder alcanzar el puerto 9090 de la aplicación, que corre fuera de
  Docker** (`mvn spring-boot:run`). Eso se resuelve con
  `extra_hosts: ["host.docker.internal:host-gateway"]` y un target
  `host.docker.internal:9090` en `prometheus.yml`. Es el detalle que hace que esto funcione
  en Linux y no sólo en Docker Desktop.
- **El puerto de gestión sigue sin publicarse hacia afuera.** Lo que cambia es que ahora un
  contenedor de la red local lo alcanza; la superficie de la API en 8080 no se toca. Es la
  misma decisión que el `application.yml` ya justifica para el endpoint `prometheus`.
- **Los logs llegan a Loki por archivo**, no por un appender de red en la aplicación: la
  aplicación escribe JSON a `stdout` y punto. Un appender que manda logs por TCP agrega un
  modo de falla nuevo —qué pasa cuando el backend de logs no responde— dentro del proceso que
  se está tratando de observar. En local, `grafana/alloy` lee el archivo redirigido; en un
  despliegue real lo hace el agente del nodo.
- **Sin Alertmanager en local.** Las reglas del §6 viven en
  `docker/prometheus/rules/reservations.yml` y se ven disparar en la pestaña *Alerts* de
  Prometheus, que es lo que hace falta para escribirlas y probarlas. A quién se le manda la
  página es una decisión del entorno, no del repositorio, y agregar un quinto contenedor para
  eso no compra nada.
- **Costo de recursos**: los cuatro contenedores en reposo son ~600 MB de RAM. Por eso el
  profile: el `compose.yaml` de todos los días sigue levantando cuatro servicios, no ocho.

Nada de esto requiere una cuenta. Prometheus, Grafana OSS, Loki y Tempo son Apache 2.0 /
AGPLv3 y corren enteros en local. La alternativa de Grafana Cloud free tier queda anotada
como destino de un entorno real —50 GB de logs y 100 k series entran holgadamente en el
volumen del §8— pero no es necesaria para levantar el proyecto.

---

## 8. El costo del esquema

La restricción lo pide explícitamente, y es la parte del diseño que más fácil se omite: **un
esquema de logging es una decisión de costo tanto como de observabilidad.**

### 8.1 Cuántas líneas por operación

| Operación | Líneas en `INFO` | Cuáles |
|---|---|---|
| `POST /v1/reservations` (feliz, 8 ciudades) | **2** | `http.request` + `reservation.created` |
| `POST` que salió degradado | 3–4 | las dos anteriores + `degraded.served` por dependencia |
| `GET` por id / listado | **1** | `http.request` |
| `PUT` / confirmación / cancelación | **2** | `http.request` + el evento de dominio |
| Vuelta del relay con trabajo | **1** | `outbox.relay.tick` |
| Vuelta del relay sin trabajo | **0** | el `if (result.total() > 0)` ya escrito |
| Mensaje consumido | **1** | `consumer.applied` (la línea de `JdbcNotificationDeliveryLog` baja a `DEBUG`, §2.1) |
| Purga diaria | 1/día | |

Las **8 consultas al catálogo de un `POST` no producen ninguna línea `INFO`**. Es la decisión
que la restricción señala: `event=catalog.call` vive en `DEBUG`, y lo que queda encendido en
producción es `reservations.catalog.call` (el timer) y `reservations.catalog.errors`, que
responden «cuánto tarda» y «cuántas fallan» sin escribir 40.000 líneas por día para decirlo.
Cuando una falla, la línea sí se escribe: `WARN` o `ERROR`, que son excepcionales por
definición.

### 8.2 El volumen

Con un escenario de **5.000 reservas y 100.000 pedidos HTTP por día** —una relación de 20
lecturas por escritura, que es lo típico de una API de reservas con un frontend que
lista y consulta:

| Fuente | Líneas/día |
|---|---|
| `http.request` | 100.000 |
| Eventos de dominio (5.000 altas + ~4.000 confirmaciones + ~1.500 modificaciones y cancelaciones) | 10.500 |
| Vueltas del relay con trabajo | ~8.000 |
| Mensajes consumidos | 10.500 |
| `WARN` + `ERROR` (~1 % de los pedidos) | ~1.500 |
| Arranque y purga | ~50 |
| **Total** | **~130.000** |

A **~450 bytes por registro** —medido sobre los ejemplos del §1.4, que son registros reales
del esquema y no una estimación— eso da **~59 MB/día**, **~1,8 GB/mes sin comprimir**. Loki
y cualquier backend comprimen JSON repetitivo cerca de 10:1, así que el almacenamiento real
ronda los **180 MB/mes**. Con 30 días de retención, cabe en el disco de una notebook y cabe
en el free tier de cualquier SaaS con dos órdenes de magnitud de margen.

### 8.3 Por qué es aceptable

El registro JSON pesa **~2,5 veces** lo que la línea de texto que Spring Boot escribe hoy
(~180 bytes). Eso es lo que se paga, y se paga por tres cosas concretas: que
`correlationId`, `event` y `reservationId` sean campos indexados y no una subcadena que hay
que parsear con una expresión regular; que el esquema sobreviva a un cambio de redacción del
mensaje; y que un salto de línea en un cuerpo de error de un tercero deje de poder fabricar
un registro falso.

El margen real está en lo que **no** se escribe:

- Un `INFO` por consulta al catálogo agregaría **40.000 líneas/día (+31 %)** para decir lo que
  dos métricas ya dicen mejor.
- `DEBUG` encendido en todo el paquete agregaría **~90.000 líneas/día (+69 %)**.
- `TRACE` encendido, con las hasta 16 operaciones de cache por `POST`, pasaría de 130.000 a
  **más de 1.200.000 líneas/día**: **diez veces** el volumen. Ese es el número que está
  detrás de la frase «`TRACE` nunca en producción, ni un minuto» del §2.

El presupuesto de métricas es despreciable al lado: **~2.800 series por instancia** (§4.4),
que con un scrape cada 15 s son ~16 MB/día de muestras por instancia antes de comprimir. Las
trazas al 10 % de muestreo, con ~15 spans por `POST` muestreado, son ~7.500 spans/día. La
pata cara de la observabilidad son los logs, y por eso es la única de las tres que este
documento presupuesta línea por línea.
