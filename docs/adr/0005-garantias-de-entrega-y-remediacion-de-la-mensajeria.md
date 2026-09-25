# 0005 — Garantías de entrega de la mensajería: idempotencia, dos dead letters y reintentos clasificados

- **Estado:** Aceptado
- **Fecha:** 2026-09-23
- **Prompt origen:** [15 — Implementación de mensajería](../prompts/15-implementacion-de-mensajeria.md)
- **Complementa a:** [0004 — Mensajería asincrónica: eventos sobre RabbitMQ con outbox en PostgreSQL](0004-mensajeria-asincronica-y-broker.md)
- **Diseño:** [`docs/messaging/topology.md`](../messaging/topology.md)
- **Auditoría que origina esta decisión:** [`docs/messaging/audit.md`](../messaging/audit.md)
- **Se ve en:** [Diagramas C4 de contexto y contenedores](../architecture/c4.md)

## Contexto

El [ADR 0004](0004-mensajeria-asincronica-y-broker.md) decidió *qué* usar
—eventos a un topic exchange de RabbitMQ con el outbox en PostgreSQL— y la
[auditoría](../messaging/audit.md) encontró doce defectos en lo que había y en
lo que el diseño dejaba abierto. Cuatro sobrevivían al diseño: el acoplamiento
síncrono dentro de la transacción (H7), la regla de descarte por `sequence` que
borra eventos legítimos (H8), la falta de clasificación de fallos (H9) y la
ventana de multiplicación del retry del consumidor (H12).

Este ADR registra las decisiones que hubo que tomar **al implementar**, porque
ninguna se deduce del ADR 0004 y todas cambian el contrato o el comportamiento
observable.

El orden de remediación es el de la auditoría: primero lo que hace que una
notificación no llegue nunca y nadie se entere, después lo que la hace llegar
mal, al final lo latente.

## Decisión

### 1. La clave de idempotencia es la PK de la fila del outbox, y cruza el puerto

El puerto de salida pasa de `NotificationPort.notify(DomainEvent)` a
`EventPublisherPort.publish(OutboxMessage)`. No es un renombre: el evento de
dominio no tiene id propio, así que con la firma anterior **el consumidor no
recibía nada con lo que deduplicar** aunque el contrato se lo exigiera.

El `messageId` es un `UUID` generado por la aplicación y es la PK de
`outbox_message`. Es estable entre reintentos y entre replays —reencolar no lo
cambia—, que es exactamente la propiedad que lo hace usable como clave.

Se descartó un `BIGSERIAL` como id: contaría a cualquiera que reciba un mensaje
cuántas operaciones hace el sistema. El `sequence` sí es `BIGSERIAL`, porque su
trabajo es justamente ser monotónico, y va en un campo aparte.

### 2. El payload se serializa al encolar y viaja como JSON opaco

`OutboxMessage.payload` es un `String`. La serialización ocurre dentro de la
transacción del caso de uso, en `DomainEventPayloadMapper`, que es el único
punto del sistema que conoce los cuatro tipos de hecho —con un `switch`
exhaustivo sobre la interfaz sellada, así que un quinto evento rompe el build—.

Tres consecuencias, las tres buscadas: lo que se publica es exactamente lo que
pasó aunque el código cambie entre el encolado y el despacho; el relay reenvía
bytes sin conocer ningún tipo de evento; y no hay que deserializar una jerarquía
sellada de vuelta desde la base.

### 3. `pollPending` reclama, y el reclamo tiene lease

Una sola sentencia elige (`SELECT ... FOR UPDATE SKIP LOCKED`) y marca
(`UPDATE ... SET status = 'IN_FLIGHT'`), en su propia transacción
(`REQUIRES_NEW`). Con `SKIP LOCKED`, N instancias se llevan subconjuntos
disjuntos sin lock distribuido y sin esperarse.

El reclamo vence (`claim-lease: 2m`): un proceso que muere entre el reclamo y la
publicación no deja el mensaje trabado para siempre.

