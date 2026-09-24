# Auditoría de la resiliencia

> Salida del prompt [17 — Auditoría de la resiliencia](../prompts/17-auditoria-de-resiliencia.md).
> Entradas: el [diseño de la resiliencia](design.md) y el código de este repositorio.
> Entrada del prompt [18 — Implementación de la resiliencia](../prompts/18-implementacion-de-resiliencia.md).

Auditoría sobre las dos cosas a la vez: el diseño de §16 y la implementación parcial que
hoy está en el repositorio. Cada hallazgo apunta a un archivo y una línea, a una propiedad
de `application.yml` o a una fila concreta del diseño. Lo que el diseño ya decidió y
documentó como límite asumido está en §5, aparte, y no cuenta como hallazgo.

- §1 [Tabla de hallazgos](#1-tabla-de-hallazgos)
- §2 [Cómo se detecta cada uno](#2-cómo-se-detecta-cada-uno)
- §3 [Presupuesto de latencia medido](#3-presupuesto-de-latencia-medido)
- §4 [Mitigación propuesta](#4-mitigación-propuesta)
- §5 [Limitaciones asumidas](#5-limitaciones-asumidas)
- §6 [Orden de remediación](#6-orden-de-remediación)

**El hecho de contexto que ordena todo lo demás:** no hay ningún circuit breaker en el
código. `grep -rn resilience4j pom.xml src/` no devuelve nada y la cadena real del catálogo
es `CachingAirportCatalog → CatalogAirportCatalog → RetryingCityCatalogClient →
RestCityCatalogClient` (`AdapterConfiguration.java:121-125`). Los §§2-3 del diseño están
enteros por hacer. Eso no se lista como hallazgo —es la premisa del prompt— pero sí se
listan los hallazgos que **sobreviven** a implementar el diseño tal cual está escrito: son
la mayoría, y son los que importan.

---

## 1. Tabla de hallazgos

| # | Hallazgo | Categoría | Evidencia | Impacto | Severidad |
|---|---|---|---|---|---|
| **1** | **El *stale-while-error* es el camino LENTO, no el rápido.** `CachingAirportCatalog` llama al origen y recién en el `catch` sirve el valor viejo. No hay ninguna marca de "el origen está caído": cada ciudad, en cada pedido, vuelve a pagar los 3 intentos completos antes de caer al *stale* | fallback / latencia | `CachingAirportCatalog.java:98-111`; `RetryingCityCatalogClient.java:95-119`; `AirportExistenceValidator.java:39-44` (bucle **en serie**) | Con el catálogo caído y el cache poblado, un `POST` **devuelve 201 después de ~86 s** y dispara 33 pedidos al catálogo. El fallback que existía para que la caída fuera invisible es justamente lo que produce el peor pedido del sistema, y lo hace durante las 2 h 30 m enteras de la ventana de gracia | **Crítica** |
| **2** | **El circuito de Redis del diseño no puede abrirse nunca por fallo.** `RedisCacheStore` atrapa toda `RuntimeException` y devuelve `Optional.empty()` / no-op; el `CircuitBreakingCacheStore` que el diseño pone **encima** ve el 100 % de las llamadas como exitosas | circuito | `RedisCacheStore.java:51-83` (tres `catch (RuntimeException)` que no relanzan) vs diseño §5 «Redis» (orden `MeteredCacheStore → CircuitBreakingCacheStore → RedisCacheStore`) y §4 fila «Redis: cualquier `RuntimeException` de Lettuce → **Sí** cuenta» | Redis caído duro (connection refused) responde **rápido**: ni fallo ni llamada lenta. El circuito queda `CLOSED` para siempre y se sigue pagando el viaje en cada operación. Sólo abriría en el modo lento (>150 ms), que es el que menos duele. El circuito más caro de construir es el que menos sirve | **Crítica** |
| **3** | **Con Redis caído desaparece el *stale-while-error* del catálogo**: la ventana de gracia vive en el mismo `CacheStore` que se cayó | fallback | `CachingAirportCatalog.java:92` (`cache.get(key)`), `CacheConfiguration.java:63-70` + `:121-141` (`cityCatalogCacheStore` → `RedisCacheStore`). El diseño §7 no analiza el fallo correlacionado | Redis caído + catálogo degradado = `503` en el **100 %** de los `POST` y `PUT`. El L1 en memoria que el diseño agrega (§7) arranca **vacío**, así que no cubre el primer pedido de cada ciudad, que es el que importa. La única defensa del camino del pedido depende de una dependencia declarada opcional | **Alta** |
| **4** | **El fallback tapa un fallo permanente.** El `catch` del *stale* es `RuntimeException`, así que también atrapa `AirportCatalogIntegrationException` (401, credencial vencida, cuerpo fuera de contrato) | fallback | `CachingAirportCatalog.java:104` (`catch (RuntimeException e)`), no `catch (AirportCatalogUnavailableException)`. Salida final en `ReservationExceptionHandler.java:283-288` | Una API key vencida queda **invisible hasta 2 h 30 m**; después sale como `500 INTERNAL_ERROR` genérico, sin código que la nombre y sin métrica. Es exactamente el caso que ningún mecanismo automático resuelve, y es el que más tarda en verse. El diseño §8 quiere alertar sobre él pero no corrige el `catch` que lo esconde | **Alta** |
| **5** | **Se sirve *stale* negativo**: un "no existe" viejo rechaza una reserva válida con `400 UNKNOWN_AIRPORT`, que no es reintentable | fallback | `CachingAirportCatalog.java:100,110` (mismo camino para positivos y negativos); `negative-cache-ttl: 5m` + `stale-while-error: 2h` (`application.yml:165,169`) → hasta **2 h 05 m**; `ReservationExceptionHandler.java:152-155` | El usuario lee «corregí el aeropuerto» sobre un itinerario correcto, y no hay nada que pueda hacer. Es el error más caro del sistema: miente sobre la causa y no es reintentable. El diseño §7 lo identifica correctamente; el código no lo implementa | **Alta** |
| **6** | **`retry-ceiling: 6h` es configuración muerta.** Con `initial-backoff 5s`, `max-backoff 5m` y `max-attempts 10`, los diez intentos se agotan en **10–20 min** de reloj, nunca en 6 h | reintentos / backoff | `application.yml:322,326,330,331`; `JdbcEventOutbox.java:342-375`. Suma de los nueve backoffs con el techo de 5 m: 5+10+20+40+80+160+300+300+300 = **1 215 s = 20 min 15 s** en el peor caso, ~10 min 30 s en promedio | Una caída del broker de más de ~20 min manda **todo** el outbox a `FAILED` y hay que drenar la dead letter a mano. El par de números documenta una intención («seis horas reintentando es un problema que ya tiene dueño») que no ocurre nunca | **Alta** |
| **7** | **El *gate* de circuito del broker no salva al outbox cuando el backlog es chico.** Las sondas de semiabierto gastan intentos reales, y siempre sobre los mensajes más viejos | circuito / anidamiento | Diseño §5 «RabbitMQ» (lote de **1** en semiabierto, 2 sondas, 60 s abierto) + `JdbcEventOutbox.java:95` (`ORDER BY sequence`) + `application.yml:322` (`max-attempts: 10`) | ~120 intentos gastados por hora, repartidos entre los pendientes. Con un backlog grande cada mensaje recibe una fracción y sobrevive; **con 5 mensajes pendientes —una madrugada— cada uno agota sus 10 intentos en menos de una hora y muere igual**. El circuito protege el incidente ruidoso y castiga al silencioso, y elige siempre los más viejos: los `reservation.created`, que son los que más importan | **Alta** |
| **8** | **Un mensaje devuelto por falta de binding abre el circuito del broker** y frena la entrega de todo lo demás | circuito | `RabbitEventPublisher.java:132-143`: el `nack` y el *return* lanzan **la misma** `EventPublishException`. El `EventPublisherPort` no deja distinguirlos, así que el `CircuitBreakingEventPublisher` no puede cumplir la regla del diseño §4 («mensaje devuelto → **No** cuenta») | Un routing key nuevo sin cola atada (p. ej. `reservation.modified` recién agregado) produce 100 % de fallos para ese tipo; si es ≥ 60 % del lote, el circuito abre 60 s y **detiene eventos que salían bien**. Es el modo de falla que el propio diseño dice querer evitar, introducido por el circuito | **Alta** |
| **9** | **`markDispatched` / `markFailed` no verifican la propiedad del reclamo, y el lease es más corto que el peor caso de un tick** | reintentos | `JdbcEventOutbox.java:189-195` y `:225-230` (sin `AND status='IN_FLIGHT'`, a diferencia de `release` en `:244-248`). `batch-size: 50` × `confirm-timeout: 5s` = **250 s** por tick contra un broker que acepta y no confirma, vs `claim-lease: 2m` (`application.yml:316,293,335`) | Otra instancia —o el tick siguiente— re-reclama mensajes todavía en vuelo. La publicación doble la absorbe el dedup del consumidor, pero un `markFailed` tardío **devuelve a `PENDING` un mensaje que la otra instancia ya despachó** (reenvío indefinido) y `attempts` se cuenta dos veces, acelerando la dead letter del hallazgo 6 | **Alta** |
| **10** | **Sin bulkhead y con threads virtuales, nada limita las llamadas en vuelo contra `api-catalog`** | recursos | `AdapterConfiguration.java:121-125` (cadena sin bulkhead); `application.yml:42-46` (virtual threads on); `AirportExistenceValidator.java:39-44` | Los threads virtuales quitaron el *backpressure* natural de un pool acotado y **no lo reemplazó nada**. 500 `POST` concurrentes contra un catálogo lento son hasta 500 × 3 sockets contra un proveedor con ~10 conexiones a MySQL: nuestro timeout se convierte en su saturación, y nuestro reintento en su caída. El diseño §5 propone 50 permisos; hoy el límite es infinito | **Alta** |
| **11** | **No hay techo del lado de la base**: ni `statement_timeout`, ni `@Transactional(timeout)`, y `connection-timeout: 3000` | recursos / latencia | `application.yml:59-66` (no hay `data-source-properties`); `CreateReservationTransaction.java:80` y `ModifyReservationTransaction.java:55` (`@Transactional` pelado); `grep -rn statement_timeout src/` → nada | El `connection-timeout` acota la espera **por** una conexión, no el uso de la que ya se tomó. Una base lenta retiene las 20 conexiones sin corte y toda la API cae, incluidos los `GET` que no tocan la tabla lenta. El diseño lo propone; el código no lo tiene | **Alta** |
| **12** | **Presupuesto de tiempo: el peor caso real es ~90 s, no los 4 s del objetivo, y supera el `graceful shutdown`** | latencia | Cálculo en §3 con `application.yml:193,194,207,208,213`; `ItineraryRequest.java:51` (**hasta 10 tramos → 11 ciudades distintas**, no 8) + `Itinerary.java:83-90,106-117`; `application.yml:3,41` (`shutdown: graceful`, 25 s) | El diseño calcula sobre 8 ciudades pero el contrato admite 11. Un deploy durante una degradación del catálogo **corta los pedidos en curso a los 25 s**, y detrás de ellos hay transacciones a punto de abrirse. Un proxy típico (ALB/nginx, 60 s) corta antes que el servidor: el usuario ve un 504 mientras la reserva se sigue creando | **Alta** |
| **13** | **No hay ninguna métrica de reintentos ni de *stale*: sólo `log.warn`** | observabilidad | `RetryingCityCatalogClient.java:104,115` (dos `log.warn`, ningún `Counter`); `CachingAirportCatalog.java:108` (un `log.warn`) | «Revisar la métrica de reintentos bajo carga con la dependencia caída» es **hoy imposible**, y tampoco hay forma de saber cuántas respuestas salieron por el fallback ni cuán viejo era el dato. Se puede saber que el catálogo está lento (`http.client.requests` sí se publica, porque el `RestClient` sale del `Builder` autoconfigurado en `AdapterConfiguration.java:157`), pero no qué está haciendo nuestra política al respecto | **Alta** |
| **14** | **Las métricas no son raspables desde afuera**: no hay registro de Prometheus y el puerto de gestión no se publica | observabilidad | `pom.xml` sin `micrometer-registry-prometheus`; `application.yml:395-405` (`management.server.port: 9090`, «no se publica hacia afuera») y `:416` (`include: health,info,metrics,outbox,messaging-dlq`, sin `prometheus`) | La respuesta a «¿se puede saber, sin entrar al servidor, si un circuito está abierto y hace cuánto?» es **no**. `/actuator/metrics` devuelve un medidor por vez en JSON: sirve para mirar, no para alertar. Las alertas con umbral que el diseño §8 justifica no tienen de dónde salir | **Alta** |
| **15** | **El backoff del consumidor es fijo y sin jitter** | backoff | `application.yml:305` (`retry-delay: 30s`); `MessagingConfiguration.java:200-203` (`.ttl(retryDelay)` sobre la cola de espera); `ReservationEventListener.java:140-148` | Todos los mensajes que fallaron juntos vuelven **juntos**, exactamente 30 s después, contra un destino que sigue caído. Cinco vueltas sincronizadas en 150 s y después DLQ: la caída de un consumidor de más de 2 min 30 s vacía la cola hacia la dead letter en bloque. El diseño §3 lo da por bueno («los que ya hay») sin revisarlo | **Media-Alta** |
| **16** | **La cola de espera del consumidor no tiene cota, a diferencia de la principal** | recursos | `MessagingConfiguration.java:200-203` (`reservationEventsRetryQueue()`: sólo `.ttl()` y `.deadLetterExchange()`) vs `:185-192` (`reservationEventsQueue()` con `maxLength(100000)` + `rejectPublish`) | Con el destino caído, la cola de espera crece sin límite en el broker. La alarma de disco de RabbitMQ termina frenando **las publicaciones del relay**, que es el camino que sí tenía protección: la falta de cota en una cola interna se convierte en la caída del broker entero | **Media** |
| **17** | **`RabbitTemplate` acumula `CorrelationData` sin confirmar** | recursos | `RabbitEventPublisher.java:89,117-130`: al vencer el `confirm-timeout` se lanza y se abandona el `CorrelationData`, que sigue en el mapa de *pending confirms* del template. `MessagingConfiguration.java` no configura `setConfirmTimeout` ni programa `checkForMissingConfirms` | Con el broker aceptando conexiones y sin confirmar, los pendientes crecen un objeto por mensaje durante toda la caída. Es el punto donde una dependencia lenta consume memoria sin cota, y no está en el inventario del diseño | **Media** |
| **18** | **Ventana `COUNT_BASED` sin caducidad: en tráfico bajo el circuito decide con datos de hace horas** | circuito | Diseño §2 «Umbrales»: los tres circuitos son `COUNT_BASED` | La justificación del diseño (con ventana temporal, a las 4 AM dos fallos serían el 100 %) es correcta, pero el efecto inverso no está cubierto: una ventana de 50 llamadas puede abarcar varias horas de madrugada. Fallos de un incidente **ya resuelto** mantienen el circuito al borde, y éxitos viejos impiden que abra durante uno nuevo. La mezcla correcta es contar llamadas **y** caducar la ventana | **Media** |
| **19** | **El mínimo de 20 llamadas del circuito del catálogo tarda en alcanzarse justo al principio de la caída** | circuito | Diseño §2 (mín. 20, ventana 50) + §5.1 (el cache va **por fuera** del circuito) + `application.yml:162` (`cache-ttl: 30m`) | Con el cache caliente, los primeros minutos de una caída producen muy pocas llamadas reales: las entradas frescas siguen sirviéndose sin tocar el circuito. El circuito recién junta 20 votos cuando las entradas empiezan a vencer, y hasta entonces cada usuario desafortunado paga el peor caso completo. No es un error de umbral: es que el circuito **no protege el caso común**, y el diseño lo presenta como si lo hiciera | **Media** |
| **20** | **El `Idempotency-Key` protege la escritura pero no la carga: el reintento del usuario duplica las llamadas al catálogo** | anidamiento | `CreateReservationService.java:66-71` (el catálogo se consulta **antes** de la transacción, a propósito); `ReservationController.java:306` (`Idempotency-Key` obligatoria) | Un cliente que corta a los 30 s y reintenta dispara otros 33 pedidos contra un catálogo ya degradado; el pedido anterior sigue corriendo. La clave evita la reserva doble —bien— pero no la amplificación, y `writes: 20/min` por instancia (`application.yml:277`) no la contiene | **Media** |
| **21** | **El javadoc del retry declara un peor caso de 6,5 s que no incluye el connect timeout: el real es 7,8 s** | latencia | `RetryingCityCatalogClient.java:52-56` vs `application.yml:193,194,207,208,213` | Todo cálculo derivado de ese número queda 17 % corto, y es el número que se venía citando. El diseño §1 ya lo señala; el código sigue diciendo 6,5 | **Baja** |
| **22** | **El diseño factura como Redis un rate limit que es en memoria** | latencia | Diseño §6, fila «Rate limit (Redis) 200 ms» vs `RateLimitFilter.java:74` (`ConcurrentHashMap`, ninguna llamada a Redis) | El presupuesto de Redis del `POST` está sobreestimado en 200 ms, y al revés: el costo real del rate limit —una cuota por instancia, N veces la nominal con N instancias— no se descuenta de ningún presupuesto | **Baja** |

---

## 2. Cómo se detecta cada uno

Todas las pruebas corren con `compose.yaml` + `mvn verify`. Las que necesitan simular una
caída bajan un contenedor (`docker compose stop <servicio>`) o levantan un
`com.sun.net.httpserver.HttpServer` del JDK como catálogo falso —que es lo único que
permite simular «acepta la conexión y no contesta», cosa que `MockRestServiceServer` no
puede—. Testcontainers (PostgreSQL y RabbitMQ), Awaitility y `support/MutableClock` ya
están en el proyecto.

| # | Prueba concreta | Si está bien | Si está mal |
|---|---|---|---|
| **1** | IT nueva `CatalogStaleLatencyIT`: `HttpServer` del JDK que acepta y nunca responde, apuntado por `reservations.airport-catalog.base-url`. Se calienta el cache con un `POST` sano, se cambia el servidor al modo colgado, se manda un `POST` con **10 tramos** y se mide el tiempo de pared | `< 4 s` y `201` con `X-Degraded: airport-catalog`, y el contador de pedidos del `HttpServer` marca **0 o 1** llamadas | **~86 s** y `201` sin ninguna marca; el contador del `HttpServer` marca **33** llamadas. Es el estado actual |
| **2** | IT `RedisCircuitIT`: `docker compose stop redis`, 200 operaciones de cache, y se comparan dos series de Micrometer: `resilience4j.circuitbreaker.calls{name=redis,kind=failed}` contra `reservations.cache.errors` | `calls{kind=failed} ≥ cache.errors` y `circuitbreaker.state{name=redis} == open` antes de la llamada 60 | `cache.errors ≈ 200` y `calls{kind=failed} == 0`, estado `closed`. La desigualdad entre las dos series **es** la prueba y no depende de la implementación |
| **3** | IT `CorrelatedOutageIT`: `docker compose stop redis && docker compose stop api-catalog`, dos `POST` seguidos con el mismo itinerario | El segundo `POST` responde `201` desde el L1 en memoria | Los dos responden `503 AIRPORT_CATALOG_UNAVAILABLE`: el *stale* no existe porque vivía en Redis |
| **4** | Se extiende `CachingAirportCatalogTest`: se cachea un positivo, se hace que el delegado lance `AirportCatalogIntegrationException` y se afirma el resultado | Sube la excepción (→ `500` con un código propio) y se incrementa `reservations.catalog.errors{kind=integration}` | Devuelve el valor cacheado: la credencial vencida queda tapada 2 h 30 m |
| **5** | Se extiende `CachingAirportCatalogTest` con `support/MutableClock`: se cachea un **negativo**, se avanzan 10 min (pasa el `negative-cache-ttl` de 5 m), el delegado lanza `AirportCatalogUnavailableException` | Sube la excepción → `503` + `Retry-After`, reintentable y honesto | Devuelve `false` → `400 UNKNOWN_AIRPORT` sobre un aeropuerto que existe |
| **6** | Se extiende `JdbcEventOutboxIT` con `MutableClock`: se marca un mensaje como fallido en bucle avanzando el reloj al `next_attempt_at` de cada vuelta, hasta que quede en `FAILED`; se mide la **edad simulada** a la que muere | La edad al morir es `≈ retry-ceiling` (6 h) | Muere con una edad de **10–20 min**: `max-attempts` gana siempre y `retry-ceiling` no participa |
| **7** | IT `OutboxCircuitBudgetIT`: 5 mensajes pendientes, broker caído (`docker compose stop rabbitmq`), circuito activo, se corre 1 h de reloj simulado de scheduler | Los 5 siguen `PENDING` al cabo de la hora | Los 5 quedan `FAILED`: contar `attempts` por mensaje al final es la aserción |
| **8** | IT sobre `MessagingFlowIT`: se borra el binding de un routing key (`rabbitmqadmin`/`RabbitAdmin`), se publica un lote mezclado de eventos con y sin binding | Los eventos con binding se siguen despachando; sólo los huérfanos fallan | El circuito abre y `outbox.pending` crece también para los tipos sanos |
| **9** | IT `OutboxClaimRaceIT`: dos `JdbcEventOutbox` contra el mismo PostgreSQL de Testcontainers. A reclama, se avanza el `MutableClock` más allá de `claim-lease`, B reclama la misma fila, B hace `markDispatched`, y recién entonces A hace `markFailed` | La fila queda `DISPATCHED` con `attempts = 1` | La fila vuelve a `PENDING` con `attempts = 2`: un mensaje ya entregado se reenvía y está dos intentos más cerca de la dead letter |
| **10** | IT `CatalogBulkheadIT`: 200 `POST` concurrentes contra el `HttpServer` colgado, que lleva un `AtomicInteger` de intercambios abiertos simultáneos | El máximo observado es `≤ 50` | El máximo es del orden de 200: nada acota las llamadas en vuelo |
| **11** | Determinista y sin caída: se pide una conexión al `DataSource` y se ejecuta `SHOW statement_timeout`; y una regla de ArchUnit que exija `@Transactional(timeout=...)` en `..service.*Transaction` | Devuelve `2s` y la regla pasa | Devuelve `0` (sin límite) y la regla falla. Complemento con caída: `docker compose pause postgres` en mitad de un `POST` y afirmar que responde en `< 3 s` |
| **12** | La misma IT del hallazgo 1, con la aserción de tiempo puesta en el presupuesto declarado, más un test que afirme que el peor caso calculado (`ciudades × intentos × (connect+read) + backoffs`) entra en `spring.lifecycle.timeout-per-shutdown-phase` | El peor caso `< 25 s` | El peor caso `≈ 90 s`: un deploy corta reservas en curso |
| **13** | Aserción sobre el `MeterRegistry` del contexto: existen `reservations.catalog.retries` y `reservations.catalog.stale_served` con sus etiquetas | Los medidores existen y se incrementan en la IT del hallazgo 1 | `registry.find(...).counter() == null`. Falla hoy en una línea |
| **14** | `GET http://localhost:9090/actuator/prometheus` desde una IT | `200` con las series `resilience4j_circuitbreaker_state` | `404`: no hay registro de Prometheus. Segunda aserción: que la serie de estado exista con las tres etiquetas `catalog`, `redis`, `broker` |
| **15** | Se extiende `ConsumerResilienceIT`: 20 mensajes que fallan todos, se registran los instantes de reentrega y se calcula la dispersión | Desviación estándar `> 0`, con los reintentos repartidos | Los 20 vuelven dentro de la misma ventana de ~100 ms, 30 s después: manada sincronizada |
| **16** | Aserción sobre los argumentos declarados de la cola: `rabbitAdmin.getQueueProperties(RETRY_QUEUE)` contiene `x-max-length` y una política de overflow | Ambos presentes | Ausentes: la cola de espera crece sin cota |
| **17** | IT con el broker en modo «acepta y no confirma» (`rabbitmqctl suspend_listeners`): se publican 200 mensajes y se lee `rabbitTemplate.getUnconfirmed(0).size()` | Se mantiene acotado | Crece monótonamente hasta 200: fuga de memoria proporcional a la duración de la caída |
| **18** | Test unitario del `CircuitBreaker` construido con un reloj de test: 25 fallos en T, 25 éxitos repartidos en 3 h, y se afirma el estado y el contenido de la ventana | La ventana caducó y los fallos de hace 3 h ya no pesan | Los 50 votos siguen en la ventana: el circuito decide con un incidente que terminó hace horas |
| **19** | IT: cache caliente con 20 ciudades, se tira el catálogo, se cuentan **cuántos pedidos de usuario** hacen falta hasta que el circuito abra | El circuito abre dentro de los primeros 3-5 pedidos | Hacen falta decenas de pedidos, o el circuito no abre hasta que el `cache-ttl` de 30 m empiece a vencer |
| **20** | IT: dos `POST` concurrentes con la **misma** `Idempotency-Key` contra el `HttpServer` colgado, contando los pedidos al catálogo | Una sola reserva creada **y** un solo juego de llamadas al catálogo (la segunda corta temprano por la clave) | Una sola reserva creada pero **66** llamadas al catálogo: la clave protege la base, no al proveedor |
| **21-22** | Test de propiedades que calcule el peor caso a partir de `AirportCatalogProperties` y lo compare con la constante documentada; y una aserción de que `RateLimitFilter` no depende de `StringRedisTemplate` | Los números del javadoc y del diseño coinciden con los de `application.yml` | Divergen en 1,3 s y en 200 ms respectivamente |

---

## 3. Presupuesto de latencia medido

Calculado con los valores **reales** de `application.yml`, no con los del diseño ni con los
defaults de ninguna librería.

### Costo por ciudad (peor caso)

| Componente | Valor | Origen |
|---|---|---|
| `connect-timeout` | 500 ms | `application.yml:193` |
| `read-timeout` | 2 000 ms | `application.yml:194` |
| Costo de un intento | **2 500 ms** | suma de los dos: conexión que se establece tarde y lectura que vence |
| Intentos | **3** | `application.yml:207` |
| Backoff tras el intento 1 | 50–100 ms | `RetryingCityCatalogClient.java:159-167`, jitter sobre la mitad superior de 100 ms |
| Backoff tras el intento 2 | 100–200 ms | ídem, sobre 200 ms (techo 500 ms, `application.yml:213`) |
| **Total por ciudad** | **7 800 ms** | `3 × 2 500 + 100 + 200`. El javadoc dice 6,5 s porque omite el connect (hallazgo 21) |

### Cuántas ciudades

`Itinerary.airports()` devuelve un **`Set`** (`Itinerary.java:83-90`) y los tramos tienen que
encadenarse (`Itinerary.java:106-117`), así que un ida y vuelta con escala son **3 códigos
distintos**, no 8: el número del diseño y del README sobreestima el caso típico. Pero el
contrato admite **10 tramos** (`ItineraryRequest.java:51`), y 10 tramos encadenados son
**11 ciudades distintas**: el peor caso real es mayor que el que el diseño presupuestó.

### `POST /v1/reservations` — peor caso, cache poblado y catálogo colgado

Es el caso peor **y** el que devuelve `201`: el camino del fallback es el lento.

| Paso | Techo | De dónde sale |
|---|---|---|
| Filtro de correlación + JWT | ~0 ms | clave JWKS cacheada por Nimbus |
| Rate limit | **~0 ms** | `RateLimitFilter.java:74`: en memoria, no Redis (hallazgo 22) |
| 11 × `cache.get` de ciudad | 2 200 ms | `spring.data.redis.timeout: 200ms`, **una lectura por ciudad**, en serie |
| **11 × resolución de ciudad contra el origen** | **85 800 ms** | 11 × 7 800 ms, en serie (`AirportExistenceValidator.java:39-44`) |
| 11 × `cache.put` | 0 ms | no se ejecuta: el origen falló |
| Obtener conexión del pool | 3 000 ms | `hikari.connection-timeout: 3000` |
| Transacción (6+ sentencias) | **sin techo** | no hay `statement_timeout` ni `@Transactional(timeout)` (hallazgo 11) |
| `versionCache.remember` | 200 ms | `ReservationController.java:441` |
| **Total acotable** | **≈ 91 s** | más el tiempo de base, que no tiene cota |

**Pedidos reales que genera un `POST` del usuario:** **33** al `api-catalog` (11 ciudades ×
3 intentos), **~23** operaciones de Redis (11 `get` + 11 `put` + 1 `put` de versión) y **10+**
sentencias SQL. Si el usuario reintenta —y a los 90 s reintenta— se duplican todas
(hallazgo 20): la `Idempotency-Key` sólo impide la segunda reserva.

**Variante que falla rápido:** si la primera ciudad **no** tiene entrada en el cache, la
excepción sube en el primer `exists` y el pedido responde `503` a los **8 s** con 3 pedidos
al catálogo. Es decir: el pedido que falla cuesta 8 s y el que tiene éxito cuesta 91 s. El
orden está exactamente al revés de lo deseable.

### `PUT /v1/reservations/{id}` — peor caso

| Paso | Techo | Nota |
|---|---|---|
| JWT + rate limit | ~0 ms | |
| `findById` fuera de transacción + chequeo de acceso y `If-Match` | 3 000 ms + sin techo | conexión del pool; corta temprano ante `If-Match` viejo (`409` sin tocar el catálogo) |
| Validación del itinerario | **88 000 ms** | igual que el `POST`: `ModifyReservationService.java:85` |
| Transacción de escritura | 3 000 ms + sin techo | |
| **Total acotable** | **≈ 94 s** | **33** pedidos al catálogo, ~23 a Redis, ~12 sentencias SQL |

### `GET /v1/reservations/{id}` y `GET /v1/reservations`

No tocan el catálogo. El peor caso es Redis lento más la base.

| Paso | Techo | Nota |
|---|---|---|
| `versionCache.find` (detalle) o cache del total (listado) | 200 ms | `ReservationController.java:435` / `CachingReservationSearchQuery` |
| Conexión del pool | 3 000 ms | |
| Consulta(s) | **sin techo** | `open-in-view: false`, 1 consulta con `@EntityGraph` en el detalle, 3 en el listado |
| `versionCache.remember` | 200 ms | |
| **Total acotable** | **≈ 3,4 s** | **1-2** operaciones de Redis y **1-3** sentencias SQL por pedido del usuario. Sin amplificación |

### Contra lo que el cliente está dispuesto a esperar

En el repositorio no hay cliente HTTP de frontend, así que la comparación se hace contra
las tres referencias que **sí** están escritas:

| Referencia | Valor | Comparación |
|---|---|---|
| Objetivo declarado por el diseño §6 | `POST` ≤ **4 s**, `PUT` ≤ **4,5 s** | Se excede **23×** y **21×** |
| La pantalla que describe `application.yml:271-273` («refresca cada 5 s») | ~5 s de tolerancia implícita | Una interfaz con esa cadencia abandona muchísimo antes de los 91 s |
| Cortes de infraestructura habituales (ALB idle 60 s, `proxy_read_timeout` 60 s de nginx) | **60 s** | El proxy corta **antes** que el servidor. El usuario ve un `504` mientras el servidor sigue y **crea la reserva igual**, porque la transacción va después del catálogo |
| `spring.lifecycle.timeout-per-shutdown-phase` | **25 s** (`application.yml:41`) | Un deploy durante una degradación corta los pedidos en curso: el `graceful shutdown` es 3,6× más corto que el pedido que debería dejar terminar |

---

## 4. Mitigación propuesta

Una o dos líneas cada una. Sin código: eso es el paso 18.

| # | Mitigación |
|---|---|
| **1** | Que el fallback deje de pagar el viaje: memorizar el estado «origen caído» (que es lo que el circuit breaker hace de fábrica) y servir el *stale* **sin** llamar. Y resolver las ciudades en paralelo sobre threads virtuales con un `deadline` único del itinerario, como plantea `BudgetedCityCatalogFanout` en el diseño §5 |
| **2** | Que `RedisCacheStore` **relance** y que la degradación al origen la decida el decorador de circuito, que es quien necesita ver el fallo. Hoy la política de fallback y la clasificación del error viven en la misma clase, y eso es lo que ciega al circuito |
| **3** | El L1 en memoria del prefijo `city:` tiene que ser **escritura permanente**, no sólo de emergencia: si se puebla siempre, está caliente cuando Redis se cae. Y el *stale-while-error* debería consultarlo antes de rendirse |
| **4** | Estrechar el `catch` del *stale* a `AirportCatalogUnavailableException`, y darle a `AirportCatalogIntegrationException` un código de error propio y una métrica: es el fallo que necesita una persona, no un fallback |
| **5** | Separar la ventana de gracia por signo: los positivos la conservan, los negativos vencen con su TTL. Un negativo vencido con el origen caído se contesta `503` + `Retry-After`, nunca `400` |
| **6** | Elegir uno de los dos cortes y que el otro sea coherente: o `max-attempts` sube a ~40 para que `retry-ceiling: 6h` sea el que manda, o el techo baja a los 20 min que hoy describe la realidad. Lo peligroso es que digan cosas distintas |
| **7** | Que la sonda de semiabierto no gaste un intento: reclamar el mensaje de prueba con un `release` incondicional al terminar, o probar con un `NOOP` contra el broker en lugar de con un mensaje real. Y que la sonda no tome siempre el más viejo |
| **8** | Separar el *return* del `nack` en dos excepciones distintas en el puerto, para que el circuito pueda aplicar la regla que el diseño §4 ya escribió. Un error de topología nuestro no puede frenar la entrega de lo ajeno |
| **9** | Agregar `AND status='IN_FLIGHT'` (y idealmente un token de reclamo) a `markDispatched` y `markFailed`, como ya tiene `release`. Y hacer que `claim-lease` se derive de `batch-size × confirm-timeout` en lugar de ser una constante suelta |
| **10** | Bulkhead de ~50 permisos con espera 0 delante del cliente del catálogo, que es el reemplazo explícito del *backpressure* que los threads virtuales quitaron |
| **11** | `statement_timeout` en el driver —no sólo `jakarta.persistence.query.timeout`, que no cubre el `flush` ni el `commit`— más `@Transactional(timeout)` en las clases `*Transaction`, y `connection-timeout` a 1 s |
| **12** | Presupuesto duro del itinerario, y que el número se calcule contra **11** ciudades y no contra 8. Que el peor caso resultante sea menor que el `graceful shutdown` es la aserción que lo mantiene honesto |
| **13** | Contador de reintentos con etiqueta de resultado y contador de `stale_served` con etiqueta de motivo y edad del dato. Sin esas dos series no hay forma de auditar la política |
| **14** | Agregar `micrometer-registry-prometheus` y exponer `prometheus` en el puerto de gestión, que sigue sin publicarse hacia afuera: lo que se abre es el *scrape*, no la superficie |
| **15** | Backoff creciente del consumidor con varias colas de espera de TTL distinta (30 s / 2 m / 10 m), que es como se hace un backoff exponencial con TTL de cola, más jitter en el reparto |
| **16** | Cota y política de overflow en la cola de espera, con el mismo criterio que la principal: `reject-publish` para que el rebalse sea visible y no silencioso |
| **17** | Configurar el `confirm-timeout` del `RabbitTemplate` (no sólo el `get` con timeout del futuro) o programar `checkForMissingConfirms`, para que los pendientes abandonados se limpien |
| **18** | Combinar el conteo con una caducidad: `COUNT_BASED` más un descarte de las llamadas anteriores a N minutos, o directamente `TIME_BASED` con un mínimo de llamadas alto |
| **19** | Aceptar que el circuito no cubre los primeros minutos y compensarlo bajando el mínimo de llamadas, o —mejor— haciendo que la marca de «origen caído» de la mitigación 1 sea la que corta, con el circuito como respaldo |
| **20** | Resolver la `Idempotency-Key` **antes** de validar el itinerario: si ya existe la reserva, no hay nada que validar. Convierte el reintento del usuario en una consulta a la base |
| **21-22** | Corregir los dos números documentados y, mejor, generarlos: un test que derive el peor caso de las propiedades y falle cuando la documentación se desactualice |

---

## 5. Limitaciones asumidas

No son hallazgos: están decididas, documentadas y el motivo se sostiene.

| Limitación | Dónde está documentada | Por qué no es un hallazgo |
|---|---|---|
| Health indicators de `redis` y `rabbit` apagados | `application.yml:421-434` | Las dos degradan sin afectar la respuesta al cliente. Marcarse `DOWN` sacaría la instancia de rotación por algo que el usuario no percibe. El de `db`, que sí importa, queda encendido |
| No hay presupuesto de tiempo del pedido completo | `RetryingCityCatalogClient.java:68-70`, `README.md:664`, diseño §6 | La ausencia está anotada como la mejora que sigue. Lo que esta auditoría aporta no es la ausencia sino **el número** (hallazgo 12): 91 s, 33 pedidos, contra un `graceful shutdown` de 25 s |
| `200` con cuerpo vacío se lee como «no existe» | `RestCityCatalogClient.java:52-75` + `RestCityCatalogClientLiveTest` | Decisión consciente sobre un proveedor que incumple su contrato, con un test contra el servicio real que avisa el día que empiece a devolver `404` |
| Sin circuit breaker para PostgreSQL | Diseño §2 | Falla la condición «hay algo mejor que hacer»: sin base no hay reserva. Un circuito cambiaría fallar lento por fallar rápido, que es lo que ya da el `connection-timeout` |
| Sin circuit breaker para el IdP / JWKS | Diseño §2 | Aceptar un token sin validar no es degradar, es un agujero. El mecanismo correcto (cache de claves de Nimbus) ya existe |
| `LoggingEventPublisher` y `UnavailableDeadLetterQueue` | Diseño §7, `application.yml:280-284` | Son el camino «arrancar sin broker», que es otra cosa que la degradación en caliente y sigue siendo un requisito |
| `503` cuando no hay **nada** cacheado | Diseño §3 | Deliberado y correcto: un `503` reintentable es mejor que un `400 UNKNOWN_AIRPORT` que miente sobre la causa |
| Rate limit por instancia, en memoria | `application.yml:264-268` | Documentado: el lugar del rate limiting es el gateway; éste es la última línea. (Que el diseño lo facture como Redis sí es un hallazgo, el 22 — el mecanismo no) |
| Entrega *at-least-once* con duplicados | Diseño §3, `ProcessReservationEventService.java:84-97` | El duplicado es de diseño y **sí** tiene clave de idempotencia: `claim(messageId)` corre primero y dentro de la misma transacción que el efecto |
| El catálogo se consulta fuera de la transacción | `CreateReservationService.java:33-41`, `HexagonalArchitectureTest` (`noTransactionalClassReachesTheAirportCatalog`) | Es la defensa de resiliencia que ya funciona y está sostenida por un test. Por eso el catálogo lento **no** agota el pool de Hikari, que era la pregunta 6 |

---

## 6. Orden de remediación

**Criterio:** primero lo que hoy le miente al usuario o le rechaza una operación válida;
después lo que pierde una notificación que ya no vuelve; después lo que amplifica la caída
hacia afuera; después lo que impide enterarse; al final lo cosmético. Dentro de cada grupo,
primero lo que desbloquea a lo siguiente. **La facilidad de arreglo no entra en el orden**:
el hallazgo 5 se arregla en cuatro líneas y va en el primer grupo porque le rechaza la
reserva a un usuario que hizo todo bien; el 14 es medio día de trabajo y va cuarto porque
nadie se queda sin viajar por una métrica que falta.

| Orden | Hallazgos | Por qué acá |
|---|---|---|
| **1. Lo que le miente al usuario ahora mismo** | **5**, **4**, **1** | El 5 rechaza reservas válidas con un error no reintentable. El 4 esconde 2 h 30 m una credencial vencida. El 1 convierte el fallback en un pedido de 91 s. Los tres están en el camino del `POST` y los tres se ven desde afuera. Además el 1 es el que justifica el circuito del catálogo, así que desbloquea el resto del diseño |
| **2. Lo que deja el sistema sin defensa** | **3**, **2** | El 3 hace que una caída de Redis borre el único fallback del camino del pedido. El 2 hace que el circuito de Redis del diseño nazca muerto. Van juntos porque los dos se resuelven moviendo la frontera entre `RedisCacheStore` y su decorador |
| **3. Lo que pierde notificaciones que no vuelven** | **6**, **9**, **7**, **8** | Un mensaje en `FAILED` no llega nunca sin que alguien lo drene a mano. El 6 es el que dispara: una caída de 20 min vacía el outbox a la dead letter. El 9 lo acelera contando intentos dos veces. El 7 y el 8 son las dos formas en que el circuito propuesto empeora esto en lugar de arreglarlo, así que hay que resolverlos **antes** de implementarlo |
| **4. Lo que amplifica la caída hacia afuera** | **10**, **11**, **12**, **20** | Convierten la degradación de un tercero en una caída nuestra, o la nuestra en la de un tercero. El 11 y el 12 son configuración con un test que los sostiene; el 10 y el 20 cambian estructura |
| **5. Lo que impide enterarse** | **13**, **14** | Van después de los arreglos y no antes: una métrica de un mecanismo que todavía no existe no mide nada. Van antes de lo que queda porque son la condición para saber si lo anterior funcionó en producción |
| **6. Ajustes de la política ya escrita** | **15**, **16**, **17**, **18**, **19** | Mejoran el comportamiento bajo caídas largas pero ninguno rompe una operación del usuario. El 18 y el 19 sólo tienen sentido una vez que los circuitos existan |
| **7. Documentación divergente** | **21**, **22** | No cambian ni una respuesta, pero cada número equivocado es un cálculo futuro equivocado. Se cierran con el test que los genera, no editando el texto |
