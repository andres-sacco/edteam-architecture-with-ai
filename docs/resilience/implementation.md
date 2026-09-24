# Implementación de la resiliencia

> Salida del prompt [18 — Implementación de la resiliencia](../prompts/18-implementacion-de-resiliencia.md).
> Entradas: el [diseño](design.md) (§16) y la [auditoría](audit.md) (§17).

El orden de trabajo es el de §6 de la auditoría —primero lo que le miente al usuario,
después lo que pierde notificaciones, después lo que amplifica la caída hacia afuera,
después lo que impide enterarse— y no el de facilidad de arreglo.

- §1 [Trazabilidad hallazgo → mitigación → test](#1-trazabilidad)
- §2 [Archivos por paquete](#2-archivos-por-paquete)
- §3 [Resiliencia final por dependencia](#3-resiliencia-final-por-dependencia)
- §4 [Presupuesto de latencia final](#4-presupuesto-de-latencia-final)
- §5 [Lo que no se remedia en este paso](#5-lo-que-no-se-remedia-en-este-paso)
- §6 [Comandos de verificación](#6-comandos-de-verificación)

---

## 1. Trazabilidad

| # | Hallazgo | Mitigación implementada | Archivos | Test que lo verifica |
|---|---|---|---|---|
| **5** | Se sirve *stale* negativo: un «no existe» viejo rechaza una reserva válida con `400` no reintentable | La ventana de gracia es **sólo para positivos**. Los negativos se guardan con TTL = `negative-cache-ttl` y **sin** ventana, así que un negativo vencido estructuralmente no existe en el cache; con el origen caído sale `503` + `Retry-After` | `CachingAirportCatalog` | `CachingAirportCatalogTest#neverServesAStaleNegative` |
| **4** | El `catch (RuntimeException)` del *stale* tapa un fallo permanente (credencial vencida) hasta 2 h 30 m | El fallback sólo se aplica a lo que el resolutor marcó `UNAVAILABLE`. `AirportCatalogIntegrationException` se cuenta (`reservations.catalog.errors{kind=integration}`) y **sube**; sale con código propio `AIRPORT_CATALOG_ERROR` | `CatalogCityResolver`, `CachingAirportCatalog`, `ApiErrorCode`, `ReservationExceptionHandler` | `CachingAirportCatalogTest#doesNotHideAPermanentFailure`, `CircuitBreakingCityCatalogClientTest#aPermanentFailureNeverOpensTheCircuit` |
| **1** | El *stale-while-error* es el camino **lento**: cada ciudad, en cada pedido, paga los intentos completos antes de caer al valor viejo (≈86 s por `POST`) | Dos cortes: el **circuito** (la llamada cuesta microsegundos) y la sonda `originAvailable` en el cache, que con el circuito abierto ni baja por la cadena. Más el **fan-out en paralelo con presupuesto** | `CircuitBreakingCityCatalogClient`, `Circuit`, `CachingAirportCatalog`, `BudgetedCityCatalogFanout`, `AdapterConfiguration` | `CatalogResilienceIT#aHungCatalogWithAWarmCacheStaysInsideTheBudget`, `CachingAirportCatalogTest#doesNotCallTheOriginWhenItIsKnownToBeDown` |
| **3** | Con Redis caído desaparece el *stale-while-error*: la ventana de gracia vive en el `CacheStore` que se cayó | L1 en memoria para el prefijo `catalog:city:`, **escrito siempre** (no sólo en emergencia) para que esté caliente cuando haga falta | `CircuitBreakingCacheStore`, `CacheConfiguration` | `CircuitBreakingCacheStoreTest#theCityFallbackIsWrittenThrough` |
| **2** | El circuito de Redis no puede abrirse nunca: `RedisCacheStore` atrapaba toda `RuntimeException` | `RedisCacheStore` **relanza**; la degradación al origen la decide el decorador, que es quien necesita ver el fallo para contarlo | `RedisCacheStore`, `CircuitBreakingCacheStore` | `CircuitBreakingCacheStoreTest#aHardRedisOutageOpensTheCircuit`, `RedisCacheStoreTest#rethrowsOnReadFailure` |
| **6** | `retry-ceiling: 6h` es configuración muerta: `max-attempts: 10` se agota en 20 min | `max-attempts` sube a **80**, con lo que el techo de tiempo vuelve a ser el corte que manda. La aplicación **avisa al arrancar** si los dos vuelven a divergir | `OutboxProperties`, `AdapterConfiguration`, `application.yml` | `OutboxRetryBudgetTest#theAttemptBudgetCoversTheTimeCeiling` |
| **9** | `markDispatched`/`markFailed` no verifican la propiedad del reclamo; el lease es más corto que el peor caso de un tick | `AND status = 'IN_FLIGHT'` en las dos, más el chequeo de estado antes de contar el intento. El lease se **deriva** de `batch-size × confirm-timeout` | `JdbcEventOutbox`, `OutboxProperties`, `AdapterConfiguration` | `JdbcEventOutboxIT$ClaimOwnership` (4 tests) |
| **7** | La sonda de semiabierto gasta intentos reales, y siempre sobre los más viejos | `dispatchProbe()`: reclama **uno al azar** (`ORDER BY random()`) y, si falla, lo **libera** sin contarle el intento | `EventOutboxPort`, `JdbcEventOutbox`, `OutboxDispatcherService`, `OutboxDispatchScheduler` | `OutboxDispatcherServiceTest#aFailedProbeReleasesTheMessage`, `JdbcEventOutboxIT#theProbeSpreadsTheRisk` |
| **8** | Un mensaje devuelto por falta de binding abriría el circuito del broker y frenaría lo que salía bien | `EventRoutingException` separada del `nack`, y el orden de chequeo invertido (el *return* antes del ack). El clasificador la marca `PERMANENT`: no cuenta | `EventRoutingException`, `RabbitEventPublisher`, `Failures` | `FailuresTest#aReturnedMessageNeverOpensTheCircuit` |
| **10** | Sin bulkhead y con threads virtuales, nada limita las llamadas en vuelo contra `api-catalog` | Bulkhead de 50 permisos con **espera cero**, entre el circuito y el retry | `BulkheadCityCatalogClient`, `ResilienceConfiguration` | `BulkheadCityCatalogClientTest` (3 tests) |
| **11** | No hay techo del lado de la base: sin `statement_timeout`, sin `@Transactional(timeout)`, `connection-timeout: 3000` | `statement_timeout=2000` en el **driver**, `@Transactional(timeout)` en todos los métodos transaccionales, `connection-timeout: 1000`, y `503 DATABASE_UNAVAILABLE` + `Retry-After: 1` en lugar de `500` | `application.yml`, los seis servicios transaccionales, `ReservationExceptionHandler`, `ApiErrorCode` | `DatabaseCeilingsIT` (2 tests), `HexagonalArchitectureTest#transactionalMethodsDeclareATimeout` |
| **12** | Peor caso real ≈ 90 s (once ciudades, no ocho), por encima del `graceful shutdown` de 25 s | Presupuesto duro del itinerario (1,6 s) con fan-out en hilos virtuales, calculado sobre **once** ciudades | `BudgetedCityCatalogFanout`, `CatalogDeadline`, `AirportCatalogProperties` | `BudgetedCityCatalogFanoutTest`, `LatencyBudgetTest#theWorstCaseFitsInsideTheGracefulShutdown` |
| **20** | La `Idempotency-Key` protege la escritura pero no la carga: el reintento del usuario duplica las llamadas al catálogo | La clave se resuelve **antes** de validar el itinerario, en una transacción de sólo lectura con techo de 1 s | `ReservationIdempotencyLookup`, `CreateReservationService` | `IdempotentWriteProtectionTest` (4 tests) |
| **13** | No hay métrica de reintentos ni de *stale*: sólo `log.warn` | `reservations.catalog.retries{result}`, `reservations.degraded.responses{dependency,reason}`, `reservations.degraded.stale.age`, `reservations.degraded.exhausted`, `reservations.catalog.errors{kind}`, `reservations.catalog.budget_exhausted`, `reservations.outbox.dispatch.skipped|probes`, más el header **`X-Degraded`** | `DegradationRecorder`, `Degradation`, `DegradationHeaderFilter`, `RetryingCityCatalogClient`, `CatalogCityResolver`, `BudgetedCityCatalogFanout` | `CachingAirportCatalogTest#everyDegradedResponseLeavesATrace`, `RetryingCityCatalogClientTest#publishesRetryMetrics`, `CatalogResilienceIT` |
| **14** | Las métricas no son raspables desde afuera | `micrometer-registry-prometheus` + `prometheus` en `management.endpoints.web.exposure.include`, en el puerto de gestión que **sigue sin publicarse** | `pom.xml`, `application.yml`, `ResilienceConfiguration` (binders de resilience4j) | `ResilienceObservabilityIT` (4 tests) |
| **15** | El backoff del consumidor es fijo y sin jitter | Backoff exponencial con jitter, escrito como vencimiento **por mensaje**; la TTL de la cola pasa a ser el techo (`max-retry-delay: 10m`) | `ReservationEventListener`, `MessagingConfiguration`, `MessagingProperties` | `ReservationEventListenerBackoffTest` (5 tests) |
| **16** | La cola de espera del consumidor no tiene cota | `x-max-length` + `reject-publish`, con el mismo criterio que la principal | `MessagingConfiguration`, `MessagingProperties` | `ConsumerResilienceIT#theRetryQueueIsBounded` |
| **17** | `RabbitTemplate` acumula `CorrelationData` sin confirmar | Al vencer el confirm se barren los pendientes abandonados (`getUnconfirmed`), en el mismo camino que los produce | `RabbitEventPublisher` | — (ver §5) |
| **18** | Ventana `COUNT_BASED` sin caducidad: en tráfico bajo el circuito decide con datos de hace horas | La ventana se descarta entera si pasó `window-max-age` sin llamadas, y **sólo** con el circuito cerrado | `Circuit`, `CircuitBreakerProperties` | `CircuitTest#theWindowExpires`, `#anOpenCircuitIsNeverReset` |
| **19** | El mínimo de 20 llamadas tarda en alcanzarse con el cache caliente: el circuito no cubre los primeros minutos | Se acepta, y se compensa con las dos piezas que sí cubren ese hueco: el **presupuesto** acota el peor caso a 1,6 s aunque el circuito no haya abierto, y la sonda `originAvailable` hace que en cuanto abra ninguna ciudad vuelva a pagar el viaje | `BudgetedCityCatalogFanout`, `CachingAirportCatalog` | `BudgetedCityCatalogFanoutTest#anItineraryAgainstAHungCatalogRespectsTheBudget` |
| **21** | El javadoc del retry declara 6,5 s de peor caso; el real era 7,8 s | El peor caso se **deriva** de las propiedades (`attemptCost()`, `worstCasePerCity()`, `Retry#worstCaseBackoff()`) en lugar de escribirse | `AirportCatalogProperties`, `RetryingCityCatalogClient` | `LatencyBudgetTest#theWorstCasePerCityIsDerived`, `RetryingCityCatalogClientTest#worstCaseBackoffIsDerivedAndNotWritten` |
| **22** | El diseño factura como Redis un rate limit que es en memoria | Corregido en el presupuesto, con un test estructural que lo sostiene | `LatencyBudgetTest` | `LatencyBudgetTest#theRateLimitDoesNotCostARedisRoundTrip` |

---

## 2. Archivos por paquete

### `pom.xml`
`resilience4j-circuitbreaker`, `-bulkhead` y `-micrometer` **2.2.0** (Apache 2.0, versión fija);
`micrometer-registry-prometheus` (versión del BOM). **No** se agrega
`resilience4j-spring-boot3`: trae AOP y las anotaciones que este diseño no quiere que
puedan aparecer en un servicio de aplicación.

### `application` (nuevos)
| Archivo | Qué es |
|---|---|
| `exception/AirportCatalogThrottledException` | El `429`: cuenta para el circuito, no se reintenta |
| `exception/EventPublisherUnavailableException` | «No se intentó»: el intento no se gasta |
| `exception/EventRoutingException` | Mensaje devuelto: topología nuestra, no salud del broker |
| `service/ReservationIdempotencyLookup` | Resuelve la clave antes de validar el itinerario |

### `application` (modificados)
`port/out/AirportCatalogPort` (firma en bloque), `port/out/EventOutboxPort` (`pollProbe`),
`port/in/DispatchPendingNotificationsUseCase` (`dispatchProbe`),
`service/AirportExistenceValidator`, `service/CreateReservationService`,
`service/OutboxDispatcherService`, y `@Transactional(timeout)` en los seis servicios
transaccionales.

### `infrastructure/resilience` (nuevo paquete)
| Archivo | Qué es |
|---|---|
| `FailureKind`, `FailureClassification` | Los dos ejes: qué cuenta y qué se reintenta |
| `Failures` | **El único lugar** donde se clasifica un fallo, por dependencia |
| `Circuit` | Envoltorio: ventana que caduca + logging de transiciones + apagado por configuración |
| `Degradation` | Marca de degradación del pedido, que el borde convierte en `X-Degraded` |
| `DegradationRecorder` | Métrica + log + marca, juntos, para que no se pueda olvidar uno |

### `infrastructure/adapter/out/airport`
Nuevos: `CityResolution`, `CityResolver`, `BudgetedCityCatalogFanout`,
`catalog/CatalogDeadline`, `catalog/CircuitBreakingCityCatalogClient`,
`catalog/BulkheadCityCatalogClient`, `catalog/CatalogCityResolver`.
Modificados: `CachingAirportCatalog`, `StaticAirportCatalog`,
`catalog/RetryingCityCatalogClient`, `catalog/RestCityCatalogClient`.
Eliminado: `catalog/CatalogAirportCatalog` (lo reemplaza `CatalogCityResolver`, que
resuelve por conjunto y traduce el rechazo de la librería).

### `infrastructure/cache`
Nuevo: `CircuitBreakingCacheStore`. Modificados: `CacheStore` (`getAll`),
`RedisCacheStore` (relanza, `MGET`).

### `infrastructure/adapter/out/messaging`
Nuevo: `CircuitBreakingEventPublisher`. Modificado: `RabbitEventPublisher`.

### `infrastructure/adapter/out/outbox`
Modificados: `JdbcEventOutbox`, `MeteredEventOutbox`.

### `infrastructure/adapter/in`
Nuevo: `rest/DegradationHeaderFilter`. Modificados: `rest/ReservationExceptionHandler`,
`rest/dto/ApiErrorCode`, `scheduling/OutboxDispatchScheduler`,
`messaging/ReservationEventListener`.

### `infrastructure/config`
Nuevos: `ResilienceConfiguration`, `CircuitBreakerProperties`.
Modificados: `AdapterConfiguration` (la cadena explícita), `CacheConfiguration`,
`MessagingConfiguration`, `AirportCatalogProperties`, `CacheProperties`,
`MessagingProperties`, `OutboxProperties`, `security/SecurityConfiguration`.

### `src/test`
Nuevos: `CatalogResilienceIT`, `ResilienceObservabilityIT`, `DatabaseCeilingsIT`,
`resilience/FailuresTest`, `resilience/CircuitTest`,
`adapter/out/airport/BudgetedCityCatalogFanoutTest`,
`adapter/out/airport/catalog/CircuitBreakingCityCatalogClientTest`,
`adapter/out/airport/catalog/BulkheadCityCatalogClientTest`,
`cache/CircuitBreakingCacheStoreTest`, `config/LatencyBudgetTest`,
`config/OutboxRetryBudgetTest`, `adapter/in/messaging/ReservationEventListenerBackoffTest`,
`application/service/IdempotentWriteProtectionTest`.
Reglas nuevas de ArchUnit: `resilienceStaysInInfrastructure`,
`transactionalMethodsDeclareATimeout`, `onlyIdempotentReadsAreRetried`.

---

## 3. Resiliencia final por dependencia

| Dependencia | Timeout | Circuito | Reintentos | Fallback | Métrica |
|---|---|---|---|---|---|
| **api-catalog** | connect 300 ms + read 700 ms = **1 s por intento**; presupuesto del itinerario **1,6 s**; bulkhead 50 sin espera | Ventana 50, mín. 20, 50 % de fallo o 60 % > 900 ms → abre **5 s**, semiabierto 4, transición automática, ventana vencida a 10 min. **No** cuentan 404, integración ni presupuesto agotado | **2 intentos**, backoff 100 → 200 ms con jitter sobre la mitad superior, consciente del presupuesto. **No** reintenta 429, integración ni bulkhead lleno | *Stale* **positivo** dentro de 2 h + `X-Degraded`; *stale* negativo **nunca**; sin nada guardado, `503` + `Retry-After: 5` | `resilience4j_circuitbreaker_{state,calls}{name=catalog}`, `reservations.catalog.retries{result}`, `reservations.catalog.errors{kind}`, `reservations.catalog.budget_exhausted`, `reservations.degraded.responses{dependency=airport-catalog,reason}` |
| **PostgreSQL** | `connection-timeout` **1 s**, `statement_timeout` **2 s** en el driver, `@Transactional(timeout=1)` en escrituras y `2` en lecturas | **Ninguno**, a propósito: no hay nada mejor que hacer sin base | **Ninguno**. La única repetición admitida es la del cliente con la misma `Idempotency-Key` | **No hay.** `503 DATABASE_UNAVAILABLE` + `Retry-After: 1`; el health indicator `db` sigue encendido | `hikaricp.*`, `reservations.degraded.exhausted` |
| **Redis** | `timeout`/`connect-timeout` 200 ms | Ventana 100, mín. 30, 50 % o 60 % > 150 ms → abre **10 s**, semiabierto 5, ventana vencida a 10 min | **Ninguno**: el «reintento» de un cache es el origen | Prefijo `catalog:city:` → L1 en memoria (10 000 entradas, escritura permanente). El resto → **miss** | `resilience4j_circuitbreaker_*{name=redis}`, `reservations.cache.{gets,puts,errors}` (medidas **por fuera** del circuito) |
| **RabbitMQ** | connect 2 s + confirm 5 s | Ventana 20, mín. **5**, 60 % o 60 % > 2 s → abre **60 s**, semiabierto 2, ventana vencida a 30 min. **No** cuenta el mensaje devuelto | **Ninguno en proceso**: el outbox es el reintento (5 s → 5 m, 80 intentos, techo 6 h) | El mensaje se queda en el outbox **sin gastar intento**. `OPEN` → el tick no se dispara; `HALF_OPEN` → una sonda al azar que tampoco gasta intento | `resilience4j_circuitbreaker_*{name=broker}`, `reservations.outbox.{pending,lag,dead}`, `reservations.outbox.dispatch.{skipped,probes}` |
| **Notificaciones** (consumidor) | — | **Ninguno** (no lo llamamos: entre medio hay una cola) | TTL por mensaje, exponencial con jitter 30 s → 10 m, 5 vueltas, después DLQ. Idempotente por `messageId` | La DLQ, drenable por `POST /actuator/messaging-dlq/replay` | `reservations.messaging.{consumed,dead-lettered}`, `reservations.messaging.dlq.depth` |
| **IdP / JWKS** | el de Nimbus | **Ninguno**: aceptar un token sin validar no es degradar | Los de Nimbus | **No hay, y no debe haberlo** | `http.client.requests` |

---

## 4. Presupuesto de latencia final

Todos los renglones salen de un techo configurado; el cálculo lo genera `LatencyBudgetTest`
y falla el día que alguien cambie un timeout sin rehacer la cuenta.

### `POST /v1/reservations`, peor caso (once ciudades, catálogo colgado, cache poblado)

| Paso | Auditoría | Ahora | De dónde sale |
|---|---|---|---|
| Correlación + JWT | ~0 ms | ~0 ms | clave JWKS cacheada |
| Rate limit | ~0 ms | ~0 ms | en memoria, no Redis (hallazgo 22) |
| Lectura de cache de ciudades | 2 200 ms (once, en serie) | **200 ms** | un solo `MGET` |
| **Validación del itinerario** | **85 800 ms** | **1 600 ms** | presupuesto duro + fan-out en hilos virtuales |
| Escritura de cache | 0 ms | 200 ms | agrupada |
| Conexión del pool | 3 000 ms | **1 000 ms** | `hikari.connection-timeout` |
| Transacción | **sin techo** | **1 000 ms** | `@Transactional(timeout = 1)` + `statement_timeout` |
| Serialización | 200 ms | 100 ms | |
| **Total** | **≈ 91 s** | **≈ 4,1 s** | **22× menos** |
| Pedidos al catálogo | 33 | **≤ 11**, y **0** con el circuito abierto | |

Con el circuito del catálogo **abierto**, el mismo pedido cuesta **≈ 1,5 s**: la validación
entera se resuelve contra el valor guardado sin tocar la red.

### `PUT /v1/reservations/{id}`, peor caso

| Paso | Auditoría | Ahora |
|---|---|---|
| JWT + rate limit | ~0 ms | ~0 ms |
| Cache de versión | 200 ms | 200 ms |
| `findById` fuera de transacción | 3 000 ms + sin techo | 1 000 ms + 2 000 ms |
| Validación del itinerario | 88 000 ms | **1 600 ms** |
| Escritura de cache | — | 200 ms |
| Transacción de escritura | 3 000 ms + sin techo | 1 000 ms + 1 000 ms |
| Serialización | 200 ms | 100 ms |
| **Total** | **≈ 94 s** | **≈ 7,1 s** |

### Contra las referencias de la auditoría

| Referencia | Valor | Antes | Ahora |
|---|---|---|---|
| Objetivo del diseño §6 | `POST` ≤ 4 s | 23× por encima | **4,1 s**: 100 ms por encima (ver §5) |
| Objetivo del diseño §6 | `PUT` ≤ 4,5 s | 21× por encima | **7,1 s**: sigue por encima (ver §5) |
| `graceful shutdown` | 25 s | 3,6× más corto que el pedido | el pedido entra con margen |
| Corte de proxy típico | 60 s | el proxy cortaba primero | ya no |

---

## 5. Lo que no se remedia en este paso

| # | Qué queda | Por qué | Qué haría falta |
|---|---|---|---|
| **12 (parcial)** | El `POST` cierra en **4,1 s** contra el objetivo de **4 s**, y el `PUT` en **7,1 s** contra **4,5 s** | Los 100 ms del `POST` son la escritura de cache, que el diseño lista como el tercer recorte disponible («si el presupuesto se agotó, el `put` se hace sin esperar») y todavía se hace en línea. Los 2,6 s del `PUT` son la lectura previa: el cálculo del diseño no facturaba la obtención de la conexión **dos veces**, ni el techo de la consulta de `findById` | Escritura de cache asincrónica (`put` sin esperar) y reutilizar la conexión entre la lectura previa y la transacción, o bajar `statement_timeout` para la lectura con `@EntityGraph`. Los dos techos están declarados como constantes en `LatencyBudgetTest`, así que bajarlos es cambiar un número y ver qué falla |
| **15 (parcial)** | El backoff del consumidor crece y tiene jitter, pero sigue sobre **una sola** cola de espera | Con una cola única hay bloqueo de cabeza de línea: un mensaje con vencimiento largo adelante retrasa a los que tiene detrás. Está acotado —el jitter se sortea sobre el último cuarto, así que el retraso máximo es el 25 % del escalón— pero no eliminado. La alternativa que lo elimina (tres colas de TTL distinta) obliga a cambiar el `RETRY_EXCHANGE` de *fanout* a *direct*, que es una migración de topología sobre un broker con mensajes en vuelo | Declarar `notifications.reservation-events.retry.{fast,medium,slow}`, pasar el exchange a *direct* y coordinar el despliegue con el consumidor. Es un cambio de infraestructura, no de código |
| **17** | Los confirms abandonados se barren **cuando vence un confirm**, no de forma periódica | El barrido ocurre exactamente cuando hay algo que barrer y no agrega un scheduler más. El hueco es el caso «el broker dejó de confirmar y además dejó de recibir tráfico»: ahí no hay vencimiento nuevo que dispare el barrido y los pendientes quedan hasta la próxima publicación | Una tarea programada que llame a `checkForMissingConfirms` cada N minutos. No se agregó porque el relay publica cada 5 s: para que el hueco importe, el outbox tiene que estar vacío — y entonces no hay pendientes |
| **18 (parcial)** | La ventana caduca por inactividad, no por antigüedad de cada llamada | Resilience4j no soporta una ventana híbrida (contar llamadas **y** descartar las viejas una por una). Lo implementado descarta la ventana entera cuando pasó `window-max-age` sin ninguna llamada, que cubre el caso que la auditoría describe —la madrugada— pero no el de tráfico bajo y constante | Una implementación propia de `CircuitBreaker` o migrar a `TIME_BASED` con un mínimo de llamadas alto, que es el intercambio que el diseño §2 rechazó con razón |
| **19 (parcial)** | El circuito sigue sin cubrir los primeros minutos de una caída con el cache caliente | Es estructural: el cache va **por fuera** del circuito a propósito, así que un hit no genera un voto. Bajar el mínimo de llamadas cambiaría el problema por falsos positivos | Ya está compensado por el presupuesto —que acota el peor caso a 1,6 s aunque el circuito esté cerrado— y por la sonda `originAvailable`. Si hiciera falta más, el paso siguiente es un circuito que también cuente los *hits* que se sirvieron *stale* |

---

## 6. Comandos de verificación

### Levantar el entorno

```bash
docker compose up -d
```

Levanta PostgreSQL 17, Redis 7, RabbitMQ 4 y el `api-catalog`. La aplicación arranca igual
sin ninguno de ellos: sin Redis el cache es en memoria, sin broker los eventos se acumulan
en el outbox, y sin catálogo el maestro es el stub.

```bash
./mvnw spring-boot:run
```

### Build y tests

```bash
./mvnw -o test
```

555 tests unitarios, sin Docker.

```bash
./mvnw verify
```

Agrega 136 tests de integración con Testcontainers (PostgreSQL y RabbitMQ) más el catálogo
falso en proceso.

### Ver el estado de los circuitos

```bash
curl -s localhost:9090/actuator/prometheus | grep resilience4j_circuitbreaker_state
```

### Reproducir a mano la apertura del circuito

Bajar el catálogo:

```bash
docker compose stop api-catalog
```

Disparar unas pocas reservas (con un token de desarrollo) y mirar cómo abre:

```bash
curl -s localhost:9090/actuator/prometheus | grep 'resilience4j_circuitbreaker_state{.*name="catalog"'
```

La serie con `state="open"` pasa a `1.0` dentro de los primeros pedidos. A partir de ahí,
las respuestas `2xx` llevan `X-Degraded: airport-catalog` mientras haya valor guardado, y
`503` + `Retry-After: 5` cuando no lo hay. Las llamadas ahorradas se ven en:

```bash
curl -s localhost:9090/actuator/prometheus | grep 'circuitbreaker_calls.*kind="not_permitted"'
```

Y volver a levantarlo:

```bash
docker compose start api-catalog
```

A los 5 s el circuito pasa solo a `half_open` —sin reinicio y sin que haga falta tráfico— y
con 4 llamadas buenas vuelve a `closed`. La transición queda en el log como
`[circuito] catalog: OPEN -> HALF_OPEN`.