**Este punto es un prerrequisito del endpoint de replay**, no una optimización:
sin el reclamo, el endpoint y el relay tomarían el mismo mensaje y lo
publicarían dos veces.

Se agregó `release(ids)` al puerto para devolver mensajes a pendiente **sin
gastarles un intento**; lo usa el bloqueo por reserva del punto 6.

### 4. Dos dead letters, con dos herramientas y dos métricas

| | Dónde vive | Qué significa | Cómo se mira | Cómo se reprocesa |
|---|---|---|---|---|
| **Productor** | `outbox_message` con `status = 'FAILED'` | No pudimos **publicar** | `GET /actuator/outbox` | `POST /actuator/outbox` |
| **Consumidor** | `notifications.reservation-events.dlq` en el broker | No pudieron **procesar** | `GET /actuator/messaging-dlq` | `POST /actuator/messaging-dlq` |

Están separadas a propósito: se resuelven en lugares distintos y con gente
distinta. Y la del productor vive en la base y no en el broker porque si lo que
está caído es el broker, una dead letter *dentro* del broker es inalcanzable
justo cuando hace falta.

El replay de las dos conserva el `messageId`, así que reprocesar lo que ya se
aplicó no produce un segundo efecto. Los dos resetean el contador de intentos:
el replay ocurre después de arreglar la causa, y arrancar con el contador
agotado devolvería el mensaje a la dead letter en el primer tropiezo.

Los endpoints van en el puerto de gestión (9090), que no se publica hacia
afuera. Es la misma mitigación estructural del resto del actuator: no hace falta
acertar con la autorización de un endpoint que reencola mensajes si el puerto no
es alcanzable.

### 5. Reintentos con backoff, jitter completo y **dos** cortes

El corte por intentos (`max-attempts: 10`) describe mal un fallo transitorio
largo: con backoff exponencial se agota en minutos, y una caída del broker de
media hora mandaría a la dead letter mensajes cuyo fallo era recuperable. Un
corte sólo por tiempo, al revés, dejaría un mensaje venenoso reintentándose
horas. **Se usan los dos y manda el que llegue primero**, con
`retry-ceiling: 6h`.

El jitter se sortea sobre el intervalo **entero** y no como un porcentaje: es lo
que descorrelaciona de verdad a N instancias que fallaron al mismo tiempo contra
el mismo destino, en lugar de dejarlas reintentando juntas con una desviación
despreciable.

**La clasificación la hace el adaptador, no el despachador.** Una
`EventPublishException` es transitoria y se reintenta; cualquier otra excepción
es permanente y va a la dead letter en el primer intento. El único que sabe si
el `503` fue del broker o si el payload no serializa es quien habla con el
broker; el despachador sólo traduce el tipo de la excepción.

### 6. El orden se cuida por reserva, no por lote

Si un mensaje de una reserva falla, los que le siguen **de esa misma reserva**
no se publican en ese lote y vuelven a pendiente sin gastar un intento. El resto
del lote sigue.

El orden global no se promete —eso lo resuelve el consumidor con `sequence`—
pero el de una misma reserva se cuida acá, que es donde sale barato. Sin esto,
un alta que falla una vez y una cancelación que sale bien producen «se canceló
tu reserva» antes de «registramos tu reserva».

El `RETURNING` de un `UPDATE` devuelve las filas en el orden en que el motor las
tocó, no en el del `ORDER BY` del subselect, así que el relay reordena por
`sequence` antes de entregar. Es un detalle chico y sin él la garantía no se
cumple.

### 7. El consumidor **no descarta** por `sequence`: aplica y registra la anomalía

Es el cambio de contrato con el equipo externo, y reemplaza la regla 2 del
diseño original.

La regla vieja —«descartar un mensaje cuyo `sequence` sea menor o igual al
último ya aplicado»— es correcta para un duplicado del mismo evento y **falsa
para dos eventos distintos que llegaron desordenados**, que es lo que el backoff
del relay y el ciclo de retry producen por diseño. El caso concreto: `created`
(seq 10) entra al retry, `confirmed` (seq 11) se procesa, vuelve el 10 y el
consumidor lo descarta con `ack`. El usuario nunca recibe el alta y nadie se
entera, porque para el broker el mensaje se procesó bien.

