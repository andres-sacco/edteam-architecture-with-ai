# Implementación de la observabilidad

> Salida del prompt [21 — Implementación de la observabilidad](../prompts/21-implementacion-de-observabilidad.md).
> Entradas: el [diseño](design.md) (§19) y la [auditoría](audit.md) (§20).

Lo que sigue es lo que quedó en el código, no lo que se propuso. Cada número
está medido contra la salida real de `./mvnw verify` y contra el stack del
`compose.yaml` levantado, no estimado.

Tres números que ordenan el resultado, con el mismo método con el que la
auditoría midió los suyos:

- **0 llamadas a `log.*` con dato interpolado** en el camino del pedido, del
  relay y del consumidor. Antes: 97 llamadas, 0 estructuradas.
- Un arranque más 15 pruebas de `ReservationApiIT` producen **67 registros
  propios en `INFO`+, todos con `correlationId`**. Antes: 0.
- Un `POST /v1/reservations` exitoso deja **2 líneas** —`http.request` y
  `reservation.created`—, las dos con el mismo `correlationId`, y la línea del
  catálogo escrita **desde el hilo virtual del fan-out** también lo lleva.

- §1 [Trazabilidad contra la auditoría](#1-trazabilidad-contra-la-auditoría)
- §2 [Archivos](#2-archivos)
- §3 [Esquema de log final](#3-esquema-de-log-final)
- §4 [Catálogo de métricas final](#4-catálogo-de-métricas-final)
- §5 [Mapa de trazabilidad final](#5-mapa-de-trazabilidad-final)
- §6 [Lo que no se remedia en este paso](#6-lo-que-no-se-remedia-en-este-paso)
- §7 [Comandos de verificación](#7-comandos-de-verificación)
- §8 [El volumen, medido](#8-el-volumen-medido)

---

## 1. Trazabilidad contra la auditoría

Orden de la tabla: el del §7 de la auditoría —primero los datos sensibles y lo
que impide diagnosticar, después lo que sólo encarece—.

| # | Hallazgo | Mitigación implementada | Archivos | Test |
|---|---|---|---|---|
| **8** | El `correlationId` no aparece en ninguna línea | `logback-spring.xml` con encoder JSON y providers explícitos, `<mdc/>` entre ellos. Es el que desbloquea a los otros ocho | `src/main/resources/logback-spring.xml`, `pom.xml` | `LogSchemaTest.writesTheCorrelationIdFromTheMdc` |
| **1** | Email en claro a `INFO` en cada 403 por alcance | El handler deja de escribir `e.getMessage()` y el mensaje del dominio deja de nombrar al solicitante. Quién fue lo dice `actorRef`; el dato completo sigue en `auditoria`. Y pasa a `WARN` (cierra también el 29) | `ReservationExceptionHandler:160`, `ReservationAccessPolicy:93` | `ObservabilityIT.scopeViolationLeavesARecord` |
| **2** | Email en claro a `ERROR` por `IllegalStateException` | `PiiMasker.mask()` en el mensaje de la excepción, como las dos líneas de arriba del mismo archivo | `UserPersistenceAdapter:86` | `ObservabilityIT.anIntegrityViolationDoesNotLeakColumnValues` |
| **3** | El stack trace como canal abierto de PII y de datos de la base | `Throwables`: se escriben `exception.class` y un `reason` **redactado** (email, `Detail: Key (…)=(…)` de PostgreSQL, JWT). El stack se conserva —sin él no hay diagnóstico— y por eso los mensajes se corrigieron en el origen | `infrastructure/logging/Throwables.java`, `ReservationExceptionHandler:262` | `ObservabilityIT.anIntegrityViolationDoesNotLeakColumnValues`, gate de `verify` |
| **4** | El consumidor loguea `e.toString()` con el comentario prometiendo lo contrario | `Throwables.rootClassOf` + `reasonOf`: la clase, no el mensaje con los valores enlazados | `ReservationEventListener:230` | `ReservationEventListenerObservabilityTest.aTransientFailureDoesNotLogTheExceptionMessage` |
| **5** | El cuerpo del proveedor entra por la vía de la excepción sin sanear | Las dos líneas pasan por `Throwables.reasonOf`; `errorBody()` además redacta, no sólo sanea | `RestCityCatalogClient:195,265` | `ObservabilityIT.aFullRunLeaksNothing`, gate de `verify` |
| **6** | Nombre y fecha de nacimiento dentro de mensajes de excepción del dominio | Los mensajes señalan la **posición** del pasajero. `Reservation.toString()` y `Actor.toString()` pierden el email | `Reservation:318,356`, `Actor:74` | gate de `verify` (lo encontró en el `DEBUG` de Spring Security) |
| **7** | El valor crudo de Redis al log sin sanear ni acotar | `LogSanitizer.sanitize(raw, 64)` en los tres sitios | `CachingAirportCatalog:294`, `ReservationVersionCache:98`, `CachingReservationSearchQuery:138` | `ObservabilityIT.noRecordCarriesControlCharactersOrIsUnbounded` |
| **9** | Ningún registro de pedido HTTP | `RequestLogFilter` con `event=http.request`, después de `CorrelationIdFilter` y envolviendo a `DegradationHeaderFilter`, con `DispatcherType.ERROR` | `infrastructure/logging/RequestLogFilter.java`, `ObservabilityConfiguration:117` | `ObservabilityIT.theAccessRecordDescribesTheRequest` |
| **26** | Todo el dato viaja interpolado; los 4 eventos de dominio no comparten campos | API fluida de SLF4J 2 en el camino del pedido, del relay y del consumidor. Los cuatro eventos llevan el mismo juego: `reservationId`, `userId`, `reservationVersion`, `itinerary.*` | los 4 servicios de `application/service`, `LogFields` | `ObservabilityIT.domainEventsShareTheSameFields` |
| **15** | Los gauges del outbox devuelven ceros con la base caída | Centinela `-1` + `reservations.outbox.metrics.errors`, y la alerta 9 sobre ese contador | `OutboxMetrics:110` | `OutboxMetricsTest` (4 casos) |
| **19** | Nada alerta sobre las dos pérdidas silenciosas | Gauges `reservations.messaging.enabled` (**no existía**), `…pii.dev_key` y `…jwt.dev_tokens`, más las alertas 7 y 8 | `ObservabilityConfiguration:137`, `rules/reservations.yml` | `AlertRulesTest.theDesignedAlertsAreThere` |
| **10** | `MDC.remove` en vez de restaurar rompe el id de la corrida del relay | Se guarda el mapa anterior y se restituye, en el relay y en el consumidor | `OutboxDispatcherService:210`, `ReservationEventListener:196` | `OutboxDispatcherServiceTest.restoresTheRunIdInsteadOfClearingIt` |
| **11** | Los hilos virtuales del fan-out no heredan el MDC | `ContextSnapshot.setThreadLocals()` por tarea + `MdcThreadLocalAccessor` registrado por `ServiceLoader` (`context-propagation` no trae uno) | `BudgetedCityCatalogFanout:123`, `infrastructure/logging/MdcThreadLocalAccessor.java` | `MdcPropagationTest` (3 casos) |
| **12** | El `ERROR` del mensaje ilegible no puede llevar `correlationId` | Se lee el `correlationId` de las propiedades AMQP **antes** de parsear, validado con el mismo patrón que el header | `ReservationEventListener:118` | `ReservationEventListenerObservabilityTest.theUnreadableMessageErrorCarriesTheAmqpCorrelationId` |
| **13** | Las tareas programadas y el dispatch de error no tienen correlación | `MdcTaskDecorator` sobre el `ThreadPoolTaskScheduler` + `setDispatcherTypes(REQUEST, ASYNC, ERROR)` en los dos filtros del borde | `SchedulingConfiguration:33`, `SecurityConfiguration:184` | `MdcPropagationTest.scheduledTasksGetASyntheticId` |
| **14** | El `correlationId` no sale hacia el catálogo | `CorrelationIdPropagation.interceptor()` en el `RestClient`; el `traceparent` lo pone la instrumentación sobre el mismo builder | `infrastructure/logging/CorrelationIdPropagation.java`, `AdapterConfiguration:172` | `CorrelationIdPropagationTest` (3 casos) |
| **16** | La etiqueta `type` la elige quien publica | `boundedType()` contra el vocabulario, con centinela `other`, en los **tres** contadores | `ReservationEventListener:268` | `ReservationEventListenerObservabilityTest.theTypeTagIsBounded` |
| **17** | Los nombres de etiqueta del catálogo no son los del código | La tabla vive en un **test** y no en un documento | `MetricsCatalogTest` | `MetricsCatalogTest.everyMeterPublishesTheDeclaredTagKeys` |
| **18** | `percentiles-histogram` + `slo` dan ~70 buckets, no 7 | `slo` **sin** `percentiles-histogram` | `application.yml:679` | medido: 8 valores de `le=` (7 SLO + `+Inf`) |
| **22** | El fallo de autenticación no deja rastro | `WARN` con `event=auth.failed` y `reason` de enum cerrado + contador. Nunca el token | `ProblemDetailAuthenticationHandlers:71` | `ObservabilityIT.unauthenticatedLeavesARecord`, `…anInvalidTokenHasItsOwnReason` |
| **23** | El rechazo por cuota no tiene métrica | `reservations.security.rate_limited{method, route}` + contador propio del vaciado del registro | `RateLimitFilter:172`, `SecurityMetrics` | `MetricsCatalogTest.everyMeterPublishesTheDeclaredTagKeys` |
| **24** | El conflicto de versión es invisible | `INFO` con `event=reservation.version_conflict` y las dos versiones + `reservations.operations{outcome=conflict}`, que separa este 409 del de idempotencia | `ReservationExceptionHandler:127`, `BusinessMetrics` | `ObservabilityIT.aVersionConflictLeavesARecord` |
| **25** | El reencolado no dice quién; la purga no dice nada | `OpsActor.current()` en las tres acciones, y `purge` ahora deja línea | `OpsActor.java`, `JdbcEventOutbox:361`, `RabbitDeadLetterQueue:131`, `OutboxEndpoint:120` | cubierto por `OutboxOpsIT` + gate de `verify` |
| **27** | Los 4xx del catálogo, todos a `ERROR` | 401/403 → `ERROR` (credencial, necesita una persona); el resto → `WARN` (contrato, se mira por tasa), con `integration.kind` para separarlos | `RestCityCatalogClient:225` | `CityCatalogValidationTest` (existente, sigue verde) |
| **28** | `INFO` por mensaje durante una caída del broker | `:167` y `:133` a `DEBUG`; el guardia del tick mira `dispatched + failed` y no `total()` | `OutboxDispatcherService:147,175`, `OutboxDispatchScheduler:104` | `OutboxDispatcherServiceTest` (existente, adaptado) |
| **29** | `INFO` vs `WARN` para el rechazo por alcance | Resuelto en favor del §3.1: `WARN` | `ReservationExceptionHandler:160` | `ObservabilityIT.scopeViolationLeavesARecord` |
| **30** | Los avisos de clave de desarrollo son banners ASCII de 6 líneas | Un evento, un registro: el aviso en el `message` fijo y los datos en campos | `PiiCipher:85`, `JwtDecoderFactory:85` | `LogSchemaTest` (un registro = una línea) |
| **31** | El log explota en el incidente: 16 `WARN` por pedido con Redis caído | Un aviso por **pedido** en lugar de por operación; la cuenta exacta sigue en `reservations.cache.errors` | `CircuitBreakingCacheStore:207`, `DegradationHeaderFilter:66` | `CircuitBreakingCacheStoreTest` (existente, sigue verde) |
| **32** | La mitigación de la clave de idempotencia cuesta y no compra | Descartada: el valor ya es un `UUID` validado por el tipo. Lo que sí se corrigió es el **tercer** sitio, que el diseño no enumeraba | `ReservationExceptionHandler:172` | gate de `verify` |
| **33** | `PageNotFound` escribe un `WARN` por un 405 | Nivel explícito en `logback-spring.xml`, con la decisión escrita | `logback-spring.xml:180` | `LogSchemaTest.thirdPartyLoggersHaveAnExplicitLevel` |
| **20** | La alerta 1 describe un 502 que el código no produce | Corregida a 500 con `AIRPORT_CATALOG_ERROR`, y excluida del numerador de la alerta 2 para no paginar dos veces | `rules/reservations.yml` | `AlertRulesTest.theWriteFailureAlertDoesNotDoublePage` |
| **21** | La alerta 6 no se puede escribir | Recording rule `reservations:auth_failures:rate10m` + umbral absoluto como piso | `rules/reservations.yml` | `AlertRulesTest.theCredentialAlertHasItsBaseline` |

**Dos hallazgos que no estaban en la tabla y aparecieron al verificar:**

| Hallazgo | Cómo apareció | Corrección |
|---|---|---|
| `reservations.messaging.enabled` **no existía** | El §4.1 del diseño lo listaba como «existe» y la auditoría lo dio por hecho. No estaba en el código: la alerta 8 habría sido una regla contra una serie inexistente | Se agrega el gauge en `ObservabilityConfiguration` |
| La alerta del lag nombraba `reservations_outbox_lag` y la serie es `reservations_outbox_lag_seconds` | Contrastando la regla contra la salida real de `/actuator/prometheus`. Micrometer le agrega el sufijo de la unidad base | Corregida; el nombre queda cubierto por `AlertRulesTest.everyMetricInARuleExists` |
| El scrape de Prometheus recibía **401** | El §7.2 asume que «Prometheus alcanza el 9090 y listo»; la cadena de seguridad también cubre el contexto de gestión | `reservations.security.metrics-scrape-open`, apagada por defecto. Ver §6 |

---

## 2. Archivos

### `pom.xml` — se modifica

Cuatro dependencias y dos goals.

- `net.logstash.logback:logstash-logback-encoder:8.1`, **versión fija** en una
  property: el BOM de Boot no la administra, el esquema del log es un contrato
  y los providers por defecto de esta librería cambian entre versiones mayores.
- `micrometer-tracing-bridge-otel` y `opentelemetry-exporter-otlp` (versiones
  del BOM): el `traceId`/`spanId` en el MDC, la propagación W3C y el span del
  fan-out.
- `io.micrometer:context-propagation` (BOM): el arreglo del hilo virtual.
- Goal `build-info` del plugin de Boot: el campo `version` del log sale del
  build y no de una constante.
- `redirectTestOutputToFile` en surefire y failsafe, y el `exec-maven-plugin`
  que corre el gate de PII en la fase `verify`.

### `src/main/resources/logback-spring.xml` — se agrega

El archivo que no existía. Encoder compuesto con **providers explícitos**
—`timestamp` (UTC, ms), `logLevel`, `loggerName` (40), `message`, `threadName`,
`mdc`, `keyValuePairs`, `arguments`, `stackTrace` con
`ShortenedThrowableConverter`— y los cuatro campos comunes por `<pattern>`.

Dos decisiones que no son de estilo:

1. `clientIp` se **excluye** del provider del MDC. Está ahí porque el adaptador
   de auditoría lo lee, y es dato personal: en cada línea es el mismo dato
   multiplicado por el volumen del log, con la retención del log y no la de la
   auditoría. Donde sí se escribe es en `event=http.request`, como campo
   explícito de `RequestLogFilter`.
2. El appender legible vive **dentro** del perfil `local,dev`. Declarado fuera,
   Logback escribe un WARN de configuración en cada arranque de cualquier otro
   entorno.

### `src/main/resources/application.yml` — se modifica

`reservations.environment`, `reservations.build-version`, el `slo` del
histograma **sin** `percentiles-histogram`, `management.tracing.*` con el
exportador apagado por defecto, y `reservations.security.metrics-scrape-open`.

### `infrastructure/logging/` — se agrega

| Clase | Qué hace |
|---|---|
| `LogFields` | El vocabulario: nombres de campo y de `event`. Constantes y no enum porque el consumidor es `addKeyValue(String, Object)` |
| `RequestLogFilter` | La línea `event=http.request` y `reservations.operations` |
| `MdcTaskDecorator` | El id sintético por corrida de las tareas programadas; restituye el MDC en vez de borrarlo |
| `MdcThreadLocalAccessor` | Le enseña a `context-propagation` a llevarse el MDC. Registrado por `ServiceLoader`, así que también vale en los tests unitarios |
| `Throwables` | Cómo se escribe una excepción sin convertirla en un canal de PII |
| `ActorRef` | El seudónimo del solicitante: `sha256(email)` a 12 hex |
| `CorrelationIdPropagation` | El interceptor que saca el id del proceso |
| `PiiMasker`, `LogSanitizer` | **Se conservan.** El javadoc de `LogSanitizer` que decía «esto no reemplaza la solución de fondo, que es loguear en JSON» ahora describe algo que ya se hizo |

### `infrastructure/observability/` — se agrega

`BusinessMetrics` (`reservations.operations`, `reservations.requests.degraded`)
y `SecurityMetrics` (`auth.failures`, `rate_limited`, `quota_registry_reset`).

### `infrastructure/config/ObservabilityConfiguration` — se agrega

Etiquetas comunes, tope de cardinalidad, el registro de `RequestLogFilter` y
los tres gauges de configuración peligrosa.

### Adaptadores modificados

`RestCityCatalogClient` (10 llamadas + el timer `reservations.catalog.call` +
la separación 401/403 de 4xx), `RetryingCityCatalogClient`,
`BudgetedCityCatalogFanout`, `CachingAirportCatalog`, `CircuitBreakingCacheStore`,
`ReservationVersionCache`, `CachingReservationSearchQuery`,
`ReservationEventListener`, `JdbcNotificationDeliveryLog` (a `DEBUG`),
`OutboxDispatchScheduler`, `MessagingPurgeScheduler`, `OutboxMetrics`,
`JdbcEventOutbox`, `RabbitDeadLetterQueue`, `OutboxEndpoint`,
`DegradationHeaderFilter`, `DegradationRecorder`, `Circuit`,
`ReservationExceptionHandler`, `ProblemDetailAuthenticationHandlers`,
`RateLimitFilter`, `JwtActorConverter`, `CorrelationIdFilter`,
`SecurityConfiguration`, `AdapterConfiguration`, `SchedulingConfiguration`,
`PiiCipher`, `JwtDecoderFactory`.

### `domain/` — se modifica (y sigue sin loguear)

`ReservationAccessPolicy` y `Reservation` dejan de nombrar personas en los
mensajes de excepción; `Reservation.toString()` y `Actor.toString()` dejan de
escribir el email. El dominio **no tiene un solo `Logger`**, y ahora eso está
verificado por ArchUnit.

### `docker/` y `compose.yaml` — se agrega

`prometheus/prometheus.yml`, `prometheus/rules/reservations.yml`,
`grafana/provisioning/{datasources,dashboards}`, `loki/loki.yml`,
`tempo/tempo.yml`, `alloy/config.alloy`, `logs/.gitignore`; y cinco servicios
en el `compose.yaml` detrás del profile `observability`.

### `scripts/pii-log-gate.sh` — se agrega

El gate sobre la salida capturada de la suite, como paso de `verify`.

### Tests

| Archivo | Cubre |
|---|---|
| `LogSchemaTest` | 17 casos. Carga el `logback-spring.xml` **real** por Joran y corre su encoder |
| `ObservabilityIT` | 12 casos: trazabilidad, eventos críticos, datos sensibles |
| `MetricsCatalogTest` | El contrato nombre↔etiquetas, la cardinalidad y el vocabulario de `outcome` |
| `MdcPropagationTest` | Los tres saltos de correlación |
| `ReservationEventListenerObservabilityTest` | Los tres hallazgos del consumidor |
| `OutboxMetricsTest` | La honestidad de los gauges |
| `AlertRulesTest` | Que las alertas sean alertas |
| `CorrelationIdPropagationTest` | El id saliendo del proceso |
| `HexagonalArchitectureTest` | +2 reglas: el dominio no loguea; ni el dominio ni la aplicación conocen el backend |
| `OutboxDispatcherServiceTest` | +3 casos de MDC |
| `WebSliceConfiguration`, `LogCapture`, `ForbiddenPatterns` | Andamiaje |

**Tests existentes adaptados** (dos, los dos justificados en el propio archivo):

- `RateLimitFilterTest`: el constructor del filtro gana el contador.
- `OutboxOpsIT.countersTellApartTransientFromPermanentFailures`: pasa a medir
  el **delta**. El contexto de Spring —y con él el `MeterRegistry`— se comparte
  entre ITs, así que el valor absoluto pasaba sólo mientras ésa fuera la única
  IT que creaba reservas contra ese contexto. Era una propiedad del orden de
  ejecución, no del código bajo prueba.

---

## 3. Esquema de log final

Once campos comunes: `@timestamp` (UTC, ms), `level`, `logger` (40), `message`
(**fijo**), `thread`, `service`, `env`, `version`, `instance`, `correlationId`,
y `traceId`/`spanId` cuando la traza está muestreada. `stack_trace` sólo si hay
excepción. `clientIp` sólo en `http.request`.

Los cinco ejemplos que siguen son **salida real**, copiada de
`docker/logs/app.log` y de `target/failsafe-reports/`.

**A. Pedido HTTP**

```json
{"@timestamp":"2026-09-24T18:09:39.776Z","level":"INFO","logger":"c.e.r.i.logging.RequestLogFilter","message":"Pedido atendido","thread":"tomcat-handler-0","service":"flight-reservations","env":"local","version":"0.0.1-SNAPSHOT","instance":"asacco","actorRef":"3c6c5c25f4b6","traceId":"610fd10f31cac8f188fc0f8cea87f903","spanId":"1318f07932a7615b","correlationId":"humo-0000-0002","event":"http.request","http.method":"POST","http.route":"/v1/reservations","http.status":400,"duration_ms":609,"clientIp":"0:0:0:0:0:0:0:1","errorCode":"UNKNOWN_AIRPORT"}
```

**B. Llamada saliente** — escrita **desde el hilo virtual del fan-out**, que es
el registro que la auditoría señalaba como el que más duele perder:

```json
{"@timestamp":"2026-09-24T18:09:39.710Z","level":"WARN","logger":"c.e.r.i.a.o.a.c.RestCityCatalogClient","message":"El catálogo respondió sin cuerpo: se toma como inexistente","thread":"virtual-103","service":"flight-reservations","env":"local","version":"0.0.1-SNAPSHOT","instance":"asacco","actorRef":"3c6c5c25f4b6","traceId":"610fd10f31cac8f188fc0f8cea87f903","spanId":"53f5cfa2afe8c950","correlationId":"humo-0000-0002","event":"catalog.call","dependency":"api-catalog","operation":"GET /city/{code}","cityCode":"EZE","outcome":"absent","http.status":200,"reason":"cuerpo vacío; el contrato exige 404"}
```

**C. Evento de dominio**

```json
{"@timestamp":"2026-09-24T18:02:55.650Z","level":"INFO","logger":"c.e.r.a.s.CreateReservationTransaction","message":"Reserva creada","thread":"main","service":"flight-reservations","env":"local","version":"0.0.1-SNAPSHOT","instance":"asacco","actorRef":"3c6c5c25f4b6","traceId":"31309f8a37e55d46e79015885c3c502c","spanId":"c6b5d6a85a987dd5","correlationId":"audit-0000-0001","event":"reservation.created","reservationId":1,"userId":1,"reservationVersion":0,"itinerary.origin":"EZE","itinerary.destination":"SCL","passengers":1}
```

Ni el email, ni el nombre, ni el documento, ni la fecha de viaje.

**D. Fallo de autenticación** — el registro que no existía:

```json
{"@timestamp":"2026-09-24T18:04:54.154Z","level":"WARN","logger":"c.e.r.i.s.ProblemDetailAuthenticationHandlers","message":"Pedido rechazado por credencial ausente o inválida","thread":"main","service":"flight-reservations","env":"local","version":"0.0.1-SNAPSHOT","instance":"asacco","traceId":"a26a156c14c178e3a8eed79a2430b975","spanId":"84977f32b129c8a3","correlationId":"bf90dee4-66d0-416f-b028-e339fb2c53c7","event":"auth.failed","reason":"no_token","http.method":"GET","http.status":401}
```

**E. Tarea programada** — con su id sintético por corrida:

```json
{"@timestamp":"2026-09-24T18:05:20.001Z","level":"INFO","logger":"c.e.r.i.a.in.scheduling.OutboxDispatchScheduler","message":"Outbox despachado","thread":"reservations-sched-1","service":"flight-reservations","env":"local","version":"0.0.1-SNAPSHOT","instance":"asacco","correlationId":"job-outbox-relay-3f2a91c4","job":"outbox-relay","event":"outbox.relay.tick","job.runId":"job-outbox-relay-3f2a91c4","dispatched":12,"failed":0,"deferred":3,"duration_ms":310}
```

Sin `traceId`: una tarea programada no nace de una traza.

### Vocabulario de `event`

`http.request` · `catalog.call` · `catalog.retry` · `catalog.fanout` ·
`reservation.created|confirmed|modified|cancelled|replayed|duplicate|version_conflict` ·
`auth.failed` · `auth.denied` · `rate.limited` · `rate.registry_reset` ·
`degraded.served` · `degraded.exhausted` · `cache.degraded` ·
`cache.unreadable` · `cache.local_hit` · `circuit.state` ·
`outbox.relay.tick|deferred|batch|not_attempted|probe|publish_failed|dead_lettered|requeued|purged|metrics_unreadable` ·
`messaging.purge` · `messaging.dlq_unreadable` ·
`consumer.applied|duplicate|out_of_order|retry|dead_lettered` ·
`notification.delivered` · `unhandled.error` · `startup.wiring`

### Tabla de niveles, aplicada

| Nivel | Qué quedó ahí | Cambios respecto del código anterior |
|---|---|---|
| **ERROR** | `unhandled.error`, `outbox.dead_lettered`, `consumer.dead_lettered`, `catalog.call` con 401/403, `http.request` con 5xx | **Bajan a WARN**: los 4xx del catálogo que no son credencial (27) |
| **WARN** | `auth.failed`, `auth.denied`, `rate.limited`, `degraded.*`, `cache.degraded`, `circuit.state`, `catalog.retry`, `consumer.retry`, `startup.wiring` de secretos | **Suben desde INFO**: `auth.denied` (29). **Nuevos**: `auth.failed` (22) |
| **INFO** | Los 4 eventos de dominio, `http.request`, `outbox.relay.tick`, `consumer.applied`, `outbox.requeued`, `outbox.purged`, `reservation.version_conflict`, `startup.wiring` | **Bajan a DEBUG**: `notification.delivered` (duplicaba a `consumer.applied`), `outbox.deferred`, `outbox.not_attempted`, `outbox.probe` (28) |
| **DEBUG** | `catalog.call` feliz, `cache.unreadable`, `outbox.batch`, el tick salteado por circuito | Sin cambios de criterio |
| **TRACE** | `cache.local_hit`, y el aviso de cache degradado **a partir del segundo del mismo pedido** (31) | El primero por pedido queda en WARN |

---

## 4. Catálogo de métricas final

La tabla está sostenida por `MetricsCatalogTest` para los medidores nuevos y
verificada contra la salida real de `/actuator/prometheus` para los nombres de
serie.

### Lo que se agregó

| Nombre | Tipo | Etiquetas | Qué pregunta responde | Alerta |
|---|---|---|---|---|
| `reservations.operations` | contador | `operation`∈{create,get,list,modify,confirm,cancel}, `outcome`∈{ok,duplicate,conflict,not_found,denied,rejected,throttled,degraded,unavailable,error} | Cuántas operaciones de negocio y con qué resultado, separando las dos causas del mismo 409 y del mismo 503 | **2** |
| `reservations.catalog.call` | timer | `outcome`∈{found,absent,unavailable,throttled,integration} | Cuánto tarda **una** llamada al catálogo, no el itinerario entero | panel de la **3** |
| `reservations.requests.degraded` | contador | `dependency`, `route` (plantilla) | Qué porcentaje de las **respuestas** salió degradado | — (panel) |
| `reservations.security.auth.failures` | contador | `reason`∈{no_token,invalid_token,forbidden} | ¿Hay una campaña de credenciales? | **6** |
| `reservations.security.rate_limited` | contador | `method`, `route` (plantilla) | ¿La cuota frena abuso o tráfico legítimo? | — (panel) |
| `reservations.security.quota_registry_reset` | contador | — | ¿Cuántas veces se le regaló una ventana a todos los clientes? | — (panel) |
| `reservations.outbox.dispatch.duration` | timer | — | ¿La vuelta del relay entra en los 5 s del intervalo? | — (panel) |
| `reservations.outbox.metrics.errors` | contador | — | ¿Los gauges del outbox están ciegos? | **9** |
| `reservations.messaging.enabled` | gauge 0/1 | — | ¿Está cableado el publicador real? | **8** |
| `reservations.security.pii.dev_key` | gauge 0/1 | — | ¿Los documentos se cifran con la clave del repositorio? | **7** |
| `reservations.security.jwt.dev_tokens` | gauge 0/1 | — | ¿Se aceptan tokens de desarrollo? | **7** |
| `http.server.requests` | histograma | `method`, `uri`, `status`, `outcome`, `exception` | p95 agregado entre instancias | **3** |

### Lo que existía y se conserva

`reservations.cache.{gets,puts,evictions,errors,size}` ·
`reservations.outbox.{enqueued,claimed,dispatched,failed,deferred,pending,lag,dead,dispatched.retained,dispatch.skipped,dispatch.probes}` ·
`reservations.messaging.{consumed,out-of-order,dead-lettered,dlq.depth}` ·
`reservations.catalog.{errors,retries,budget_exhausted,fanout}` ·
`reservations.degraded.{responses,stale.age,exhausted}` ·
`resilience4j.circuitbreaker.*` · `jvm.*`, `hikaricp.*`, `process.*`

Dos cambios sobre lo que existía: los gauges del outbox devuelven `-1` en lugar
de `0` cuando la base no responde, y la etiqueta `type` de los tres contadores
de mensajería está acotada al vocabulario.

### Etiquetas comunes y cardinalidad

`application`, `env`, `instance` en **todo** medidor, por
`MeterRegistryCustomizer`. El `MeterFilter.maximumAllowableTags` corta en 1.000
series por medidor: se prefiere perder resolución en uno a perder el monitoreo
entero.

**Medido**: 8 buckets por serie de `http_server_requests_seconds_bucket`
(7 SLO + `+Inf`), no ~70.

### Las alertas

| # | Alerta | Umbral | Ventana | Sev. |
|---|---|---|---|---|
| 1 | `CatalogIntegrationBroken` | `rate(reservations_catalog_errors_total{kind="integration"}[5m]) > 0` | 5 min | P1 |
| 2 | `WritesFailing` | tasa de `outcome="error"` sobre las escrituras `> 2 %` | 10 min | P1 |
| 3 | `CreateReservationSlow` | `histogram_quantile(0.95, …POST /v1/reservations) > 4` | 15 min | P1 |
| 4 | `OutboxLagging` | `reservations_outbox_lag_seconds >= 0 and > 300` | 10 min | P1 |
| 5 | `DeadNotifications` | `outbox_dead > 0 or dlq_depth > 0` | 30 min | P2 |
| 6 | `CredentialPressure` | `> 5/s` **y** `> 20×` la línea de base de ayer | 15 min | P2 |
| 7 | `DevelopmentSecretsInUse` | cualquiera de los dos gauges en 1 con `env != local` | 5 min | P1 |
| 8 | `MessagingPublisherDisabled` | `messaging_enabled == 0` con `env != local` | 5 min | P1 |
| 9 | `OutboxMetricsBlind` | `rate(outbox_metrics_errors_total[10m]) > 0` | 10 min | P2 |

Las nueve tienen `summary`, `impact` y **`action`**, y las nueve pasan
`promtool check rules` y evalúan en `health: ok` contra la aplicación real.

---

## 5. Mapa de trazabilidad final

Un `POST /v1/reservations` con `X-Correlation-Id: audit-0000-0001`, salto por
salto. La columna «antes» es el veredicto del §4 de la auditoría.

| # | Salto | Antes | Ahora | Cómo viaja |
|---|---|---|---|---|
| 1 | Frontend → `CorrelationIdFilter` | sí | **sí** | Header validado contra `[A-Za-z0-9_-]{8,64}`, al MDC y a la respuesta |
| 2 | Filtro → `RequestLogFilter` | — | **sí** (nuevo) | Mismo hilo. Escribe la línea de acceso que antes no existía |
| 3 | Filtro → `RateLimitFilter` | sí, sin registro útil | **sí** | MDC. Ahora con `event=rate.limited` y contador |
| 4 | Filtro → entry point del 401 | sí en el MDC, **no** en el log | **sí** | `event=auth.failed` en `WARN`, con `reason` |
| 5 | Controlador → caso de uso → persistencia | sí | **sí** | Mismo hilo de Tomcat |
| 6 | Caso de uso → **fan-out del catálogo** | **SE PIERDE** | **sí** | `ContextSnapshot.setThreadLocals()` + `MdcThreadLocalAccessor`. *Verificado en la corrida real: la línea de `RestCityCatalogClient` sale desde `thread: virtual-103` con el id puesto* |
| 7 | Fan-out → **HTTP saliente al catálogo** | **SE PIERDE** | **sí** | `X-Correlation-Id` por interceptor + `traceparent` por la instrumentación |
| 8 | Caso de uso → `DegradationRecorder` → header | sí | **sí** | `ThreadLocal` propio; el log de acceso lo lee ya completo |
| 9 | Caso de uso → fila de `auditoria` | sí | **sí** | Sin cambios |
| 10 | Caso de uso → `EventEnvelope` | sí | **sí** | Campo del envelope |
| 11 | Outbox → **`@Scheduled` del relay** | **SE PIERDE** | **sí** | `MdcTaskDecorator` → `job-outbox-relay-<8hex>` |
| 12 | Relay → publicación | se recupera | **sí** | Se pisa con el del pedido que originó el hecho |
| 13 | Publicación → **resto de la vuelta** | **SE PIERDE** | **sí** | Se **restituye** el MDC anterior en vez de borrarlo |
| 14 | Relay → broker → consumidor | se recupera, salvo cuerpo ilegible | **sí, también con cuerpo ilegible** | Propiedades AMQP antes de parsear, y el envelope después |
| 15 | Consumidor → caso de uso → entrega | sí | **sí** | Mismo hilo del contenedor |
| 16 | Consumidor → cola de reintento | sí | **sí** | El envelope viaja en el cuerpo |
| 17 | Consumidor → dead letter | sí en el cuerpo, **no** en el log | **sí en los dos** | Ver salto 14 |
| 18 | `POST` → **purga de mensajería** | **SE PIERDE** | **sí** | `job-messaging-purge-<8hex>` |
| 19 | Operador → `/actuator/outbox` → reencolado | **SE PIERDE**, y sin actor | **sí, y con actor** | `OpsActor.current()` → `actorRef` |
| 20 | Contenedor → dispatch `ERROR` (`/error`) | **SE PIERDE** | **sí** | `setDispatcherTypes(REQUEST, ASYNC, ERROR)` |

**De 19 saltos, 8 perdían el id. Ahora no lo pierde ninguno.**

### El `correlationId` y el `traceId` conviven

| | `correlationId` | `traceId` |
|---|---|---|
| Cobertura | 100 % de los pedidos | sólo los muestreados (1.0 local, 0.1 producción) |
| Dónde vive además del log | Header de respuesta, columna `correlation_id` de `auditoria`, campo del `EventEnvelope` | Tempo y el MDC |
| Vida útil | La de la auditoría (años) | La de la traza (días) |

El pivote es directo y está **provisionado**: el datasource de Loki tiene un
`derivedField` sobre `traceId` que abre la traza en Tempo desde el mismo
Grafana. *Verificado: el `traceId` de una línea de log resuelve en
`/api/traces/{id}` de Tempo con sus spans.*

---

## 6. Lo que no se remedia en este paso

| Qué | Por qué | Qué haría falta |
|---|---|---|
| **El `DEBUG` de Spring y de Hibernate vuelca modelo y cuerpos de respuesta** (`Writing [ReservationResponse[…userId=ana.perez@example.com…]]`, entidades JPA enteras) | No se arregla escribiendo mejor nuestras líneas: es el framework logueando su propio trabajo. El contrato de la API expone el email como `userId`, así que el cuerpo de la respuesta **es** dato personal | Está mitigado por configuración —`logging.level` deja esos paquetes en su default, que no es `DEBUG`— y el gate lo excluye explícitamente. Cerrarlo de verdad pide o un filtro de Logback que redacte por patrón sobre todos los loggers, o cambiar el contrato para que `userId` sea el id interno |
| **`reservations.security.metrics-scrape-open`** abre `/actuator/prometheus` sin token | El diseño asume que el scrape «alcanza el 9090 y listo» y la cadena de seguridad también cubre el contexto de gestión. Las alternativas eran un token de larga vida escrito en el repositorio o abrirlo sin condición | Apagado por defecto y defendible **sólo** mientras el puerto de gestión no se publique. En un entorno real, el scrape se autentica: `authorization.credentials_file` en `prometheus.yml` con un token de servicio rotado por el gestor de secretos |
| **No hay `promtool test rules` con series sintéticas** | `promtool check rules` valida la sintaxis y `AlertRulesTest` el contrato; lo que falta es probar que cada alerta **dispara cuando debe** | Un `docker/prometheus/rules/reservations_test.yml` con series sintéticas por alerta. Es media hora y no cambia ningún código |
| **No hay dashboards de Grafana versionados** | Un JSON exportado a mano envejece mal y nadie lo revisa en un diff. Lo que este repositorio versiona son las **alertas**, que sí son una decisión | Si los paneles se vuelven un artefacto compartido, generarlos desde código (Grafonnet / Foundation SDK) en vez de exportarlos |
| **El span `outbox.publish` no cuelga con un span link del pedido original** | El §5.3 lo describe bien —entre el `POST` y el despacho pasan hasta 5 s y el span servidor ya cerró—, y la instrumentación automática no lo hace sola | Agregar `traceparent` como campo del `EventEnvelope` y abrir el span consumidor como hijo remoto. Es un cambio de contrato del mensaje y merece su propio paso |
| **`instance` como etiqueta común** | Con autoscaling agresivo y pods efímeros es churn de series | Está anotado con su umbral (~50 instancias) y su salida: sacarla de las etiquetas comunes y dejársela al scraper como `pod` |
| **No hay alerta de latencia para el `PUT`** | Decisión del diseño, y sigue siendo correcta: el objetivo es 4,5 s y el peor caso medido es 7,1 s. Una alerta contra un techo que el sistema no cumple suena siempre | El bucket en 4,5 s ya está en el histograma: la alerta se escribe el día que los 2,6 s de la lectura previa se recorten |

---

## 7. Comandos de verificación

### Levantar el entorno

```bash
cp .env.example .env
docker compose up -d
```

```bash
docker compose --profile observability up -d
```

Con el profile quedan `Prometheus` en `127.0.0.1:9091`, `Grafana` en `:3000`
(anónimo, sólo lectura), `Loki` en `:3100` y `Tempo` en `:4318`. Los cuatro son
OSS y corren enteros en local: ninguno pide una cuenta.

### Correr el build y los tests

```bash
./mvnw verify
```

`verify` corre los unitarios, los de integración contra Testcontainers y el
gate de datos sensibles. Sin Docker, `./mvnw test` corre sólo los unitarios.

### Levantar la aplicación contra el stack

```bash
METRICS_SCRAPE_OPEN=true TRACING_EXPORT_ENABLED=true LOG_FORMAT=json ./mvnw spring-boot:run > docker/logs/app.log
```

Las tres variables están apagadas por defecto: la aplicación arranca y los
tests pasan sin ninguno de los cinco contenedores, igual que arranca sin Redis,
sin broker y sin catálogo.

### Seguir un pedido de punta a punta

```bash
curl -i -H 'X-Correlation-Id: audit-0000-0001' -H "Authorization: Bearer $TOKEN" http://localhost:8080/v1/reservations
```

Los cinco lugares donde ese valor tiene que aparecer:

```bash
grep audit-0000-0001 docker/logs/app.log | jq -r '[.level, .event, .logger] | @tsv'
```

```bash
docker compose exec postgres psql -U reservations -d reservations -c "SELECT accion, correlation_id FROM auditoria ORDER BY id DESC LIMIT 5"
```

```bash
docker compose exec postgres psql -U reservations -d reservations -c "SELECT type, correlation_id FROM outbox_message ORDER BY sequence DESC LIMIT 5"
```

```bash
curl -sG 'http://127.0.0.1:3100/loki/api/v1/query_range' --data-urlencode 'query={service="flight-reservations"} | json | correlationId="audit-0000-0001"' | jq -r '.data.result[].values[][]'
```

Y del log a la traza, que es el pivote del §5.4:

```bash
curl -s "http://127.0.0.1:3200/api/traces/$(grep -m1 audit-0000-0001 docker/logs/app.log | jq -r .traceId)" | jq '[.batches[].scopeSpans[].spans[].name]'
```

### Verificar que no haya PII en la salida

```bash
./scripts/pii-log-gate.sh target
```

Corre solo dentro de `./mvnw verify`, en la fase `verify`, después de failsafe.
Revisa los ~1.100 registros propios que la suite escribe contra siete patrones
—email, JWT, `Authorization: Bearer`, la marca de la clave de cifrado de
desarrollo, apellidos y documentos de las fixtures, y el `Detail: Key (…)=(…)`
de PostgreSQL— y falla el build con una sola coincidencia.

### Verificar las alertas y los buckets

```bash
docker compose exec prometheus promtool check rules /etc/prometheus/rules/reservations.yml
```

```bash
curl -s 'http://127.0.0.1:9091/api/v1/rules' | jq -r '.data.groups[].rules[] | "\(.name)\t\(.labels.severity // "-")\t\(.health)"'
```

```bash
curl -s http://localhost:9090/actuator/prometheus | grep -oE 'http_server_requests_seconds_bucket.*le="[^"]+"' | grep -oE 'le="[^"]+"' | sort -u
```

Ocho valores: `0.2`, `0.5`, `1.0`, `2.0`, `4.0`, `4.5`, `8.0` y `+Inf`. Si
aparecieran decenas, `percentiles-histogram` se coló de vuelta.

---

## 8. El volumen, medido

**Medido, no estimado**: 535 bytes de promedio por registro propio en `INFO`+,
sobre los 67 que produce un arranque más las 15 pruebas de `ReservationApiIT`.

El diseño calculaba ~450 bytes. Los ~85 de diferencia son `traceId` y `spanId`
—que el diseño listaba como obligatorios y no contaba en el cálculo— más
`actorRef`.

### Líneas por operación

| Operación | Antes | Ahora | Cuáles |
|---|---|---|---|
| `POST /v1/reservations` feliz, 8 ciudades | **1** | **2** | `http.request` + `reservation.created`. Las 8 consultas al catálogo no producen ninguna línea `INFO` |
| `POST` degradado | 2–4 | **3–4** | las dos anteriores + `degraded.served` por dependencia |
| `GET` por id / listado | **0** | **1** | `http.request` |
| `PUT` / confirmación / cancelación | 2 | **2** | `http.request` + el evento de dominio |
| 401 / 403 / 409 / 429 | **0** | **2** | `http.request` + el evento de seguridad o de conflicto |
| Vuelta del relay con trabajo | 1 | **1** | `outbox.relay.tick` |
| Vuelta del relay sin trabajo | 0 | **0** | el guardia del tick |
| **Vuelta del relay con el broker caído y backlog** | **3** | **0** | hallazgo 28: el guardia mira `dispatched + failed` |
| Mensaje consumido | 2 | **1** | `consumer.applied`; `notification.delivered` bajó a `DEBUG` |
| **`POST` con Redis caído** | **~16** | **1** | hallazgo 31: un aviso por pedido |

### Qué se bajó de nivel para compensar

El esquema agrega `http.request`, que es la línea más frecuente del sistema:
+100.000/día sobre el escenario de volumen del diseño. Para compensar, cuatro
cosas bajaron de nivel y una cambió de guardia:

| Qué bajó | De → a | Cuánto ahorra/día |
|---|---|---|
| `notification.delivered` (duplicaba a `consumer.applied`) | INFO → DEBUG | **−10.500** |
| `outbox.deferred` («N mensajes liberados») | INFO → DEBUG | −8.000 en régimen normal; **−720.000 durante una caída del broker** |
| `outbox.not_attempted` y `outbox.probe` | INFO → DEBUG | variable; es una línea **por mensaje** durante una caída |
| El aviso de cache degradado a partir del segundo del mismo pedido | WARN → TRACE | **−1,5 M durante una caída de Redis** (era 12 veces el presupuesto diario completo) |
| El guardia del tick del relay | `total()` → `dispatched + failed` | **−2.160/hora** durante una caída del broker |

### El presupuesto

| Fuente | Líneas/día |
|---|---|
| `http.request` | 100.000 |
| Eventos de dominio | 10.500 |
| Vueltas del relay con trabajo | ~8.000 |
| Mensajes consumidos | 10.500 |
| `WARN` + `ERROR` (~1 % de los pedidos) | ~1.500 |
| Arranque y purga | ~50 |
| **Total** | **~130.500** |

A 535 bytes: **~70 MB/día**, **~2,1 GB/mes sin comprimir**, ~210 MB/mes
comprimido 10:1. Con 30 días de retención cabe en el disco de una notebook y en
el free tier de cualquier SaaS con dos órdenes de magnitud de margen.

**Y ahora el día malo también está presupuestado**, que es lo que el §8 del
diseño no tenía:

| Escenario | Antes | Ahora |
|---|---|---|
| Caída de Redis, 100.000 pedidos | ~1,6 M líneas (12× el presupuesto **diario**) | ~100.000 (1 por pedido) |
| Caída del broker de 1 h con backlog | 2.160 líneas + 1 por mensaje del lote | **0** del tick; la transición del circuito se loguea una vez |
| Caída del catálogo, `POST` con 8 ciudades | hasta ~48 `WARN` por `POST` | hasta ~9: uno por ciudad más el del presupuesto agotado |

El registro JSON pesa ~3 veces lo que la línea de texto anterior (~180 bytes).
Eso es lo que se paga, y se paga por tres cosas concretas: que
`correlationId`, `event` y `reservationId` sean campos indexados y no una
subcadena que hay que parsear con una expresión regular distinta por formato de
mensaje; que el esquema sobreviva a un cambio de redacción; y que un salto de
línea en un cuerpo de error de un tercero deje de poder fabricar un registro
falso.
