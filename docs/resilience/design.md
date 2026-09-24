# Diseño de la resiliencia

> Salida del prompt [16 — Diseño de la resiliencia](../prompts/16-diseno-de-resiliencia.md).
> Entrada del prompt [17 — Auditoría de la resiliencia](../prompts/17-auditoria-de-resiliencia.md).

Diseño hecho sobre el código de este repositorio, no sobre el enunciado: los costos por
llamada salen de leer `AdapterConfiguration`, `application.yml`, los decoradores de
`adapter/out/airport` y el relay del outbox.

- §1 [Inventario de dependencias](#1-inventario-de-dependencias)
- §2 [Dónde va un circuit breaker y dónde no](#2-dónde-va-un-circuit-breaker-y-dónde-no)
- §3 [Tabla maestra por dependencia](#3-tabla-maestra-por-dependencia)
- §4 [Clasificación de fallos](#4-clasificación-de-fallos)
- §5 [Orden de los decoradores](#5-orden-de-los-decoradores)
- §6 [Presupuesto de tiempo del pedido](#6-presupuesto-de-tiempo-del-pedido)
- §7 [Interacción con lo ya construido](#7-interacción-con-lo-ya-construido)
- §8 [Cómo se entera el sistema de que está degradado](#8-cómo-se-entera-el-sistema-de-que-está-degradado)
- §9 [Qué cambia en el código](#9-qué-cambia-en-el-código)
- §10 [De dónde sale cada número](#10-de-dónde-sale-cada-número)

---

## 1. Inventario de dependencias

Los tres modos de falla son los del enunciado: **(A)** errores aleatorios, **(B)** respuestas
lentas que retienen recursos, **(C)** caída total con llamadas colgadas.

| Dependencia | ¿Está en el camino del pedido? | Modo al que es vulnerable | Daño concreto sobre el usuario hoy |
|---|---|---|---|
| **api-catalog** (REST, `GET /city/{code}`) | **Sí**: `POST` y `PUT` validan cada aeropuerto del itinerario | **A, B y C** | Es la única dependencia de red sincrónica. Peor caso por ciudad hoy: `connect 500 ms + read 2 s` por intento × 3 intentos + 2 backoffs ≈ **7,8 s** (el javadoc de `RetryingCityCatalogClient` dice 6,5 s porque no cuenta el connect-timeout). Un ida y vuelta con escala son 8 ciudades **en serie**: ~62 s de pedido antes de caer al *stale*. El usuario mira un formulario colgado y reintenta, multiplicando la carga |
| **PostgreSQL** (JPA + Flyway) | **Sí**: toda escritura y toda lectura | **B y C** (A lo absorbe el pool) | No hay fallback posible: es el sistema de registro. Una base lenta retiene las 20 conexiones del pool y `connection-timeout: 3000` hace que 600 pedidos encolados esperen 3 s cada uno antes de fallar. La API entera devuelve error, incluidos los `GET` que ni tocan la tabla lenta |
| **Redis** (caché distribuida) | **Sí**, pero es opcional por diseño | **B y C** | Con Redis muerto cada `get`/`put` paga `timeout: 200 ms`. Un `POST` hace hasta 8 `get` + 8 `put` de ciudades más el rate limit: **hasta 3,4 s de espera pura** por un componente cuyo aporte es ahorrar tiempo. Además el cache frío convierte la caída de Redis en una estampida contra el catálogo |
| **RabbitMQ** (publicación desde el relay) | **No**: el outbox desacopla | **B y C** | El usuario no espera. El daño es sobre el **presupuesto de reintentos del mensaje**: con el broker caído, cada tick del relay reclama 50 mensajes, cada uno paga `connection-timeout 2 s` + `confirm-timeout 5 s` y **gasta uno de sus 10 intentos**. Diez ticks y mensajes perfectamente recuperables terminan en `FAILED`. La notificación nunca llega y hay que drenarla a mano |
| **Sistema de notificaciones** (consumidor externo) | **No** | **A, B y C**, pero amortiguados por la cola | Ninguno inmediato: los mensajes se acumulan en su cola. Agotados los 5 rounds de TTL, van a la DLQ y el usuario no recibe el mail de su reserva —que sí existe y es correcta— |
| **IdP / JWKS** (`jwk-set-uri`) | **Sí**, en cada pedido autenticado | **B y C** | No estaba en la lista del enunciado y aparece leyendo `JwtDecoderFactory`: si el JWKS no responde y la clave no está cacheada, **todos** los pedidos dan 401/500. Nimbus ya cachea las claves; queda anotado como dependencia inventariada, sin circuito (§2) |

Dos hechos del código condicionan todo lo que sigue:

- **Las llamadas al catálogo están fuera de la transacción a propósito**, y hay un test de
  ArchUnit (`noTransactionalClassReachesTheAirportCatalog`) que lo sostiene. Sin eso, la
  lentitud del catálogo sería agotamiento del pool de Hikari. Ese test es la primera
  defensa de resiliencia que ya existe y no se toca.
- **Threads virtuales encendidos**: el sistema *no* se queda sin threads esperando. Lo que
  se agota es el pool de conexiones (20) y la paciencia del cliente. Por eso el modo de
  falla B se mitiga con presupuestos de tiempo y bulkheads, no con tamaños de pool.

---

## 2. Dónde va un circuit breaker y dónde no

Un circuit breaker sirve cuando se cumplen las tres condiciones a la vez: **(1)** llamar a la
dependencia caída cuesta caro, **(2)** hay algo mejor que hacer que esperar, y **(3)** la
dependencia puede recuperarse sola. Si falta alguna, el circuito agrega un modo de falla
nuevo sin comprar nada.

| Dependencia | ¿Circuito? | Justificación |
|---|---|---|
| **api-catalog** | **Sí** | Se cumplen las tres. Llamar cuesta hasta 7,8 s por ciudad, hay algo mejor (el último valor conocido en cache) y una caída del catálogo se resuelve sola. Es el caso de libro |
| **Redis** | **Sí** | Cuesta 200 ms por operación, hay algo mejor y obvio (ir al origen, que es lo que se hace igual en un miss) y un failover de Redis dura segundos. El circuito convierte 3,4 s de espera inútil por pedido en ~0 |
| **RabbitMQ** | **Sí**, pero en el relay, no en el camino del pedido | Cuesta 7 s por mensaje y hay algo mejor: **no gastar el intento**. El circuito acá no protege latencia —nadie espera— sino el presupuesto de reintentos del outbox, que es lo que separa "el mail llegó tarde" de "el mail hay que reenviarlo a mano" |
| **PostgreSQL** | **No** | Falla la condición (2): no hay nada mejor que hacer. Sin base no hay reserva que crear ni que leer, así que un circuito abierto sólo cambia *fallar lento* por *fallar rápido* — y eso ya lo da `connection-timeout`, que es un load shedder con otro nombre. Además dispararía en falso: una consulta lenta sobre una tabla abriría el circuito para operaciones que estaban sanas, y con `SELECT ... FOR UPDATE SKIP LOCKED` del relay compitiendo por el mismo pool, un pico de contención se leería como una base caída. La caída total de la base **sí** tiene que sacar la instancia de rotación, y para eso está el health indicator de `db`, que —a diferencia de los de `redis` y `rabbit`— queda **encendido** |
| **Sistema de notificaciones** | **No** | Falla la condición (1): este servicio no lo llama. Entre nosotros y el consumidor hay una cola, que es un buffer con una política de reintentos propia. Un circuito necesita un llamador al que hacerle fallar rápido, y acá el llamador es una cola a la que no le molesta esperar |
| **IdP / JWKS** | **No** | Falla la condición (2): sin claves no se puede validar un token, y aceptar el pedido sin validarlo no es una degradación, es un agujero. El mecanismo correcto ya está: Nimbus cachea el JWKS y lo refresca; lo que corresponde es subir ese cache a 15 m y alertar sobre `401` masivos, no abrir un circuito que produciría una tormenta de 401 |

### Umbrales

Todos los circuitos son **`COUNT_BASED`** y no `TIME_BASED`. Razón: el tráfico es irregular
—picos de frontend de día, casi nada de madrugada— y con una ventana temporal el umbral
significa cosas distintas según la hora: a las 4 AM dos fallos serían el 100 % de la ventana.
Contando llamadas, el umbral quiere decir lo mismo siempre.

Todos tienen **`automaticTransitionFromOpenToHalfOpen: true`**. Es la restricción de
"un circuito abierto no puede ser permanente": sin eso, la transición a semiabierto depende
de que llegue una llamada, y un circuito que se abrió justo cuando el tráfico cayó se
quedaría abierto hasta el próximo pedido. Con la transición automática la recuperación no
necesita ni tráfico ni intervención.

| Parámetro | `catalog` | `redis` | `broker` |
|---|---|---|---|
| Tipo de ventana | `COUNT_BASED` | `COUNT_BASED` | `COUNT_BASED` |
| Tamaño de ventana | 50 llamadas | 100 llamadas | 20 llamadas |
| Llamadas mínimas | 20 | 30 | 5 |
| Umbral de fallo | 50 % | 50 % | 60 % |
| Umbral de llamada lenta | 60 % por encima de **900 ms** | 60 % por encima de **150 ms** | 60 % por encima de **2 s** |
| Tiempo abierto | **5 s** | **10 s** | **60 s** |
| Llamadas en semiabierto | 4 | 5 | 2 |
| Transición automática a semiabierto | sí | sí | sí |
| Unidad contada | una **resolución de ciudad** (no un intento HTTP) | una operación `get`/`put`/`evict` | una publicación con su confirm |

La justificación de cada número está en §10.

---

## 3. Tabla maestra por dependencia

| Dependencia | Modo de falla | Circuit breaker | Reintentos | Fallback | Qué ve el usuario |
|---|---|---|---|---|---|
| **api-catalog** | A, B, C | Ventana 50 llamadas, mín. 20, **50 %** de fallo o **60 %** de llamadas > 900 ms → abre **5 s**, semiabierto **4** llamadas, transición automática. Cuentan `AirportCatalogUnavailableException` y el timeout; **no** cuentan 404, 200 vacío ni `AirportCatalogIntegrationException` | Sólo `GET /city/{code}` (lectura idempotente). **2 intentos** (1 reintento, baja de 3), backoff inicial 100 ms, techo 200 ms, jitter sorteado sobre la mitad superior. **No reintenta 429** ni ningún fallo permanente. Sólo reintenta si el presupuesto restante del itinerario alcanza para otro intento completo (1,1 s) | Escalonado: **1)** entrada fresca en cache → se sirve; **2)** entrada vencida pero dentro de la ventana de gracia **y positiva** → se sirve *stale* (hasta 2 h 30 m de atraso) con header, métrica y WARN; **3)** entrada *stale* **negativa** → **no** se sirve (§7); **4)** nada guardado → no hay fallback: `503 AIRPORT_CATALOG_UNAVAILABLE` + `Retry-After: 5` | Caso 1 y 2: su reserva se crea normalmente, con `X-Degraded: airport-catalog` en la respuesta cuando el dato es viejo. Caso 3 y 4: `503` con `Retry-After`, que es reintentable y honesto — nunca un `400 UNKNOWN_AIRPORT` sobre un aeropuerto que sí existe |
| **PostgreSQL** | B, C | **Ninguno**, a propósito (§2). En su lugar: pool de 20, `connection-timeout` **1 s** (baja de 3 s), `statement_timeout` de 2 s en el driver, `@Transactional(timeout = 2)` en las clases `*Transaction`, health indicator `db` encendido | **Ninguno en la aplicación.** Un reintento de escritura sin clave que lo proteja duplica reservas; y el `POST` es idempotente sólo por `Idempotency-Key`, que es del cliente. La única repetición admitida es la que hace el cliente con la misma `Idempotency-Key`, y la resuelve `CreateReservationTransaction` buscando por esa clave antes de insertar | **No hay.** Es el único caso del diseño donde el pedido tiene que fallar | `503` (hoy `500`: ver §9) cuando el pool no da una conexión en 1 s, con `Retry-After: 1`. Si la base está caída del todo, el health indicator saca la instancia de rotación y el balanceador manda el tráfico a otra |
| **Redis** | B, C | Ventana 100 llamadas, mín. 30, **50 %** de fallo o **60 %** de llamadas > 150 ms → abre **10 s**, semiabierto **5**, transición automática. Cuenta cualquier `RuntimeException` de Lettuce, incluido el timeout | **Ninguno.** Reintentar un cache es pagar 200 ms otra vez por un dato que, si no está, se saca del origen igual. El "reintento" de un cache es el origen | Circuito abierto o error: `get` devuelve vacío, `put` y `evict` son no-ops, y el flujo va al origen. **Excepción**: las claves del prefijo `city:` caen a un `InMemoryCacheStore` acotado (10 000 entradas, mismo TTL), porque son el único dato cuya invalidación es sólo por TTL y son las que protegen a la dependencia cara. Las claves `reservation:version:*` **nunca** caen a memoria: se invalidan activamente en cada escritura y una copia por instancia daría `ETag` distintos según qué instancia atienda | Nada visible, salvo latencia: los `GET` paginados recalculan el total y las ciudades se revalidan contra el catálogo. Sin el circuito, vería hasta 3,4 s extra por pedido |
| **RabbitMQ** | B, C | Ventana 20, mín. **5**, **60 %** de fallo o **60 %** de confirms > 2 s → abre **60 s**, semiabierto **2**, transición automática. Cuentan `AmqpException`, timeout de confirm y confirm negativo; **no** cuenta un mensaje devuelto por falta de binding (es un error de topología, no del broker) | **Ninguno en proceso.** El outbox *es* el reintento: durable, con backoff de 5 s a 5 m, `max-attempts: 10` y `retry-ceiling: 6h`. Un reintento adentro del publicador duplicaría esa política y retendría el hilo del relay | El mensaje se queda en el outbox **sin gastar un intento**: con el circuito abierto el scheduler saltea el tick entero (ni siquiera consulta la base) y, si el circuito se abre en medio de un lote, el dispatcher libera los mensajes que quedaban sin marcarlos como fallidos. En semiabierto el tick despacha un lote de **1** mensaje, que es la llamada de prueba | Nada en el momento: la reserva se crea, se confirma y se cancela igual. La notificación llega tarde —tanto como dure la caída— en lugar de no llegar nunca |
| **Notificaciones** (consumidor) | A, B, C | **Ninguno** (§2) | Los que ya hay: TTL de la cola de espera, `retry-delay: 30s`, `max-retry-rounds: 5`, después DLQ. El consumidor es idempotente por `messageId` (`JdbcProcessedMessageStore`), que es la clave que autoriza esos reintentos | La DLQ. Es un fallback operativo, no automático: alguien la drena con `POST /actuator/messaging-dlq/replay` | Nada, hasta que no recibe el mail. La reserva existe y es consultable por API: el canal degradado es la notificación, no el dato |
| **IdP / JWKS** | B, C | **Ninguno** (§2) | Los de Nimbus, con el cache de claves subido a 15 m | **No hay, y no debe haberlo**: aceptar un token sin validar no es degradar | `401` si su token no valida; si el JWKS está caído y la clave no está cacheada, `500`. Es el único lugar donde una dependencia caída propaga el error en lugar de degradar, y es deliberado |

---

## 4. Clasificación de fallos

La clasificación ya existe y está escrita en `RestCityCatalogClient`; este diseño la usa tal
cual y le agrega una distinción (`429`) y una regla nueva (qué cuenta para el circuito).

| Excepción o respuesta | ¿Cuenta para el circuito? | ¿Se reintenta? | Por qué |
|---|---|---|---|
| `200` con cuerpo válido | No (es un éxito) | — | — |
| `404`, o `200` con cuerpo vacío | **No** | **No** | Es una respuesta de negocio: "esa ciudad no existe". El proveedor está sano. Contarlo abriría el circuito ante una ráfaga de códigos mal tipeados, justo cuando todo funciona |
| `429 Too Many Requests` → `AirportCatalogThrottledException` | **Sí** | **No** (cambia respecto de hoy) | El proveedor está diciendo explícitamente que bajemos el ritmo. Reintentar es desobedecerlo y empeorar su saturación; lo correcto es contarlo para que el circuito abra y pare el tráfico de verdad, y caer al *stale* |
| `5xx` → `AirportCatalogUnavailableException` | **Sí** | **Sí** | El pedido no tiene nada de malo, el problema es del otro lado y por definición puede haberse resuelto en 100 ms |
| Read timeout / connect timeout / `ResourceAccessException` | **Sí** (y además como *llamada lenta* si superó 900 ms) | **Sí** | Es el modo de falla B en estado puro: contarlo como lento es lo que hace que el circuito abra **antes** de que el proveedor devuelva errores |
| `4xx` que no es 404 ni 429 (401, 403, credencial vencida) → `AirportCatalogIntegrationException` | **No** | **No** | Es permanente: el mismo pedido da el mismo resultado hasta que alguien rote la API key. Un circuito abierto lo **escondería** detrás de un `503` genérico y retrasaría el diagnóstico. Se alerta por métrica (§8), que es la respuesta correcta a un fallo que necesita una persona |
| `200` con cuerpo ilegible o sin `code` → `AirportCatalogIntegrationException` | **No** | **No** | Integración rota, no proveedor caído. Insistir da lo mismo |
| `CallNotPermittedException` (circuito abierto) | **No** (por definición: el circuito no cuenta sus propios rechazos) | **No** | Se **traduce** a `AirportCatalogUnavailableException` en el borde del decorador, para que `CachingAirportCatalog` dispare el *stale* y el `ReservationExceptionHandler` siga devolviendo `503` y no un `500` por una excepción que no sabe manejar |
| `BulkheadFullException` | **Sí** | **No** | Significa "ya hay 50 llamadas nuestras en vuelo contra el catálogo". Reintentar es empujar contra una puerta cerrada; contarlo es correcto porque la saturación propia es un síntoma de la lentitud ajena |
| Presupuesto del itinerario agotado → `AirportCatalogUnavailableException` | **No** | **No** | El corte lo pusimos nosotros, no el proveedor. Contarlo mezclaría nuestra política de latencia con la salud del catálogo |
| `IllegalArgumentException` (código vacío) | **No** | **No** | Es un bug nuestro. Un circuito que se abre por un bug propio oculta el bug |
| **Redis**: `RedisCommandTimeoutException`, `RedisConnectionFailureException`, cualquier `RuntimeException` de Lettuce | **Sí** | **No** | Todos degradan al origen igual; el circuito existe sólo para no pagar los 200 ms |
| **Broker**: `AmqpException`, timeout del confirm, confirm negativo | **Sí** | **No** en proceso (lo hace el outbox) | Un `nack` es el broker rechazando por cola llena o alarma de disco: transitorio y del lado de él |
| **Broker**: mensaje devuelto (`publisher-returns`, sin binding) | **No** | No en proceso | Es un error de topología nuestro. Abrir el circuito por un routing key sin cola frenaría la entrega de **todos** los demás eventos, que estaban saliendo bien |
| **Broker**: `JsonProcessingException` al serializar → `IllegalStateException` | **No** | **No** | Permanente y propio. Va a `FAILED` con `OutboxFailure.PERMANENT`, como ya hace el dispatcher |
| **Base**: `SQLTransientConnectionException` (pool agotado) | No hay circuito | **No** | Un reintento contra un pool agotado alarga la cola. Corresponde rechazar con `503` + `Retry-After` |
| **Base**: `OptimisticLockingFailureException` | No hay circuito | **No** | Es una carrera entre dos clientes, no una falla de infraestructura. Ya se traduce a `409 CONCURRENT_UPDATE` y lo resuelve el cliente releyendo el `ETag` |

---

## 5. Orden de los decoradores

### La regla general

```
cache  →  circuit breaker  →  bulkhead  →  retry  →  cliente HTTP
```

De afuera hacia adentro. Cada frontera está elegida, no heredada del orden por defecto de
la librería:

1. **El cache va afuera de todo.** Un hit no tiene que consumir una llamada del circuito ni
   un permiso del bulkhead: no toca la red. Si el circuito estuviera por encima del cache,
   un circuito abierto dejaría sin servir datos que estaban guardados y frescos —el peor
   resultado posible—. Y el *stale-while-error* **necesita** estar por encima del circuito
   para poder reaccionar al `CallNotPermittedException`: el fallback es un `catch` alrededor
   de todo lo demás.

2. **El circuit breaker va por fuera del retry**, que es la decisión que el enunciado pide
   justificar. Un reintento por dentro y uno por fuera no cuentan lo mismo:

   - **Con el retry adentro** (lo elegido), la unidad que cuenta el circuito es *resolver una
     ciudad*, con sus hasta 2 intentos incluidos. Un pedido desafortunado vale **un** voto.
   - **Con el retry afuera**, cada intento HTTP sería un voto, y un solo evento de red
     pesaría el doble o el triple según cuántos intentos haya hecho la política de ese día.
     El umbral "50 % de fallo" dejaría de significar "la mitad de las resoluciones falla"
     para significar "la mitad de los paquetes falla", que no es lo que queremos medir.
   - Y la razón decisiva: con el circuito adentro, un circuito **abierto** haría que el bucle
     de reintentos gire sobre `CallNotPermittedException` —durmiendo backoff para nada— a
     menos que se le enseñe a ignorar esa excepción en particular. Con el circuito afuera, un
     circuito abierto cortocircuita el retry entero y la llamada cuesta microsegundos, que es
     exactamente lo que estamos comprando.
   - **El costo, dicho de frente**: el circuito sólo ve los fallos que sobrevivieron al retry,
     así que abre más tarde. Se compensa con dos cosas: el mínimo de llamadas es bajo (20,
     ≈ 3 itinerarios) y se cuentan las **llamadas lentas**, que aparecen antes que los fallos.

3. **El bulkhead va entre el circuito y el retry.** No en el lugar más interno, que es donde
   lo pondría el orden por defecto. Si estuviera adentro del retry, una rechazo por bulkhead
   lleno se reintentaría, que es exactamente al revés de lo que hay que hacer cuando ya hay
   demasiadas llamadas propias en vuelo. Arriba del retry, el permiso representa "este pedido
   está ocupando al proveedor" y se sostiene durante toda la secuencia reintentada, en lugar
   de soltarse y volver a tomarse entre intentos —lo que permitiría que una ráfaga pasara el
   bulkhead en el primer intento y lo duplicara en el segundo—. El precio es que el permiso
   queda tomado durante el backoff: con un techo de 200 ms y 50 permisos, es aceptable.

4. **El retry va pegado al cliente HTTP**, donde la clasificación de la respuesta todavía
   está fresca y no hace falta volver a mirar códigos de estado.

### Catálogo de ciudades

```
AirportExistenceValidator                  (application: sólo conoce el puerto)
└── CachingAirportCatalog                  (hit → 0 llamadas; stale-while-error; marca la degradación)
    └── BudgetedCityCatalogFanout          (NUEVO: presupuesto de 1,6 s para el itinerario + fan-out en virtual threads)
        └── CatalogAirportCatalog          (puerto ↔ cliente)
            └── CircuitBreakingCityCatalogClient   (NUEVO: circuito + traducción de CallNotPermitted)
                └── BulkheadCityCatalogClient      (NUEVO: ≤ 50 llamadas en vuelo, sin cola de espera)
                    └── RetryingCityCatalogClient  (2 intentos, consciente del presupuesto)
                        └── RestCityCatalogClient  (connect 300 ms / read 700 ms; clasifica la respuesta)
```

El fan-out es la pieza que faltaba y la que convierte "cada ciudad tiene su techo" en "el
itinerario tiene el suyo": resuelve primero los hits del cache en una sola lectura y lanza
las ciudades que faltan **en paralelo** sobre threads virtuales, con un `deadline` único.
Lo que no contestó cuando el presupuesto se agota se trata como no disponible, y sigue el
camino de fallback normal. Ocho ciudades dejan de costar la suma y pasan a costar el máximo.

### Redis

```
MeteredCacheStore                 (las métricas se siguen viendo aunque el circuito esté abierto)
└── CircuitBreakingCacheStore     (NUEVO: circuito + política de fallback por prefijo de clave)
    └── RedisCacheStore           (timeout 200 ms; ya degrada al origen ante error)
```

El medidor queda **por fuera** del circuito a propósito: si estuviera adentro, con el
circuito abierto dejaríamos de publicar `reservations.cache.gets` y el panel mostraría
silencio en lugar de degradación. Ahí no hay retry porque no hay nada que reintentar.

### RabbitMQ

```
OutboxDispatchScheduler                    (infraestructura: consulta el estado del circuito antes del tick)
└── OutboxDispatcherService                (application: no sabe que hay un circuito)
    └── EventPublisherPort
        └── CircuitBreakingEventPublisher  (NUEVO: circuito + traducción a EventPublisherUnavailableException)
            └── RabbitEventPublisher       (confirm-timeout 5 s)
```

Acá el orden tiene una vuelta más: además del decorador, **el scheduler consulta el estado
del circuito antes de disparar el tick**. Sin eso, el circuito ahorraría los 7 s por mensaje
pero el relay igual reclamaría 50 filas de la base para descartarlas. Con el gate:

| Estado | Qué hace el tick |
|---|---|
| `CLOSED` | Lote normal de 50 |
| `OPEN` | No se dispara: ni consulta la base ni toca el broker. Métrica `reservations.outbox.dispatch.skipped` |
| `HALF_OPEN` | Lote de **1**: ese mensaje es la llamada de prueba |

Y si el circuito abre en mitad de un lote, `CircuitBreakingEventPublisher` lanza
`EventPublisherUnavailableException`, que `OutboxDispatcherService` distingue de un fallo de
publicación: libera los mensajes restantes con `release(...)` y **no les gasta el intento**.
Ese es todo el punto del circuito en esta dependencia.

### PostgreSQL

Sin decoradores. Los límites son de configuración —pool, `connection-timeout`,
`statement_timeout`, `@Transactional(timeout)`— y el mecanismo de última instancia es el
health indicator `db`, que sí está encendido.

---

## 6. Presupuesto de tiempo del pedido

**No hay un timeout global del pedido, y no lo va a haber**: con threads virtuales y sin un
filtro que corte, el techo de un pedido es la **suma de los techos de sus componentes**. Por
eso cada componente tiene que tener el suyo, y por eso el presupuesto se declara acá.

**Objetivo declarado: `POST /v1/reservations` ≤ 4 s en el peor caso, `PUT` ≤ 4,5 s.**
El objetivo de p99 con todo sano es 400 ms; los 4 s son el techo que no se puede cruzar.
Sale de que del otro lado hay una persona esperando el resultado de un formulario: más allá
de unos pocos segundos el cliente reintenta, y un reintento sobre un sistema degradado es
exactamente lo que no queremos.

### `POST /v1/reservations`, peor caso, cache frío y catálogo degradado

| Paso | Techo | De dónde sale |
|---|---|---|
| Filtro de correlación + JWT | ~0 ms | El JWKS está cacheado; validar una firma es CPU |
| Rate limit (Redis) | 200 ms → **0 ms** con el circuito abierto | `spring.data.redis.timeout` |
| Lectura de cache de ciudades (hasta 8 claves) | 200 ms → **0 ms** con el circuito abierto | Una sola lectura agrupada, no 8 |
| **Validación del itinerario (≤ 8 ciudades)** | **1 600 ms** | Presupuesto duro del fan-out. Cada ciudad: `connect 300 + read 700` = 1 s por intento, 2 intentos + backoff ≤ 200 ms = 2,2 s de techo propio — que el presupuesto recorta a 1,6 s. En paralelo, 8 ciudades cuestan lo que la más lenta, no la suma |
| Escritura de cache de ciudades | 200 ms → 0 ms con el circuito abierto | Agrupada, igual que la lectura |
| Obtener conexión del pool | **1 000 ms** | `hikari.connection-timeout`, bajado de 3 s |
| Transacción: `findByEmail` + `findByIdempotencyKey` + `findOrRegister` + `save` + `enqueue` + auditoría | **1 000 ms** | `statement_timeout: 2s` en el driver acota cada sentencia; `@Transactional(timeout = 2)` acota el conjunto. 1 s es el techo realista de 6 sentencias por índice |
| Serialización + filtros de salida | ~100 ms | |
| **Total** | **≈ 3,9 s** | Entra en el presupuesto de 4 s |

Con el circuito del catálogo **abierto** el mismo pedido cuesta ~1,1 s: la validación
completa se resuelve contra el *stale* sin tocar la red. Ése es el valor concreto del
circuito — y la razón por la que el presupuesto se puede sostener aun con el proveedor caído.

### `PUT /v1/reservations/{id}`

| Paso | Techo | Nota |
|---|---|---|
| JWT + rate limit | 200 ms | |
| `findById` (fuera de transacción) + chequeo de acceso y de versión | **1 000 ms** | Conexión + una consulta con `@EntityGraph`. Corta temprano ante `If-Match` viejo: un `409` no llega a tocar el catálogo |
| Validación del itinerario | **1 600 ms** | Mismo presupuesto que el `POST` |
| Transacción de escritura | **1 500 ms** | Conexión + `save` + `enqueue` + auditoría |
| Serialización | ~100 ms | |
| **Total** | **≈ 4,4 s** | Entra en el presupuesto de 4,5 s |

### Qué se recorta si no entra

En ese orden, y el orden importa: primero se recorta lo que no cambia el resultado.

1. **El segundo intento.** El retry es consciente del presupuesto: si lo que queda no alcanza
   para un intento completo (1,1 s), no reintenta. Ahorra hasta 1,2 s por ciudad sin cambiar
   nada más.
2. **El paralelismo por encima de 8 ciudades.** Un itinerario con más tramos no agranda el
   presupuesto: agranda el fan-out. El presupuesto es del itinerario, no de la ciudad.
3. **La escritura del cache.** Si el presupuesto se agotó, el `put` se hace sin esperar: el
   dato se guarda para el próximo pedido, no para éste.
4. **La validación misma.** Agotado el presupuesto, lo que quedó sin resolver se trata como
   no disponible y entra al fallback: *stale* si hay algo guardado, `503` si no. Nunca se
   acepta una ciudad sin validar para ganar tiempo.

Lo que **no** se recorta: el `statement_timeout`, el chequeo de idempotencia y el de
versión. Recortar ahí cambia la corrección, no la latencia.

---

## 7. Interacción con lo ya construido

### El *stale-while-error* del catálogo: **se conserva, y cambia de forma**

Es el fallback, y el circuito no lo reemplaza: lo **alimenta**. Antes del circuito, llegar al
*stale* costaba 7,8 s por ciudad; con el circuito abierto cuesta microsegundos. Son piezas
complementarias — el circuito decide *cuándo* no llamar, el *stale* decide *qué contestar*.

Dos cambios:

- **La ventana de 2 h sólo aplica a los positivos.** Un negativo *stale* es servir "esa
  ciudad no existe" sobre un dato viejo, y eso rechaza reservas válidas con un `400
  UNKNOWN_AIRPORT` que le dice al usuario que corrija un itinerario que estaba bien. Es el
  error más caro que puede cometer este sistema: no es reintentable y le miente al cliente
  sobre la causa. Un negativo vencido, con el origen caído, se resuelve con `503` +
  `Retry-After`, que es reintentable y honesto. Los positivos sí se sirven vencidos: servir
  una ciudad que se dio de baja hace dos horas acepta una reserva que el resto del flujo
  puede corregir después, y ese error sí es recuperable.
- **Deja de ser silencioso.** Hoy el único rastro es un `log.warn`. Se agregan métrica y
  header (§8): un fallback que sirve datos viejos sin decirlo es indistinguible de un
  sistema sano, y eso es exactamente lo que hace que nadie se entere de la caída del
  proveedor hasta que alguien reclama.

### El fallback en memoria de la caché: **se conserva, con el alcance recortado**

Hoy `InMemoryCacheStore` se usa cuando `reservations.cache.redis.enabled=false` —el modo de
los tests y del arranque sin Redis— y eso no cambia.

Lo que se agrega es su uso como fallback **en caliente**, y sólo para el prefijo `city:`. La
razón de no usarlo para todo: `reservation:version:*` se invalida activamente en cada
escritura, y una copia por instancia no recibe esa invalidación. Con N instancias, un `ETag`
servido desde la memoria de la instancia A después de que B modificó la reserva produce un
`412` sobre un `If-Match` correcto, o peor, un `304` sobre un recurso que cambió. Un cache
degradado puede permitirse ser lento; no puede permitirse ser incoherente. Para esas claves,
el circuito abierto significa **miss**, que es siempre correcto.

### El outbox: **se conserva entero, y el circuito lo protege**

El outbox no es redundante con el circuito: son cosas distintas. El outbox garantiza que el
mensaje **no se pierde**; el circuito garantiza que el mensaje **no gasta sus intentos**
contra un broker que sabemos caído. Sin circuito, `max-attempts: 10` se consume en minutos
durante una caída larga y `retry-ceiling: 6h` nunca llega a actuar, que es justo al revés
de lo que ese par de números quería decir.

### Qué queda redundante

| Pieza | Estado |
|---|---|
| El tercer intento del retry del catálogo | **Redundante.** Con el circuito midiendo y el *stale* respondiendo, el tercer intento agrega 1,2 s de peor caso y recupera casi nada: si dos intentos separados por 200 ms fallaron, el proveedor no está teniendo un microcorte. Baja a 2 |
| El reintento del `429` | **Contraproducente.** Se elimina (§4) |
| `connection-timeout: 3000` de Hikari | **Mal calibrado, no redundante.** Baja a 1 s: esperar 3 s por una conexión cuando las consultas tardan milisegundos sólo alarga la cola |
| `UnavailableDeadLetterQueue` y `LoggingEventPublisher` | **Se conservan tal cual.** Son el camino de "arrancar sin broker", que es otra cosa que la degradación en caliente y sigue siendo un requisito |
| Health indicators de `redis` y `rabbit` apagados | **Se conservan.** El circuito refuerza la decisión: ahora hay una métrica que dice que la dependencia está caída sin necesidad de marcar la instancia como `DOWN` |

---

## 8. Cómo se entera el sistema de que está degradado

Ningún fallback de este diseño es silencioso. Por cada uno, tres niveles:

| Degradación | Métrica | Log | Visible para el cliente |
|---|---|---|---|
| Catálogo servido *stale* | `reservations.catalog.stale_served{reason=circuit_open\|retries_exhausted\|budget_exhausted}` | `WARN` con el código y la edad del dato | Header **`X-Degraded: airport-catalog`** en la respuesta `2xx` |
| Circuito abierto (cualquiera) | `resilience4j.circuitbreaker.state{name=catalog\|redis\|broker}` y `...calls{kind=failed\|successful\|not_permitted}` | `WARN` en cada transición de estado | — |
| Catálogo con error permanente | `reservations.catalog.errors{kind=integration}` | `ERROR` con el estado HTTP | `500` (no `503`: no es reintentable) |
| Redis degradado | `reservations.cache.errors{operation}`, ya existente | `WARN`, ya existente | — |
| Presupuesto del itinerario agotado | `reservations.catalog.budget_exhausted` | `WARN` con cuántas ciudades quedaron sin resolver | `X-Degraded` o `503`, según haya *stale* o no |
| Relay frenado por circuito | `reservations.outbox.dispatch.skipped` + el `lag` del outbox, ya existente | `WARN` una vez por transición, no por tick | — |

El header es la pieza que falta hoy y la que convierte la degradación en algo auditable
desde afuera: un frontend puede mostrar "datos de catálogo desactualizados" y un test de
integración puede afirmar que el *stale* se usó. Se emite desde un `OncePerRequestFilter` de
infraestructura que lee una marca dejada por `CachingAirportCatalog` en un `ScopedValue`; ni
el dominio ni la aplicación se enteran.

**Alertas que este diseño justifica** (el umbral es la parte que no se puede dejar para
después):

- `resilience4j.circuitbreaker.state{name=catalog} == open` por más de 2 min → aviso.
- `reservations.catalog.errors{kind=integration} > 0` sostenido 5 min → **página a alguien**:
  es el caso que ningún mecanismo automático resuelve (una API key vencida).
- `reservations.outbox.pending` creciendo 15 min → aviso; el circuito del broker abierto
  explica la causa sin tener que mirar logs.

---

## 9. Qué cambia en el código

### `pom.xml`

**Se agrega** `resilience4j-circuitbreaker`, `resilience4j-bulkhead` y
`resilience4j-micrometer` (Apache 2.0, sin servicio externo ni plan pago). **No** se agrega
`resilience4j-spring-boot3`: ese módulo trae AOP y anotaciones, y una anotación
`@CircuitBreaker` es exactamente lo que este diseño no quiere que pueda aparecer en un
servicio de aplicación. Con los módulos base, los circuitos se cablean a mano en
`AdapterConfiguration`, igual que ya se cablean los decoradores de cache y de reintentos —la
misma razón por la que este repositorio usó un decorador en lugar de `@Cacheable`—.

### `domain`

**Nada.** No se toca.

### `application`

| Pieza | Cambio |
|---|---|
| `port/out/AirportCatalogPort` | **Se reemplaza** `boolean exists(AirportCode)` por `Set<AirportCode> unknown(Collection<AirportCode>)`. Es el cambio que habilita el presupuesto y el paralelismo: mientras el puerto pregunte de a una ciudad, el adaptador no puede hacer nada mejor que un bucle en serie |
| `service/AirportExistenceValidator` | **Se simplifica**: pasa de recorrer los aeropuertos a delegar el conjunto y lanzar `UnknownAirportException` con lo que vuelve. Sigue sin saber que existe un circuito |
| `exception/AirportCatalogThrottledException` | **Nuevo**, extiende `AirportCatalogUnavailableException`. Es lo que permite decidir "no reintentar, pero sí contar" sin que la aplicación importe nada de la librería |
| `exception/EventPublisherUnavailableException` | **Nuevo**, extiende `EventPublishException`. Es la traducción de `CallNotPermittedException` que cruza el puerto |
| `service/OutboxDispatcherService` | **Se modifica**: distingue `EventPublisherUnavailableException` de un fallo de publicación — libera el resto del lote y corta el ciclo **sin gastar intentos** |
| `service/CreateReservationTransaction`, `ModifyReservationTransaction` | `@Transactional(timeout = 2)`. Es Spring, que la aplicación ya usa; no es la librería de resiliencia |

### `infrastructure`

| Paquete | Pieza | Cambio |
|---|---|---|
| `adapter/out/airport` | `CachingAirportCatalog` | **Se modifica**: implementa el puerto en bloque, distingue *stale* positivo de negativo y marca la degradación para el header |
| `adapter/out/airport` | `BudgetedCityCatalogFanout` | **Nuevo**: presupuesto de 1,6 s y fan-out sobre threads virtuales |
| `adapter/out/airport/catalog` | `CircuitBreakingCityCatalogClient` | **Nuevo**: circuito + traducción de `CallNotPermittedException` |
| `adapter/out/airport/catalog` | `BulkheadCityCatalogClient` | **Nuevo**: 50 llamadas en vuelo, sin cola |
| `adapter/out/airport/catalog` | `RetryingCityCatalogClient` | **Se modifica**: 3 → 2 intentos, no reintenta `AirportCatalogThrottledException`, consulta el presupuesto restante antes de dormir. El `Sleeper` inyectable se conserva |
| `adapter/out/airport/catalog` | `RestCityCatalogClient` | **Se modifica** sólo en una línea: el `429` lanza `AirportCatalogThrottledException`. La clasificación ya era correcta y es la base de todo lo demás |
| `cache` | `CircuitBreakingCacheStore` | **Nuevo**: circuito + fallback por prefijo de clave |
| `cache` | `MeteredCacheStore`, `RedisCacheStore`, `InMemoryCacheStore` | **Se conservan**. `InMemoryCacheStore` gana un segundo uso como L1 de `city:` |
| `adapter/out/messaging` | `CircuitBreakingEventPublisher` | **Nuevo** |
| `adapter/in/scheduling` | `OutboxDispatchScheduler` | **Se modifica**: gate por estado del circuito y lote de 1 en semiabierto |
| `adapter/in/rest` | `DegradationHeaderFilter` | **Nuevo**: emite `X-Degraded` |
| `adapter/in/rest` | `ReservationExceptionHandler` | **Se modifica**: agrega el manejo de `SQLTransientConnectionException` / `CannotCreateTransactionException` → `503` + `Retry-After: 1`, hoy caen en el `500` genérico |
| `config` | `ResilienceConfiguration` | **Nuevo**: los tres `CircuitBreakerRegistry` y el `Bulkhead`, más su binder de Micrometer |
| `config` | `AirportCatalogProperties`, `CacheProperties`, `MessagingProperties` | **Se modifican**: cada una gana su sub-record `CircuitBreakerProperties`. Los umbrales son configuración, no constantes |
| `config` | `AdapterConfiguration` | **Se modifica**: arma la cadena de decoradores de §5 |

### `application.yml`

```yaml
reservations:
  airport-catalog:
    connect-timeout: 300ms        # era 500ms
    read-timeout: 700ms           # era 2s
    itinerary-budget: 1600ms      # NUEVO: el techo que faltaba
    retry:
      max-attempts: 2             # era 3
      initial-backoff: 100ms
      max-backoff: 200ms          # era 500ms
    bulkhead:
      max-concurrent-calls: 50
      max-wait: 0ms
    circuit-breaker:
      sliding-window-size: 50
      minimum-number-of-calls: 20
      failure-rate-threshold: 50
      slow-call-duration-threshold: 900ms
      slow-call-rate-threshold: 60
      wait-duration-in-open-state: 5s
      permitted-calls-in-half-open-state: 4
      automatic-transition-from-open-to-half-open: true
  cache:
    circuit-breaker:
      sliding-window-size: 100
      minimum-number-of-calls: 30
      failure-rate-threshold: 50
      slow-call-duration-threshold: 150ms
      slow-call-rate-threshold: 60
      wait-duration-in-open-state: 10s
      permitted-calls-in-half-open-state: 5
      automatic-transition-from-open-to-half-open: true
  messaging:
    circuit-breaker:
      sliding-window-size: 20
      minimum-number-of-calls: 5
      failure-rate-threshold: 60
      slow-call-duration-threshold: 2s
      slow-call-rate-threshold: 60
      wait-duration-in-open-state: 60s
      permitted-calls-in-half-open-state: 2
      automatic-transition-from-open-to-half-open: true

spring:
  datasource:
    hikari:
      connection-timeout: 1000    # era 3000
      data-source-properties:
        options: "-c statement_timeout=2000"   # NUEVO: el corte duro, del lado del driver
  jpa:
    properties:
      jakarta.persistence.query.timeout: 1000  # NUEVO: acota las consultas JPA
```

### `src/test`

| Pieza | Cambio |
|---|---|
| `HexagonalArchitectureTest` | **Nueva regla** `resilienceStaysInInfrastructure`: ninguna clase de `domain..` ni de `application..` puede depender de `io.github.resilience4j..`. Es la restricción del enunciado convertida en test, con la misma forma que las reglas de cache, seguridad y mensajería que ya están |
| Tests de `RetryingCityCatalogClient` | Se extienden con el caso "presupuesto agotado" y "429 no se reintenta". El `Sleeper` inyectable hace que sigan corriendo sin dormir |
| Nuevos tests de circuito | Con un `CityCatalogClient` falso que falla a demanda: se verifica que el circuito abre a las 20 llamadas con 50 % de fallo, que en abierto no llega ni una llamada al delegado, y que a los 5 s pasa solo a semiabierto. Sin red y sin esperar tiempo real: el circuito se construye con un `Clock` de test |
| Test de presupuesto | Un itinerario de 8 ciudades contra un catálogo que tarda 5 s devuelve en ≤ 1,6 s |

---

## 10. De dónde sale cada número

Ninguno es el default de la librería. Los defaults de resilience4j —ventana de 100, 50 % de
fallo, 60 s abierto, 10 llamadas en semiabierto— están pensados para un servicio genérico de
alto tráfico; acá el tráfico, el costo de la llamada y el valor del fallback son distintos
para cada dependencia, y eso es justamente lo que fija los umbrales.

| Número | De dónde sale |
|---|---|
| Catálogo: `read-timeout` **700 ms** | `GET /city/{code}` es un lookup por clave contra MySQL sobre una tabla de unos pocos miles de ciudades: el tiempo de servicio esperado es de un dígito en milisegundos. 700 ms es ~20× el p99 esperado, que cubre una pausa de GC del proveedor y un salto de red, y no más. Los 2 s de hoy eran ~200× y se eligieron cuando no había presupuesto de pedido que respetar. **Este es el primer valor que hay que reemplazar por el medido**: en cuanto `http.client.requests` publique el histograma del catálogo, el timeout pasa a ser p99 × 5 |
| Catálogo: `connect-timeout` **300 ms** | Establecer una conexión es rápido o no va a pasar. En la misma red, el TCP handshake son microsegundos; 300 ms cubre un DNS lento. Bajó de 500 ms sólo porque ahora forma parte del techo por intento (1 s exactos: 300 + 700), y un techo redondo es más fácil de razonar en el presupuesto |
| Catálogo: **2 intentos** | El primer reintento recupera el microcorte, que es el 90 % de los fallos transitorios de red. El tercero cuesta 1,2 s más de peor caso y sólo ayuda si el proveedor se cae y se recupera en menos de 400 ms, que es una ventana demasiado angosta para pagarla en cada pedido. Y con el circuito y el *stale* atrás, el costo de rendirse temprano es bajo |
| Catálogo: backoff **100 ms → 200 ms**, jitter sobre la mitad superior | El 100 ms inicial se conserva: es el orden de un reintento que llega después del microcorte y antes de que el usuario note algo. El techo baja de 500 a 200 ms porque con 2 intentos nunca se usaría el tercer escalón, y 200 ms es lo que entra en el presupuesto. El jitter sobre la mitad superior (50–100 % del valor calculado) es el que ya está en `RetryingCityCatalogClient` y no hay razón para cambiarlo: desincroniza N instancias sin hacer impredecible el techo |
| Catálogo: presupuesto **1,6 s** | Sale del presupuesto del pedido hacia atrás: 4 s de techo − 2 s de persistencia − 0,4 s de todo lo demás. Es lo que sobra, y alcanza para un intento completo (1 s) más un reintento parcial |
| Catálogo: ventana **50**, mínimo **20** | Un itinerario típico son 4 ciudades y uno complejo 8. 20 llamadas ≈ 3 itinerarios: suficiente para que una ráfaga desafortunada de un solo pedido no abra el circuito, y poco como para detectar una caída en segundos. La ventana de 50 le da al circuito memoria de ~7 itinerarios, que es el orden de lo que pasa mientras dura un incidente breve |
| Catálogo: fallo **50 %** | Por encima de la mitad ya no hay duda de que el problema es del proveedor. Con 20 llamadas mínimas, 10 fallos. Más bajo abriría ante un proveedor parcialmente degradado que todavía sirve la mitad de las consultas —y media validación buena vale más que ninguna—; más alto retrasaría la apertura hasta que casi todo falle |
| Catálogo: lento **900 ms / 60 %** | 900 ms es apenas por debajo del techo de un intento (1 s): si una resolución de ciudad tardó eso, el primer intento ya agotó su timeout. Es el modo de falla B detectado **antes** de que se convierta en errores, que es la única forma de que el circuito ayude con una dependencia lenta en lugar de caída |
| Catálogo: abierto **5 s** | Corto a propósito, y es el número que más se aparta del default de 60 s. El fallback sirve datos que envejecen, así que cada segundo de circuito abierto tiene un costo en frescura; y probar cuesta poco: 4 llamadas de ≤1 s. Con 5 s, una caída de 1 minuto se paga con ~12 rondas de prueba (~48 llamadas desperdiciadas, nada) y la recuperación se detecta en 5 s |
| Catálogo: semiabierto **4** | Dos números redondos: con 4 llamadas, el umbral de 50 % se decide con 2 fallos, y 4 es el tamaño de un itinerario típico —una sola reserva alcanza para decidir si el proveedor volvió— |
| Catálogo: bulkhead **50** | El `api-catalog` es una aplicación Spring Boot con un pool a MySQL del orden de 10 conexiones. Más de ~50 pedidos nuestros en vuelo no se atienden más rápido: se encolan adentro de él, y ahí nuestro timeout se convierte en su saturación. 50 permisos con espera 0 es el límite que hace que la cola sea nuestra —y visible— en lugar de suya |
| Redis: sin reintentos | El costo de un miss es ir al origen, que es lo que ya se hace. Un reintento paga 200 ms más por un dato opcional |
| Redis: lento **150 ms** | El timeout es 200 ms; una operación que tardó 150 ms ya está en la zona donde el cache dejó de ser un atajo. Una lectura sana de Redis en la misma red es de un dígito en milisegundos: 150 ms es 20× eso |
| Redis: ventana **100**, mínimo **30** | Es la dependencia con más llamadas por pedido (hasta 17 en un `POST`), así que 30 llamadas son ~2 pedidos: se llega al mínimo enseguida y la ventana de 100 sigue representando pocos segundos de tráfico |
| Redis: abierto **10 s** | Un failover de Redis o un reinicio de contenedor tarda del orden de 5 a 15 s. Menos de 10 s sería probar contra algo que todavía no volvió, y cada ronda de prueba cuesta 5 × 200 ms |
| Broker: mínimo **5**, ventana **20** | El relay publica poco y en lotes: un tick son hasta 50 publicaciones, pero un tick cada 5 s. Pedir 20 llamadas mínimas retrasaría la apertura hasta la mitad del primer lote. Cinco fallos consecutivos de publicación contra un broker no son ambiguos |
| Broker: fallo **60 %** | Más alto que el resto porque un fallo aislado de publicación puede ser un mensaje puntual (una cola llena por `reject-publish` de un solo routing key) y no el broker entero. Con 5 llamadas mínimas, 3 fallos abren |
| Broker: lento **2 s** | Un `publisher confirm` contra un broker sano es de un dígito en milisegundos. Por encima de 2 s el broker está bajo control de flujo o con alarma de disco: sigue contestando, pero el relay no va a drenar la cola a ese ritmo. El `confirm-timeout` de 5 s se conserva como corte duro |
| Broker: abierto **60 s** | El único caso donde el default largo es el correcto. Nadie espera, el mensaje está a salvo en el outbox, y **probar es caro**: 2 s de connect + 5 s de confirm por cada llamada de prueba. Además el propio backoff del outbox ya trabaja en la escala de 5 s a 5 m, así que un circuito de 60 s no atrasa nada que no estuviera atrasado |
| Broker: semiabierto **2** | Una publicación de prueba por tick, dos ticks para decidir. Con lotes de 1 en semiabierto, eso son exactamente 2 mensajes arriesgados por intento de recuperación |
| Hikari: `connection-timeout` **1 s** | Con 20 conexiones y consultas por índice de pocos milisegundos, una espera de 3 s por una conexión significa que la cola tiene cientos de pedidos adelante. Esperar más sólo agranda la cola: a partir de cierto punto, rechazar es la respuesta correcta. 1 s es el tiempo en el que un pico legítimo se absorbe y una saturación real se hace visible |
| `statement_timeout` **2 s** | Ninguna sentencia de este sistema tiene derecho a tardar más: el peor caso documentado es la paginación con tres consultas sobre índices. Va en el driver y no sólo en JPA porque `jakarta.persistence.query.timeout` no cubre el `flush` ni el `commit`, que es justo donde pega una base lenta |
| `@Transactional(timeout = 2)` | El techo del conjunto, no de cada sentencia. Cierra el hueco de una transacción con seis sentencias de 1,9 s cada una |
| Stale: **2 h** para positivos | Se conserva el valor ya decidido. Una ciudad que existía hace dos horas casi con certeza sigue existiendo: el catálogo de ciudades cambia con frecuencia mensual |
| Stale: **0** para negativos | No es un ajuste de número, es un cambio de política: servir un negativo viejo rechaza una reserva válida con un error que no es reintentable (§7) |