La regla nueva:

- **El descarte aplica sólo al mismo `messageId`.** Eso es un duplicado.
- **Un evento distinto que llega desordenado se aplica igual** y se registra
  como anomalía (`reservations.messaging.out-of-order`). Una notificación tardía
  es peor que una puntual y mucho mejor que una silenciosamente descartada.

La deduplicación es un `INSERT ... ON CONFLICT DO NOTHING` contra una PK, no un
`SELECT` seguido de un `INSERT`: la decisión la toma la base, sin la carrera que
tendrían dos consumidores concurrentes. Y se registra **en la misma
transacción** que el efecto, para que un fallo del efecto no deje el mensaje
marcado como procesado.

### 8. La frescura se corta en el consumidor, no con `x-message-ttl`

El diseño pedía `x-message-ttl: 24h` en la cola principal. Con un ciclo de
retry eso no funciona: la TTL de cola se cuenta desde que el mensaje entra a la
cola, así que un mensaje reinyectado arranca un reloj nuevo y la ventana no
acota nada; y si se la hiciera dead-letterear hacia la cola de espera, el
mensaje entraría en un bucle expira → espera → reinyección → expira.

La ventana pasa a ser una regla explícita del caso de uso: un hecho con más de
24 h no se notifica y va a la dead letter con su motivo. Es visible, tiene test
y no depende de una interacción sutil entre dos primitivas del broker.

La cola principal dead-letterea **sólo** a la DLQ, y el `x-delivery-limit: 5` de
la cola cuórum corta el crash-loop.

### 9. El reintento del consumidor es una sola operación para el broker

El diseño decía «republica a la cola de espera y hace `ack` del original». Dos
operaciones, con una ventana: una caída en el medio deja el mensaje en la cola
de espera *y* sin confirmar, así que la cola lo vuelve a entregar y el mensaje se
multiplica en cada vuelta.

El contenedor corre con **canal transaccionado**: la publicación y el `ack` se
comprometen juntas en el broker. Y la deduplicación corre **antes** de la
decisión de reintentar, que es lo que contendría el problema si la ventana
existiera.

**Consecuencia inesperada, y por eso queda escrita:** los *publisher confirms* y
las transacciones de canal son incompatibles en el mismo canal —el broker
responde `PRECONDITION_FAILED`—, y las dos cosas se necesitan de verdad. El lado
de publicación y el de consumo usan **conexiones separadas** sobre la misma
conexión de red. Sin esto, el consumidor no arranca.

También por eso el `peek` de la DLQ va por un canal **sin** transacción:
`basic.nack` no es transaccional en RabbitMQ, así que dentro de un `tx.commit`
el reencolado se descarta y mirar la dead letter la vaciaría.

### 10. El contenedor del consumidor **no** comparte el transaction manager

Atar la transacción de la base a la del canal parece más prolijo y rompe el
manejo de errores: el caso de uso es `@Transactional`, así que una excepción
suya marcaría como *rollback-only* la transacción del contenedor y el commit
posterior fallaría, justo en el camino en el que el listener ya decidió mandar el
mensaje a la DLQ.

Sin él el orden es el correcto: la base confirma primero y el canal después. No
son atómicos entre sí y no hace falta que lo sean — una caída entre los dos
commits produce una reentrega, y la reentrega la absorbe la deduplicación.

### 11. La validación del catálogo sale de la transacción

Es el único hallazgo que no toca la mensajería, y el de mayor impacto
transversal. La validación de aeropuertos es HTTP con hasta 3 intentos y ~6,5 s
de peor caso *por ciudad*; adentro de `@Transactional`, un itinerario de tres
tramos contra un catálogo degradado retenía una conexión del pool ~26 s, y con
`maximum-pool-size: 20` veinte pedidos así agotaban el pool y **toda la API**
devolvía error, incluidos los `GET` que no tocan el catálogo ni escriben nada.
El `INSERT` del outbox empeoraba el cuadro al sumar una escritura más a esa
transacción larga.

