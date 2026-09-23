# Auditoría de la mensajería

Revisión del diseño de [topología](topology.md) y de la implementación parcial
que hoy está en el repositorio, buscando las inconsistencias que no se ven
hasta que el sistema está en producción.

La entrada son las dos cosas a la vez, y eso importa: **el diseño del paso 13
ya corrige siete de los diez hallazgos de implementación**. Lo que hay que
mirar con más cuidado son los cuatro que *sobreviven* al diseño —H7, H8, H9 y
H12—, porque implementar el diseño tal como está los deja adentro.

- §1 [Hallazgos](#1-hallazgos)
- §2 [Cómo detectar cada uno](#2-cómo-detectar-cada-uno)
- §3 [Mitigación propuesta](#3-mitigación-propuesta)
- §4 [Limitaciones asumidas](#4-limitaciones-asumidas-no-son-hallazgos)
- §5 [Orden de remediación](#5-orden-de-remediación)
- §6 [Estado de la remediación](#6-estado-de-la-remediación)

> **Los doce hallazgos están remediados.** El detalle —qué se implementó y qué
> test lo verifica— está en §6, y las decisiones que hubo que tomar al hacerlo
> en el [ADR 0005](../adr/0005-garantias-de-entrega-y-remediacion-de-la-mensajeria.md).
> Este documento se conserva tal como se escribió: un ADR no se reescribe, y una
> auditoría tampoco.

---

## 1. Hallazgos

| # | Hallazgo | Categoría | Evidencia | Impacto | Severidad |
|---|---|---|---|---|---|
| **H1** | **Reintento en caliente, sin backoff.** `markFailed` devuelve el mensaje a `PENDING` sin ninguna marca de "no antes de", y `pollPending` filtra únicamente por estado. Con `dispatch-interval: 5s` y `max-attempts: 5`, los cinco intentos se consumen en **20 segundos**, todos contra un destino que sigue caído | Reintentos | [`InMemoryEventOutbox.java:86-94`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/out/outbox/InMemoryEventOutbox.java#L86) y [`:70-76`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/out/outbox/InMemoryEventOutbox.java#L70); [`OutboxDispatchScheduler.java:45`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/in/scheduling/OutboxDispatchScheduler.java#L45); `application.yml:232-234` | Un blip de 30 s del destino manda **el outbox entero** a `FAILED`. Todas las reservas creadas, confirmadas o canceladas en esa ventana quedan sin notificar de forma definitiva. El usuario no recibe nada y no hay reclamo posible porque la reserva sí existe | **Crítica** |
| **H2** | **`FAILED` es un estado terminal del que nadie se entera.** No hay DLQ, ni endpoint, ni métrica, ni reproceso: sólo una línea de log. `findByStatus` existe pero no lo expone nadie, y el actuator sólo publica `health,info,metrics` | DLQ | [`InMemoryEventOutbox.java:89-91`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/out/outbox/InMemoryEventOutbox.java#L89) y [`:113`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/out/outbox/InMemoryEventOutbox.java#L113); [`OutboxStatus.java:12-13`](../../src/main/java/com/edteam/reservations/application/outbox/OutboxStatus.java#L12) (el javadoc dice "dead letter"); `application.yml:299-305` | Combinado con H1: no sólo se pierde la notificación, sino que **no hay forma de saber que se perdió ni de reenviarla** después de arreglar la causa. El javadoc promete un dead letter que no existe | **Crítica** |
| **H3** | **El `messageId` nunca cruza el puerto.** El despachador pasa `message.event()`, no `message`; `DomainEvent` no tiene id propio. La clave de idempotencia que el javadoc de `NotificationPort` pide propagar **no existe del otro lado** | Idempotencia | [`OutboxDispatcherService.java:56`](../../src/main/java/com/edteam/reservations/application/service/OutboxDispatcherService.java#L56); [`NotificationPort.java:19-21`](../../src/main/java/com/edteam/reservations/application/port/out/NotificationPort.java#L19); [`DomainEvent.java:29-45`](../../src/main/java/com/edteam/reservations/domain/event/DomainEvent.java#L29); [`OutboxMessage.java:21`](../../src/main/java/com/edteam/reservations/application/outbox/OutboxMessage.java#L21) | Se declara at-least-once y se le pide idempotencia al consumidor, pero no se le da con qué. Cada reintento —y con H1 hay hasta cinco en 20 s— es un **"tu reserva quedó confirmada" duplicado** al mismo usuario. Ningún consumidor, por bien escrito que esté, puede deduplicar esto | **Alta** |
| **H4** | **`enqueue` no participa de la transacción.** Es un `put` sobre un `ConcurrentHashMap`, sin `TransactionSynchronization`. El contrato del puerto dice explícitamente lo contrario. En `Confirm`/`Cancel` el `UPDATE` con `@Version` se flushea recién al commit, así que el rollback por conflicto optimista ocurre **después** de haber encolado | Pérdida | [`InMemoryEventOutbox.java:55-62`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/out/outbox/InMemoryEventOutbox.java#L55) contra [`EventOutboxPort.java:22-24`](../../src/main/java/com/edteam/reservations/application/port/out/EventOutboxPort.java#L22); [`ConfirmReservationService.java:68-70`](../../src/main/java/com/edteam/reservations/application/service/ConfirmReservationService.java#L68); [`ReservationJpaEntity.java:73-75`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/out/persistence/entity/ReservationJpaEntity.java#L73) | El problema es el **inverso** del que documenta el stub: no se pierde un evento, se emite uno **fantasma**. Dos confirmaciones concurrentes: una gana, la otra hace rollback, y el usuario recibe dos avisos de confirmación por una sola confirmación. Cualquier fallo en el commit (constraint, caída de la conexión) notifica un hecho que nunca ocurrió | **Alta** |
| **H5** | **Cero métricas del outbox.** Micrometer se usa en el caché ([`MeteredCacheStore`](../../src/main/java/com/edteam/reservations/infrastructure/cache/MeteredCacheStore.java)) y en ningún lado de la mensajería. `enqueuedAt` se guarda en cada mensaje y **no lo lee nadie** | Observabilidad | grep de `MeterRegistry` en `src/main`: sólo `infrastructure/cache`; [`OutboxMessage.java:21`](../../src/main/java/com/edteam/reservations/application/outbox/OutboxMessage.java#L21); `application.yml:305` | No se puede responder ninguna de las tres preguntas operativas: cuántos hay pendientes, cuántos fallaron, hace cuánto está trabado el más viejo. Ni entrando a la base: el estado vive en el heap. Es lo que vuelve **invisibles** a H1 y H2 | **Alta** |
| **H6** | **El `sequence` se descarta en el borde del puerto y el lote no bloquea por reserva.** `StoredMessage` lleva `sequence`, `toOutboxMessage()` no lo copia. El despachador sigue al siguiente mensaje cuando uno falla, sin importar que sea de la misma reserva | Orden | [`InMemoryEventOutbox.java:132-134`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/out/outbox/InMemoryEventOutbox.java#L132) y [`:72`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/out/outbox/InMemoryEventOutbox.java#L72); [`OutboxMessage.java:21`](../../src/main/java/com/edteam/reservations/application/outbox/OutboxMessage.java#L21); [`OutboxDispatcherService.java:54-65`](../../src/main/java/com/edteam/reservations/application/service/OutboxDispatcherService.java#L54) | El javadoc del outbox afirma que ordena "para que un *modificada* no se notifique antes que su *creada*", y la garantía **muere en el borde del puerto**: el consumidor no recibe el número con el que ordenar. Un `created` que falla una vez y un `cancelled` que sale bien producen "se canceló tu reserva" antes de "registramos tu reserva" | **Alta** |
| **H7** | **Acoplamiento síncrono encubierto dentro de la transacción que encola.** `airportValidator.validate()` hace HTTP contra `api-catalog` —con hasta 3 intentos y ~6,5 s de peor caso *por ciudad*— **dentro** de `@Transactional`. El pool es de 20 conexiones con `connection-timeout: 3000` | Acoplamiento | [`CreateReservationService.java:77`](../../src/main/java/com/edteam/reservations/application/service/CreateReservationService.java#L77) + [`:101`](../../src/main/java/com/edteam/reservations/application/service/CreateReservationService.java#L101); [`ModifyReservationService.java:55`](../../src/main/java/com/edteam/reservations/application/service/ModifyReservationService.java#L55) + [`:80`](../../src/main/java/com/edteam/reservations/application/service/ModifyReservationService.java#L80); `application.yml:41-43` y `:144-162` | Un itinerario de 3 tramos contra un catálogo degradado retiene una conexión de base **~26 s**. Veinte pedidos así agotan el pool y la API entera devuelve error, incluidos los `GET`. El comentario de `application.yml:141-142` ya anticipa el agotamiento del pool, pero sólo por el read timeout: **no ve que la llamada está adentro de la transacción**. El diseño empeora esto al meter el `INSERT` del outbox en esa misma transacción larga. **No lo corrige el paso 13** | **Alta** |
| **H8** | **(Diseño) La regla de descarte por `sequence` borra eventos legítimos.** La regla 2 del contrato del consumidor dice "descartar un mensaje cuyo `sequence` sea menor o igual al último ya aplicado para esa reserva". Esa regla es correcta para un *duplicado del mismo evento* y **falsa para dos eventos distintos que llegaron desordenados**, que es justo lo que el backoff del relay y el ciclo `retry`/TTL producen por diseño | Orden / Pérdida | [`topology.md` §4, "Qué tiene que hacer el consumidor", punto 2](topology.md#qué-tiene-que-hacer-el-consumidor-el-contrato-del-otro-lado); §3, camino de reintento pasos 1-3; §6, `JdbcEventOutbox` ("backoff exponencial con jitter") | `reservation.created` (seq 10) entra al retry 30 s; `reservation.confirmed` (seq 11) se procesa; vuelve el 10 y el consumidor lo **descarta con `ack`**. El usuario nunca recibe el alta y **nadie se entera**, porque para el broker el mensaje se procesó bien. El mismo defecto invalida el replay de la DLQ: reencolar un mensaje viejo es un no-op silencioso para cualquier reserva que tuvo un evento posterior | **Alta** |
| **H9** | **Todo fallo se trata como transitorio.** El despachador captura `RuntimeException` a secas. `NotificationDeliveryException` existe para significar "reintentable" y **no se usa para distinguir nada**. El diseño tampoco lo resuelve: §6 sólo promete backoff, no clasificación | Reintentos | [`OutboxDispatcherService.java:59`](../../src/main/java/com/edteam/reservations/application/service/OutboxDispatcherService.java#L59); [`NotificationDeliveryException.java`](../../src/main/java/com/edteam/reservations/application/exception/NotificationDeliveryException.java) (cero usos en `src/main` fuera del javadoc); contraste con `application.yml:150-153`, donde el catálogo **sí** clasifica | Corre en los dos sentidos y los dos duelen: un *poison message* (payload que no serializa) quema 5 intentos y 25 s de despachador por nada, y un broker caído 10 minutos manda todo a la dead letter del productor aunque el fallo fuera 100 % recuperable. La política más nueva y más crítica del sistema es peor que la que ya existía para el catálogo | **Media-Alta** |
| **H10** | **`pollPending` no reclama el mensaje.** Devuelve los `PENDING` sin cambiarles el estado ni tomar un lease. Lo único que hoy evita el doble envío es que haya **un solo llamador** y que `@Scheduled` sea `fixedDelay` | Orden / Concurrencia | [`InMemoryEventOutbox.java:65-76`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/out/outbox/InMemoryEventOutbox.java#L65) contra el contrato de [`EventOutboxPort.java:28-34`](../../src/main/java/com/edteam/reservations/application/port/out/EventOutboxPort.java#L28); [`OutboxDispatchScheduler.java:45`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/in/scheduling/OutboxDispatchScheduler.java#L45) | Hoy es latente. Se vuelve activo **en el momento exacto en que se agregue el `OutboxEndpoint` de replay que el diseño propone** (§6): el endpoint y el scheduler tomarían el mismo mensaje y lo enviarían dos veces. Cambiar `fixedDelay` por `fixedRate` lo rompe sin que falle ningún test | **Media** |
| **H11** | **El mapa nunca se purga.** Los `DISPATCHED` y `FAILED` quedan en el heap para siempre, y `pollPending` recorre y **ordena el mapa completo cada 5 s**. La limitación documentada del stub habla de pérdida por caída, no de crecimiento ilimitado | Pérdida | [`InMemoryEventOutbox.java:41`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/out/outbox/InMemoryEventOutbox.java#L41), [`:80-82`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/out/outbox/InMemoryEventOutbox.java#L80), [`:70-74`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/out/outbox/InMemoryEventOutbox.java#L70) | Un evento por operación de reserva, retenido indefinidamente: el proceso llega a `OutOfMemoryError` y en ese reinicio se pierden **también** los pendientes reales. El costo del poll crece `O(n log n)` sobre el histórico completo, no sobre la cola | **Media** |
| **H12** | **(Diseño) El camino de retry del consumidor republica antes del `ack`.** El paso 2 es "republica a `notifications.retry` y hace `ack` del original". Una caída entre las dos operaciones deja el mensaje en retry **y** sin `ack`, así que la cola lo redelivera | Idempotencia | [`topology.md` §3, "El camino de reintento, en orden", paso 2](topology.md#topología-de-referencia-del-consumidor) | El mensaje se **multiplica** en cada vuelta de retry en la que el consumidor muera en esa ventana. Con `x-delivery-limit: 5` el crash-loop se corta, pero mientras tanto el usuario recibe N copias del mismo aviso. Sólo lo contiene el dedupe por `messageId`, y sólo si ese dedupe corre **antes** de la decisión de reintentar | **Media** |

---

## 2. Cómo detectar cada uno

Todas las pruebas corren en el entorno del repositorio: `mvn test` para las
unitarias y `docker compose up -d && mvn verify` para las de integración
(Testcontainers + PostgreSQL ya están en el `pom.xml`; `MutableClock` ya existe
en `src/test/.../support`).

| Hallazgo | Prueba concreta | Si está bien | Si está mal (hoy) |
|---|---|---|---|
| **H1** | Unitaria sobre `InMemoryEventOutbox` con `MutableClock`: encolar 1 evento, `pollPending` → `markFailed`, y volver a llamar `pollPending` **sin avanzar el reloj** | El segundo `pollPending` devuelve **lista vacía**: el mensaje está en backoff | Devuelve el mismo mensaje. Repitiendo 5 veces seguidas, en tiempo simulado cero, queda en `FAILED` |
| **H1** (extremo a extremo) | IT: `NotificationPort` de test que siempre lanza; correr `dispatchPending(50)` en bucle durante 20 s de reloj simulado sobre 50 mensajes | Los 50 siguen `PENDING`, con `attempts` bajo (3-4) y el próximo intento agendado a minutos | Los 50 quedan `FAILED` en ~20 s |
| **H2** | IT contra el puerto de gestión: agotar los intentos de un mensaje y pedir `GET :9090/actuator/outbox`; después `POST :9090/actuator/outbox/{id}/replay` y verificar que vuelve a `PENDING` | El endpoint lista el mensaje muerto y el replay lo reencola | `404`: el endpoint no existe. El único rastro es una línea de log que se pierde al reiniciar |
| **H3** | **Reprocesar el mismo mensaje dos veces y verificar que el estado final sea el mismo.** IT: crear una reserva, despachar, forzar el mensaje de vuelta a `PENDING`, despachar otra vez, con un `NotificationPort` de test que acumula lo recibido | El adaptador ve **una clave de idempotencia estable** en las dos entregas y produce **un solo efecto** | El adaptador recibe un `DomainEvent` sin ningún id: las dos entregas son indistinguibles y producen **dos notificaciones**. No hay dónde asertar la clave, que es la prueba |
| **H4** | **Matar la transacción entre el encolado y el commit.** IT con `AuditTrailPort` de test que lanza en `record(...)`: invocar `confirm(...)`, esperar la excepción, y asertar `findByStatus(PENDING).isEmpty()` y que la reserva sigue `PENDING` en la base | El outbox está vacío: el evento se fue con el rollback | Hay 1 mensaje `PENDING` que el scheduler va a despachar: **notificación de una confirmación que no ocurrió** |
| **H4** (variante concurrente) | IT: dos `confirm(...)` concurrentes sobre la misma reserva con el mismo `expectedVersion`; una falla con `ConcurrentUpdateException` | 1 evento `ReservationConfirmed` en el outbox | 2 eventos: el perdedor del conflicto optimista dejó el suyo |
| **H5** | IT: crear 3 reservas con el despacho apagado y pedir `GET :9090/actuator/metrics/reservations.outbox.pending`, `.lag` y `.dead` | Las tres métricas existen; `pending` = 3 y `lag` crece con el reloj | `404` en las tres. No hay ninguna forma de contar pendientes desde afuera del proceso |
| **H6** | Unitaria: encolar `created` y `cancelled` de la **misma** reserva; `NotificationPort` que falla la primera vez sólo con `created`; correr `dispatchPending` dos veces registrando el orden de llegada | El segundo mensaje **no se envía** hasta que el primero de esa reserva salga (bloqueo por `subject`), o al menos el consumidor recibe el `sequence` para descartar | Llega `cancelled` y después `created`. Y `OutboxMessage` no tiene `sequence`, así que **la aserción del orden no se puede escribir del lado del consumidor** |
| **H7** | **Simular el destino lento y medir la latencia de la API.** IT con el `base-url` del catálogo apuntando a un stub que duerme 3 s: lanzar 25 `POST /v1/reservations` concurrentes y, en paralelo, medir un `GET /v1/reservations/{id}` que no toca el catálogo | El `GET` responde en decenas de ms: la lentitud del catálogo no sale de la ruta de escritura | El `GET` falla con timeout del pool (`connection-timeout: 3000`): las 20 conexiones están retenidas por transacciones esperando HTTP |
| **H7** (directa) | Test de arquitectura ArchUnit, hermano de los que ya hay en `HexagonalArchitectureTest`: ningún método `@Transactional` puede alcanzar `AirportCatalogPort` | Regla verde | Falla en `CreateReservationService.create` y `ModifyReservationService.modify` |
| **H8** | Unitaria del relay (el lado que este repo sí controla): encolar `created`(seq 10) y `confirmed`(seq 11) de la misma reserva, fallar `created`, despachar dos veces, y asertar la secuencia de `sequence` publicados | Monotónica por `subject`: 10 y después 11 | 11 y después 10 → con la regla 2 del contrato el consumidor **descarta el 10 y hace `ack`**, y el alta no se notifica nunca |
| **H8** (contrato) | Test de contrato sobre el JSON Schema que el diseño propone en `docs/messaging/schemas/`: un caso que fije que dos `messageId` distintos con `sequence` desordenado **no** son un duplicado | El caso existe y la regla del §4 lo refleja | No existe: la regla está redactada sólo para el caso del duplicado |
| **H9** | Unitaria: dos `NotificationPort` de test, uno que lanza `NotificationDeliveryException` (transitorio) y otro que lanza `IllegalArgumentException` (payload inválido, permanente) | El transitorio se reintenta con backoff hasta el techo de tiempo; el permanente va a `FAILED` **en el primer intento** | Los dos recorren exactamente el mismo camino: 5 intentos y `FAILED`. El test no distingue porque el código tampoco |
| **H10** | Unitaria: `pollPending(10)` dos veces seguidas sin marcar nada en el medio. Y una versión con dos hilos sobre `dispatchPending` contando entregas | El segundo `pollPending` devuelve vacío (el primero los reclamó); con dos hilos, cada mensaje se entrega una sola vez | El segundo devuelve los **mismos** mensajes; con dos hilos cada mensaje se entrega dos veces |
| **H11** | Unitaria: encolar y despachar 50 000 eventos, avanzar el reloj más allá de la retención y asertar `findByStatus(DISPATCHED).isEmpty()`. Medir además el tiempo de `pollPending(50)` antes y después | Los despachados se purgan; `pollPending` tarda lo mismo con 0 y con 50 000 despachados | 50 000 mensajes retenidos en el heap y un `pollPending` que ordena los 50 000 en cada corrida, cada 5 s |
| **H12** | Del lado del consumidor, fuera de este repo: test de integración que mata el proceso entre el `basicPublish` a `notifications.retry` y el `basicAck` del original, y cuenta cuántas copias quedan | Una sola copia en retry: la operación es atómica (`tx` del canal, o `nack`+DLX en vez de republish+ack) | Dos copias. En este repo la detección posible es un test que fije la regla: el dedupe por `messageId` corre **antes** de la decisión de reintentar |

---

## 3. Mitigación propuesta

Una o dos líneas por hallazgo; el código es el paso siguiente.

| # | Mitigación |
|---|---|
| **H1** | Agregar `nextAttemptAt` al mensaje y filtrarlo en `pollPending`; `markFailed` lo agenda con backoff exponencial y jitter, con el mismo vocabulario (`initial-backoff` / `max-backoff`) que ya usa el catálogo. Y cambiar el corte de "5 intentos" por un **techo de tiempo** (p. ej. 6 h), que es lo que describe un fallo transitorio largo |
| **H2** | Un `OutboxEndpoint` en el puerto de gestión (9090) que liste y reencole los `FAILED`, más la métrica `reservations.outbox.dead` con alerta en `> 0`. Que la dead letter tenga dueño, tablero y camino de vuelta, no sólo un enum |
| **H3** | El despachador pasa el `OutboxMessage` entero al puerto de salida (el `publish(OutboxMessage)` que el diseño ya define), y el `messageId` viaja en el envelope y en las propiedades AMQP. La clave de idempotencia es la PK de la fila, estable entre reintentos |
| **H4** | Con el `JdbcEventOutbox` el `INSERT` entra en la transacción del caso de uso y el problema desaparece solo. Mientras el stub siga vivo, registrarlo con `TransactionSynchronizationManager` para que el `put` se aplique en el `afterCommit` |
| **H5** | Instrumentar el outbox con Micrometer igual que `MeteredCacheStore`: `pending` y `dead` como gauges, `dispatched`/`failed` como counters y `lag` como `now - min(enqueuedAt)` de los pendientes. `enqueuedAt` ya se guarda; falta leerlo |
| **H6** | Llevar `sequence` al `OutboxMessage` y al envelope, y hacer que el despachador **bloquee por `subject`**: si un mensaje de una reserva falla, los siguientes de esa misma reserva no se envían en ese lote. El resto del lote sigue |
| **H7** | Sacar la validación contra el catálogo **fuera** de la transacción: validar primero, abrir la transacción después. Fijar la regla con un test de arquitectura, que es como este repo ya sostiene las otras disciplinas |
| **H8** | Reescribir la regla 2 del contrato: el descarte por `sequence` aplica **sólo** al mismo `messageId` ya aplicado. Para eventos distintos que llegan desordenados, el consumidor **buffera o aplica igual** —una notificación tardía es peor que ninguna, pero mucho mejor que una silenciosamente descartada— y lo registra como anomalía |
| **H9** | Clasificar el fallo en el adaptador, no en el despachador: una excepción transitoria (`EventPublishException`) reintenta con backoff; cualquier otra va a `FAILED` en el primer intento. El adaptador es el único que sabe si el `503` es del broker o si el payload no serializa |
| **H10** | `pollPending` reclama: `FOR UPDATE SKIP LOCKED` con el outbox JDBC, y mientras tanto una transición a `IN_FLIGHT` con lease y expiración en el stub. Requisito **previo** al endpoint de replay de H2 |
| **H11** | Purga: borrar los `DISPATCHED` de más de 7 días (el `OutboxPurgeScheduler` del diseño) y, en el stub, acotar el mapa. Índice parcial `WHERE status = 'PENDING'` para que el poll escale con la cola y no con el histórico |
| **H12** | Invertir el orden en el consumidor: `nack` sin requeue hacia un DLX de espera, en lugar de republicar y después hacer `ack`. Una sola operación, atómica para el broker. Y correr el dedupe por `messageId` antes de decidir el reintento |

---

## 4. Limitaciones asumidas (no son hallazgos)

Están declaradas en el código o en el ADR, y son decisiones tomadas con los ojos
abiertos. No entran en la tabla de arriba.

| Limitación | Dónde está declarada | Por qué no es un hallazgo |
|---|---|---|
| **El outbox vive en memoria**: los pendientes se pierden si el proceso se cae y cada instancia tiene el suyo | [`InMemoryEventOutbox.java:24-31`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/out/outbox/InMemoryEventOutbox.java#L24); [ADR 0004, Contexto](../adr/0004-mensajeria-asincronica-y-broker.md) | Es un stub explícito con su reemplazo ya diseñado (`JdbcEventOutbox`). Lo que **sí** es hallazgo es H4, que es el problema inverso —emitir de más— y no está documentado en ningún lado |
| **`LoggingNotificationAdapter` no envía nada**: escribe una línea y devuelve | [`LoggingNotificationAdapter.java:17-21`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/out/notification/LoggingNotificationAdapter.java#L17) | Stub deliberado, con el reemplazo definido en §6 del diseño. Efecto colateral que conviene tener presente: como **nunca falla**, todo el camino de reintento y de `FAILED` está muerto en ejecución y sólo vive en los tests |
| **No hay broker todavía** | [`topology.md`, introducción](topology.md); ADR 0004 | Es el punto de partida declarado del diseño, no una omisión |
| **El fallback sin broker marca `DISPATCHED` sin publicar** | [ADR 0004, "En contra, y asumido"](../adr/0004-mensajeria-asincronica-y-broker.md) | Decisión consciente, con su riesgo nombrado ("que alguien lo deje encendido donde no corresponde") y su mitigación (aviso en cada arranque). Es el mismo trato que el stub del catálogo y el caché en memoria |
| **La entrega es at-least-once: duplicados y desorden son el modo normal** | ADR 0004; [`OutboxDispatcherService.java:25-26`](../../src/main/java/com/edteam/reservations/application/service/OutboxDispatcherService.java#L25) | Es la decisión, no la falla. Las fallas son H3 y H6: se traslada el costo al consumidor **sin darle las dos herramientas** —`messageId` y `sequence`— con las que se paga |
| **La auditoría es síncrona y va en la misma transacción** | [`topology.md` §2, fila 3](topology.md#2-tabla-de-comunicaciones) | Decisión explícita y bien argumentada: un registro de evidencia que se puede perder en una cola es un registro que miente |
| **`management.health.rabbit.enabled: false`** | [`topology.md` §6, Configuración](topology.md#configuración) | Consistente con el mismo criterio ya aplicado a Redis: el broker caído degrada, no impide responder |
| **No se agrega ningún webhook de entrada** | [`topology.md` §5](topology.md#5-webhook-de-entrada-la-decisión-es-que-no) | Decisión razonada, con el diseño del día que haga falta ya escrito |
| **Hay un solo consumidor conocido, así que el fan-out es teórico hoy** | [`topology.md` §2, fila 6](topology.md#2-tabla-de-comunicaciones) | El costo del topic exchange sobre una cola directa es bajo y la razón está dada: no tocar el productor cuando aparezca el segundo interesado |
| **Declarar las colas del consumidor acopla al productor** | [`topology.md` §3, "Sobre el acoplamiento"](topology.md#topología-de-referencia-del-consumidor) | Está acotado por configuración al perfil local y justificado (que `docker compose up` deje el circuito andando) |

---

## 5. Orden de remediación

**Criterio:** primero lo que hace que una notificación **no llegue nunca y
nadie se entere**; después lo que la hace llegar **mal** (duplicada, fantasma,
desordenada); al final lo **latente**. Dentro de cada grupo mandan el impacto en
el usuario y el tiempo de coordinación con terceros, no el esfuerzo.

Dos desvíos deliberados de ese orden, ambos por dependencia y no por comodidad:

- **H5 (métricas) sube al bloque 1** aunque su impacto directo sea menor: es el
  instrumento con el que se verifica que los demás quedaron arreglados. Sin él,
  cada corrección se declara terminada a ciegas.
- **H10 (reclamo del mensaje) sube por delante de H2**, porque el endpoint de
  replay de H2 es exactamente el segundo llamador concurrente que H10 convierte
  en doble envío. Arreglarlos al revés introduce el bug al arreglar el otro.

| Orden | Hallazgo | Por qué acá |
|---|---|---|
| 1 | **H1** — backoff y techo de tiempo | Es el que hoy **destruye** notificaciones. Mientras esté, cualquier otra corrección se pierde igual a los 20 s de una caída del destino |
| 2 | **H5** — métricas del outbox | Habilitante. Es lo que convierte "creemos que anda" en "el lag es de 4 s y hay 0 en `dead`" |
| 3 | **H10** — reclamo / `SKIP LOCKED` | Prerrequisito de H2. Barato ahora, caro después de agregar el replay |
| 4 | **H2** — DLQ real del productor con replay | Cierra el camino de vuelta: lo que falló se ve y se puede reenviar |
| 5 | **H8** — regla de descarte del consumidor | Sube por **tiempo de coordinación**: es contrato con un equipo externo. Si se acuerda tarde, el consumidor ya lo implementó mal y hay que renegociarlo en producción |
| 6 | **H4** — encolado transaccional de verdad | Notificar hechos que no ocurrieron erosiona la confianza más rápido que no notificar. Llega casi gratis con el `JdbcEventOutbox` |
| 7 | **H3** — `messageId` hasta el consumidor | Sin esto el consumidor no puede deduplicar nada, y todo lo anterior produce duplicados |
| 8 | **H6** — `sequence` y bloqueo por reserva | Una notificación fuera de orden es entendible; "se canceló tu reserva" antes del alta, no |
| 9 | **H7** — sacar el catálogo de la transacción | Impacto alto y transversal, pero **es el único que no toca la mensajería**: se puede hacer en paralelo con cualquiera de los anteriores, sin conflicto |
| 10 | **H9** — clasificar transitorio vs. permanente | Optimiza el comportamiento del reintento, que con H1 y H2 ya dejó de perder mensajes |
| 11 | **H12** — atomicidad del retry del consumidor | Repositorio ajeno, y el dedupe de H3 ya lo contiene |
| 12 | **H11** — purga y acotado del outbox | El único cuyo daño es gradual y predecible: se ve venir en la métrica de H5 mucho antes de doler |

---

## 6. Estado de la remediación

Implementado en el paso [15](../prompts/15-implementacion-de-mensajeria.md), en
el orden del §5. Las decisiones que hubo que tomar —y las que cambiaron el
contrato— están en el
[ADR 0005](../adr/0005-garantias-de-entrega-y-remediacion-de-la-mensajeria.md).

| # | Estado | Qué se hizo | Test que lo verifica |
|---|---|---|---|
| **H1** | Remediado | `next_attempt_at` con backoff exponencial y jitter completo en `markFailed`, filtrado en `pollPending`. **Dos** cortes: `max-attempts: 10` y `retry-ceiling: 6h`, manda el que llegue primero | `JdbcEventOutboxIT.Backoff`: un mensaje que acaba de fallar no vuelve en la corrida siguiente; la espera crece y tiene techo; cincuenta mensajes contra un destino caído siguen pendientes |
| **H2** | Remediado | Dos dead letters con dos endpoints en el puerto de gestión: `outbox` (la del productor, en la tabla) y `messaging-dlq` (la del consumidor, en el broker). Las dos con listado, replay y métrica que alerta en `> 0` | `OutboxOpsIT.theManagementEndpointListsAndReplaysTheDeadLetter`; `MessagingFlowIT.anUnprocessableMessageEndsUpInTheDeadLetterQueue` y `theDeadLetterQueueCanBeReplayed` |
| **H3** | Remediado | `NotificationPort.notify(DomainEvent)` → `EventPublisherPort.publish(OutboxMessage)`. El `messageId` es la PK de la fila del outbox y viaja en el envelope y en las propiedades AMQP | `OutboxDispatcherServiceTest.passesTheIdempotencyKeyAndOrderToThePublisher`; `ProcessReservationEventServiceTest` (cinco entregas, un efecto); `MessagingFlowIT.processingTheSameMessageTwiceLeavesOneEffect` |
| **H4** | Remediado | `JdbcEventOutbox.enqueue` es un `INSERT` con `JdbcTemplate`: participa de la transacción del caso de uso sin hacer nada especial | `JdbcEventOutboxIT.TransactionalEnqueue.aRollbackTakesTheEventWithIt` |
| **H5** | Remediado | `MeteredEventOutbox` (contadores) y `OutboxMetrics` (gauges: `pending`, `lag`, `dead`, `dispatched.retained`, `dlq.depth`), expuestos por Actuator | `OutboxOpsIT.publishesOutboxMetrics` y `lagGrowsWithTheOldestPendingMessage`; `JdbcEventOutboxIT.Metrics` |
| **H6** | Remediado | `sequence` en `OutboxMessage` y en el envelope; el relay bloquea por `subject` y libera los postergados sin gastarles un intento. El relay reordena por `sequence` porque el `RETURNING` de un `UPDATE` no respeta el `ORDER BY` del subselect | `OutboxDispatcherServiceTest.blocksBySubjectWhenAMessageFails`; `JdbcEventOutboxIT.Ordering` |
| **H7** | Remediado | La validación del catálogo sale de la transacción: los casos de uso de alta y modificación dejan de ser `@Transactional` y delegan la escritura en un colaborador `*Transaction`. Regla estructural en ArchUnit | `CatalogOutsideTransactionIT`; `HexagonalArchitectureTest.noTransactionalClassReachesTheAirportCatalog` |
| **H8** | Remediado | Regla 2 del contrato reescrita: el descarte por `sequence` aplica **sólo** al mismo `messageId`; un evento distinto desordenado se aplica igual y se registra como anomalía (`reservations.messaging.out-of-order`) | `ProcessReservationEventServiceTest.appliesOutOfOrderEventsInsteadOfDiscardingThem` y `tellsApartOutOfOrderFromDuplicate`; `MessagingFlowIT.outOfOrderEventsAreAppliedNotDiscarded` |
| **H9** | Remediado | La clasificación la hace el adaptador: `EventPublishException` es transitoria, cualquier otra excepción es permanente y va a la dead letter en el primer intento. `NotificationDeliveryException` se reemplazó por `EventPublishException`, que ahora sí significa algo | `OutboxDispatcherServiceTest.classifiesFailures`; `JdbcEventOutboxIT.DeadLetter.aPermanentFailureDiesImmediately`; `ConsumerResilienceIT.aPermanentFailureGoesStraightToTheDeadLetterQueue` |
| **H10** | Remediado | `pollPending` reclama: una sentencia con `FOR UPDATE SKIP LOCKED` que elige y marca `IN_FLIGHT`, en su propia transacción, con lease de 2 min | `JdbcEventOutboxIT.Claiming`: un segundo poll devuelve vacío; cuatro despachadores concurrentes entregan cada mensaje una sola vez; un lease vencido vuelve a ser elegible |
| **H11** | Remediado | `MessagingPurgeScheduler` diario, índice parcial `WHERE status IN ('PENDING','IN_FLIGHT')` y dos ventanas de retención: los ids deduplicados se conservan **más** que los mensajes despachados | `JdbcEventOutboxIT.Purge`; `OutboxOpsIT.theEndpointPurgesOldDispatchedMessages` |
| **H12** | Remediado | El contenedor del consumidor corre con **canal transaccionado**: la publicación a la cola de espera y el `ack` del original se comprometen juntas en el broker. Y la deduplicación corre **antes** de la decisión de reintentar | `ProcessReservationEventServiceTest.deduplicationRunsBeforeValidation`; `ConsumerResilienceIT.aMessageThatExhaustsItsRetriesEndsUpInTheDeadLetterQueue` |

### Lo que la remediación cambió del diseño

Tres desviaciones de [`topology.md`](topology.md), las tres documentadas en el
ADR 0005 y anotadas en el propio documento de topología:

1. **La ventana de frescura de 24 h no es `x-message-ttl`**, es una regla del
   caso de uso: una TTL de cola reinicia su reloj en cada reinyección del ciclo
   de retry, así que no acota nada (ADR 0005 §8).
2. **El lado de publicación y el de consumo usan conexiones separadas.** Los
   *publisher confirms* y las transacciones de canal son incompatibles en el
   mismo canal y las dos garantías se necesitan (ADR 0005 §9).
3. **El repositorio trae un consumidor de referencia**, apagable por
   configuración. En producción el consumidor es el servicio de notificaciones;
   el propio existe para que las cinco propiedades que este diseño promete
   tengan prueba acá (ADR 0005 §12).

### Lo que sigue abierto

| Qué | Por qué no entró | Qué haría falta |
|---|---|---|
| **JSON Schema del mensaje** en `docs/messaging/schemas/` | El contrato ejecutable existe como test (`DomainEventPayloadMapperTest`, con *allowlist* de campos), que es lo que rompe el build ante un cambio. El Schema publicado sirve para otra cosa: que el consumidor externo valide del otro lado sin leer nuestro código | Generar los cuatro archivos y un test que valide los mensajes emitidos contra ellos, con una librería de JSON Schema que hoy no está en el classpath |
| **H12 del lado del consumidor real** | Es un repositorio ajeno. Acá está resuelto en el consumidor de referencia y fijada la regla que hay que trasladar | Coordinar con el equipo de notificaciones: canal transaccionado (o `nack` hacia un DLX de espera) y dedupe antes de decidir el reintento |
| **Orden entre reservas distintas** | No hace falta y el costo es alto: un candado global serializaría todo el despacho | Si alguna vez hiciera falta, partición por `subject` |
| **Presupuesto de tiempo del itinerario completo** | Es el hallazgo hermano de H7 y ya estaba declarado fuera de alcance antes de esta auditoría: sacar la llamada de la transacción acota el daño al pool, no el tiempo total del pedido | Resolver las ciudades en paralelo —son independientes— y un circuit breaker que deje de intentar mientras el proveedor esté caído |