Los casos de uso de alta y modificación dejan de ser transaccionales: validan
primero y delegan la escritura en un colaborador `*Transaction`. La separación
es en **dos beans** y no en dos métodos de la misma clase porque
`@Transactional` se aplica por proxy y una autoinvocación no abre transacción:
el bug sería invisible.

La verificación de versión se repite dentro de la transacción, que es donde
cuenta: entre la lectura de las precondiciones y la escritura hay una llamada
HTTP, y en esa ventana otro pedido pudo modificar la reserva.

`HexagonalArchitectureTest` fija la regla: ninguna clase con un método
transaccional puede alcanzar el catálogo.

### 12. El repositorio trae un consumidor de referencia, apagable

En producción el consumidor es el servicio de notificaciones, que es su dueño.
El de este repositorio existe para que el circuito se pueda levantar y probar de
punta a punta, y va detrás de `reservations.messaging.consumer-enabled`, apagado
fuera de local. No tiene lógica de negocio: parsea el envelope, delega en
`ProcessReservationEventUseCase` y traduce el resultado a una decisión de
transporte.

Es una desviación del §1 del diseño, donde el consumidor es exclusivamente
externo. Se acepta porque sin un consumidor propio las cinco propiedades que
este paso promete —idempotencia, DLQ, acotamiento del reintento, aislamiento del
venenoso, independencia de la latencia de la API— no tendrían prueba en este
repositorio, y una garantía sin test es una intención.

## Consecuencias

**A favor**

- Una notificación deja de perderse por un reinicio, por una caída del
  consumidor o por un blip del broker: la fila está comprometida junto con la
  reserva y el reintento tiene backoff.
- Se deja de emitir notificaciones **fantasma**: el `INSERT` del outbox se va
  con el rollback del caso de uso.
- Procesar el mismo mensaje dos veces deja exactamente el mismo estado, y hay un
  efecto contable que lo prueba.
- Lo que falló se ve (`reservations.outbox.dead`,
  `reservations.messaging.dlq.depth`, las dos con alerta en `> 0`) y tiene camino
  de vuelta.
- El número operativo que faltaba existe: `reservations.outbox.lag` dice cuánto
  tarda una notificación desde que el hecho ocurrió.
- Un catálogo degradado dejó de poder tumbar la API completa.
- Varias instancias despachan en paralelo sin lock distribuido.

**En contra, y asumido**

- **Dos conexiones al broker** por la incompatibilidad entre confirms y
  transacciones de canal. Es una complejidad real del cliente, no del diseño, y
  no se puede evitar sin renunciar a una de las dos garantías.
- **Base de datos y broker no son atómicos entre sí** en el consumidor. El orden
  elegido hace que el modo de falla sea una reentrega, que la deduplicación
  absorbe; no hay pérdida, hay trabajo repetido.
- **Tres tablas más que mantener**, con dos ventanas de retención distintas y un
  scheduler de purga que hay que vigilar. Los ids deduplicados se conservan
  **más** que los mensajes despachados: purgarlos con la misma ventana abriría
  el agujero que la deduplicación tapa.
- **El consumidor puede recibir eventos fuera de orden y los aplica.** Es
  deliberado —ver el punto 7— y el costo es que una notificación puede llegar
  tarde. La anomalía queda medida.
- **`@Transactional` sale de los casos de uso de escritura** y pasa a un
  colaborador. Es una pieza más y una indirección más, y se paga para que la
  llamada de red no tenga una conexión de base tomada.
- **La modificación lee la reserva dos veces.** Una para cortar temprano antes de
  pagar el catálogo, otra dentro de la transacción. Es la lectura barata la que
  se duplica.
- **El consumidor de referencia acopla parcialmente el repositorio con su
  consumidor.** Está acotado por configuración y no toca el código de negocio.
- **Sin broker, el fallback marca `DISPATCHED` sin publicar.** Se conserva la
  decisión del ADR 0004, con su mismo riesgo y su mismo aviso en cada arranque.
