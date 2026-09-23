# Topología de mensajería

Cómo se comunica el sistema de reservas con lo que tiene alrededor: qué viaja
de forma síncrona, qué de forma asincrónica, sobre qué mecanismo y bajo qué
contrato.

El punto de partida es el estado actual. Hoy **toda** la comunicación es HTTP
síncrono salvo una pieza: el outbox transaccional hacia el sistema de
notificaciones, que está a medio construir. El outbox vive en memoria
(`InMemoryEventOutbox`), el envío es un log (`LoggingNotificationAdapter`) y el
estado `FAILED` promete una dead-letter queue que no existe. No hay broker.

Este documento decide las tres cosas que faltan —dónde se guardan los eventos,
por dónde salen y qué forma tienen— sin tocar el dominio.

- §1 [Participantes](#1-participantes)
- §2 [Tabla de comunicaciones](#2-tabla-de-comunicaciones)
- §3 [Colas y tópicos](#3-colas-y-tópicos)
- §4 [Contrato de mensaje](#4-contrato-de-mensaje)
- §5 [Webhook de entrada](#5-webhook-de-entrada-la-decisión-es-que-no)
- §6 [Qué cambia en el código](#6-qué-cambia-en-el-código)

---

## 1. Participantes

| Participante | Rol | Qué necesita saber del otro |
|---|---|---|
| **Frontends** (web, móvil) | Clientes de la API | La URL, el contrato OpenAPI y cómo obtener un JWT. No saben que existe mensajería. |
| **Servicio de reservas** (este) | Dueño de la reserva. **Productor** de los cuatro hechos de negocio | Nada del consumidor: sólo el nombre del exchange al que publica. No conoce colas, ni bindings, ni cuántos suscriptores hay. |
| **Servicio de notificaciones** (externo) | **Consumidor**. Dueño del dato de contacto, de las plantillas y del canal | El contrato del mensaje (envelope + payload), el nombre del exchange al que atarse y el routing key que le interesa. Resuelve el email a partir del `userId`: ese dato es suyo, no viaja en el mensaje. |
| **api-catalog** (externo) | Maestro de ciudades | Nada. Es un proveedor pasivo: no publica eventos ni sabe quién lo consulta. |
| **Broker** (RabbitMQ) | Infraestructura de transporte | Nada de negocio. Rutea por routing key. |
| **Operaciones** | Drena la dead letter, replica los mensajes atascados | Dos lugares: la tabla `outbox_message` en estado `FAILED` (lo que no se pudo publicar) y la cola `notifications.reservation-events.dlq` (lo que no se pudo procesar). |
| **Suscriptor futuro** (analítica, antifraude, data platform) | Consumidor hipotético | Sólo el contrato. Se ata al mismo exchange con su propia cola y **el productor no se entera**. No existe hoy: es la razón por la que el mecanismo es un evento y no una cola directa. |

Dos participantes que **no** están en la lista, a propósito:

- **Un proveedor de pagos.** No hay integración de pagos en el sistema: la
  confirmación es `POST /v1/reservations/{id}/confirmation`, una llamada
  autenticada del dueño de la reserva. Ver §5.
- **Un orquestador / saga.** Los cuatro hechos son notificaciones de algo que
  ya pasó y se comprometió en la base. No hay una transacción distribuida que
  compensar, así que no hace falta coordinación.

---

## 2. Tabla de comunicaciones

| # | Origen | Destino | Qué se comunica | Mecanismo | Por qué ése y no otro |
|---|---|---|---|---|---|
| 1 | Frontends | API de reservas | Alta, lectura, modificación, confirmación, cancelación | **Síncrono HTTP** (JWT, `ETag`/`If-Match`) | La respuesta *es* la operación: el usuario necesita el id, la versión y el error de validación ahora. Una cola acá sólo agregaría un estado "pendiente" que el usuario tendría que sondear. |
| 2 | Caso de uso | PostgreSQL (`reserva` + `outbox_message`) | La reserva y el hecho ocurrido | **Escritura transaccional local** (no es mensajería) | Es el punto de traspaso entre lo síncrono y lo asincrónico. Escribir el evento en la misma transacción es lo único que impide notificar algo que después se rollbackeó, o perder una notificación de algo que sí se guardó. |
| 3 | Caso de uso | Auditoría (`AuditTrailPort`) | Quién hizo qué sobre qué reserva | **Síncrono, en la misma transacción** | La auditoría es evidencia. Un registro que se puede perder en una cola es un registro que miente. No se hace asincrónico. |
| 4 | Reservas | `api-catalog` `GET /city/{code}` | Validación de los aeropuertos del itinerario | **Síncrono** + caché + reintentos sobre el GET | El `201` depende de la respuesta: no se puede diferir. Ya decidido: timeouts por proveedor y *stale-while-error*. |
| 5 | Reservas | Redis | Caché distribuida | **Síncrono, opcional, degradable** | Si no contesta en 200 ms se va al origen. Nunca es la fuente de verdad. |
| 6 | **Relay del outbox** | **Exchange `reservations.events`** | Los cuatro hechos: `reservation.created`, `.confirmed`, `.modified`, `.cancelled` | **Evento** (publish/subscribe sobre *topic exchange*) | El hecho ya ocurrió, es inmutable y no espera respuesta. El productor publica a un exchange y **no nombra a ningún destinatario**: no sabe si hay uno, tres o ninguno. Una cola directa hacia notificaciones haría lo mismo hoy y obligaría a tocar nuestro código el día que aparezca un segundo interesado. |
| 7 | Exchange | Cola `notifications.reservation-events` | Los mismos hechos, ya ruteados | **Cola** (desde la vista del consumidor) | Del lado del consumidor el trabajo tiene que esperarlo aunque esté caído. Eso lo da la cola durable, no el exchange: un exchange sin colas atadas descarta lo que recibe. El mismo mensaje es un *evento* para quien lo publica y una *cola de trabajo* para quien lo procesa; no es contradicción, es dónde se para cada uno. |
| 8 | Cola principal | `notifications.retry` → `notifications.dlq` | Mensajes que el consumidor no pudo procesar | **Cola** (retry con backoff + DLQ) | Un mensaje venenoso no puede bloquear al resto ni reintentarse para siempre. Es la DLQ real que `OutboxStatus.FAILED` prometía. |
| 9 | Relay | `outbox_message` en estado `FAILED` | Mensajes que no se pudieron **publicar** | **Dead letter en la base, no en el broker** | Si lo que está caído es el broker, un dead letter *dentro* del broker es inalcanzable justo cuando hace falta. El dead letter del productor tiene que vivir del lado del productor. Son dos fallas distintas y necesitan dos lugares distintos. |
| 10 | Proveedor de pagos | Reservas | Resultado del cobro | **Webhook — no adoptado** | No hay integración de pagos. Ver §5. |
| 11 | Notificaciones | Reservas | Acuse de entrega del email | **Webhook — descartado** | No hay ninguna regla de negocio que reaccione a "el mail rebotó". Aceptarlo volvería a acoplar al productor con el consumidor —justo lo que este diseño saca— a cambio de un dato que sólo íbamos a loguear. La observabilidad del canal es del dueño del canal. |
| 12 | `api-catalog` | Reservas | Altas y bajas en el maestro de ciudades | **Evento — no disponible** | Sería el mecanismo correcto y no lo controlamos: el proveedor no publica nada. El TTL de 30 min del caché es el sustituto, y el negativo de 5 min es el que acota el daño de una ciudad nueva. |

### Por qué RabbitMQ

| Opción | Free tier / local | Ajuste | Veredicto |
|---|---|---|---|
| **RabbitMQ** | Imagen OSS en `compose.yaml` (~100 MB de RAM). Hosted: plan gratuito de CloudAMQP | *Topic exchange* = pub/sub con routing declarativo: el productor publica a un exchange y los consumidores declaran sus colas. DLQ, TTL y límites de cola son primitivas del broker, no código nuestro. Colas *quorum* replicadas para durabilidad | **Elegido** |
| Kafka / Redpanda | OSS en compose, pero pesado (~1 GB). Los "free tier" gestionados son créditos de prueba, no capa gratuita | Da orden por partición y *replay* del log. Nada de eso lo necesitamos: el volumen es de una operación de reserva, hay un consumidor y el diseño ya asume desorden. Lo que sí necesitamos —DLQ, reintento con backoff— hay que escribirlo a mano | Descartado: paga un costo operativo alto por garantías que no usamos |
| Redis Streams | **Ya está en `compose.yaml`**. Costo incremental cero | Tentador y equivocado: ese Redis está declarado como caché descartable (`--save ""`, `maxmemory 30mb`, `allkeys-lru`). Usarlo como broker significa que una ráfaga de tráfico desaloja notificaciones pendientes por política LRU. Contradice una decisión ya tomada | Descartado |
| Sólo PostgreSQL (`LISTEN/NOTIFY` o *polling*) | Cero infraestructura nueva | Es exactamente lo que ya hace el outbox, y alcanza mientras haya un solo consumidor conocido. Lo que no da es fan-out a un consumidor que todavía no existe sin que el productor escriba código para él | Descartado como destino final, **conservado como buffer**: el outbox en Postgres sigue siendo la primera parada |

La combinación que queda —outbox en Postgres, exchange en RabbitMQ— es la que
cumple la restricción dura: **la aplicación arranca y los tests corren sin el
broker**. Sin RabbitMQ los eventos se acumulan en la tabla y el relay reintenta;
las reservas no se enteran. Es el mismo criterio con el que hoy arranca sin
Redis y sin el catálogo externo.

---

## 3. Colas y tópicos

### Lo que publica reservas

| Nombre | Tipo | Publicador | Consumidores | Dueño | Garantía | Orden | Retención |
|---|---|---|---|---|---|---|---|
| `reservations.events` | Topic exchange, durable | Relay del outbox | Ninguno directo: rutea a las colas atadas | **Reservas** | — (un exchange no almacena) | — | Nada. Un mensaje sin cola atada se descarta; por eso se publica con `mandatory=true` y una *return callback*, para que un binding faltante sea un error visible y no una pérdida silenciosa |
| `outbox_message` | Tabla PostgreSQL | Casos de uso, en su transacción | Relay del outbox | **Reservas** | Commit atómico con la reserva | `sequence` (`BIGSERIAL`) monotónico | `DISPATCHED` se purga a los 7 días; `FAILED` se conserva hasta resolverse |

Routing keys: `reservation.created`, `reservation.confirmed`,
`reservation.modified`, `reservation.cancelled` — idénticas al `eventType()` del
evento de dominio. No hay traducción que mantener.

### Topología de referencia del consumidor

Estas colas **son del servicio de notificaciones**, no nuestras. Se documentan
acá porque son parte del contrato operativo y porque el `compose.yaml` las
declara para que el repositorio funcione de punta a punta en local (ver la nota
de acoplamiento más abajo).

| Nombre | Tipo | Publicador | Consumidor | Garantía | Orden | Retención |
|---|---|---|---|---|---|---|
| `notifications.reservation-events` | Quorum queue, durable. Binding: `reservations.events` con `reservation.*` | El exchange | Servicio de notificaciones | **At-least-once**: se hace `ack` recién después de procesar | FIFO *best effort*. Se rompe con cualquier redelivery, con más de un consumidor y con el *prefetch*. **No es una garantía**: el orden se resuelve con `sequence` (§4) | Hasta el `ack`. **Sin `x-message-ttl`**: la ventana de frescura de 24 h la corta el consumidor (ver la nota de abajo). `x-max-length: 100000` con `x-overflow: reject-publish`, `x-delivery-limit: 5` |
| `notifications.reservation-events.retry` | Queue sin consumidor. `x-message-ttl: 30s`, DLX → `notifications.requeue` | El consumidor, al fallar de forma transitoria | Nadie: la TTL la devuelve sola | At-least-once | — | 30 s por vuelta, hasta 5 vueltas |
| `notifications.reservation-events.dlq` | Queue durable, sin TTL | El consumidor al agotar reintentos; la cola principal por TTL vencida o `x-delivery-limit` | Operaciones (a mano o con una herramienta de replay) | Persistente | — | Indefinida. Alerta con profundidad > 0 |

Exchanges auxiliares del consumidor: `notifications.retry` (fanout, hacia la
cola de espera), `notifications.requeue` (fanout, atado a la cola principal como
segundo binding) y `notifications.dlq` (fanout).

**El camino de reintento, en orden:**

1. El consumidor falla con un error transitorio (su proveedor de email dio 503).
2. Republica el mensaje **tal cual** a `notifications.retry` con el header
   `x-attempt` incrementado, y hace `ack` del original — **las dos cosas en la
   misma transacción de canal**, así que para el broker es una sola operación.

   > **Corrección (ver [ADR 0005 §9](../adr/0005-garantias-de-entrega-y-remediacion-de-la-mensajeria.md)).**
   > Con dos operaciones separadas hay una ventana: una caída en el medio deja el
   > mensaje en la cola de espera *y* sin `ack`, así que la cola lo redelivera y
   > el mensaje se **multiplica** en cada vuelta. El canal transaccionado la
   > cierra, y la deduplicación por `messageId` —que corre **antes** de la
   > decisión de reintentar— la contendría igual.
3. A los 30 s vence la TTL de la cola de espera y el mensaje sale por su DLX a
   `notifications.requeue`, que está atado a la cola principal. Vuelve a entregarse.
4. Con `x-attempt >= 5`, o ante un error no transitorio (payload que no cumple
   el esquema, tipo desconocido), va directo a `notifications.dlq`.
5. Si el consumidor muere antes de hacer `ack`, la cola redelivera; el
   `x-delivery-limit: 5` de la cola *quorum* corta el crash-loop y manda el
   mensaje a la DLQ.

El backoff está en la TTL de la cola de espera y no en un `sleep` del
consumidor: dormir dentro del handler ocupa el canal y frena los mensajes sanos
que venían detrás del venenoso.

**La cola principal dead-letterea sólo a la DLQ, nunca a la de retry.** Si TTL
vencida y rechazo apuntaran al mismo lugar, un mensaje expirado entraría en un
loop infinito: expira → retry → se reinyecta → expira.

**La ventana de frescura vive en el consumidor, no en la TTL de la cola** (ver
[ADR 0005 §8](../adr/0005-garantias-de-entrega-y-remediacion-de-la-mensajeria.md)).
Una `x-message-ttl` de cola se cuenta desde que el mensaje *entra* a la cola, así
que un mensaje reinyectado por el ciclo de retry arranca un reloj nuevo y la
ventana de 24 h no acota nada. Hacerla dead-letterear hacia la cola de espera
sería el bucle de arriba. Como regla del caso de uso —un hecho con más de 24 h no
se notifica y va a la DLQ con su motivo— es visible, tiene test y no depende de
una interacción sutil entre dos primitivas del broker.

**Backpressure en lugar de pérdida.** Con `reject-publish`, una cola llena hace
fallar nuestra publicación; el relay marca el mensaje como fallido, lo reintenta
con backoff y la fila se acumula en Postgres, que es donde se puede ver, medir y
drenar. La alternativa (`drop-head`) descarta notificaciones en silencio.

**Sobre el consumidor de referencia de este repositorio.** El repositorio trae
además un consumidor propio
(`infrastructure/adapter/in/messaging/ReservationEventListener`), detrás de
`reservations.messaging.consumer-enabled` y apagado fuera de local. Es una
desviación del §1, donde el consumidor es exclusivamente externo, y se acepta
porque sin él las cinco propiedades que este diseño promete —idempotencia, DLQ,
acotamiento del reintento, aislamiento del mensaje venenoso e independencia de la
latencia de la API— no tendrían prueba acá, y una garantía sin test es una
intención. No tiene lógica de negocio: parsea el envelope, delega en un caso de
uso y traduce el resultado a una decisión de transporte.

**Sobre el acoplamiento.** Declarar las colas del consumidor es, técnicamente,
saber quién consume. Se acepta con dos límites: (a) la declaración vive detrás
de `reservations.messaging.declare-consumer-topology`, encendida sólo en el
perfil local, para que `docker compose up` deje el circuito andando de punta a
punta; (b) en cualquier otro entorno la aplicación declara **únicamente el
exchange**, y cada consumidor declara y ata su propia cola. El código de negocio
no menciona ninguna cola en ningún caso.

---

## 4. Contrato de mensaje

### Dónde vive el contrato

El mensaje es **JSON autodescriptivo**: el envelope completo va en el cuerpo. Las
propiedades AMQP (`message_id`, `type`, `timestamp`, `correlation_id`,
`content_type`, headers `x-schema-version` y `x-sequence`) son un **espejo** de
los mismos valores, para que las herramientas del broker y el deduplicador del
consumidor no tengan que parsear el cuerpo.

El cuerpo es el contrato; las propiedades son una comodidad del transporte. Es
lo que permite que mudarse a otro broker mañana no cambie una sola línea del
contrato, y lo que cumple la restricción de que los eventos de dominio no
cambian de forma según por dónde salen.

### Envelope

| Campo | Tipo | De dónde sale | Para qué |
|---|---|---|---|
| `messageId` | UUID (string) | PK de la fila del outbox | **Clave de idempotencia.** El consumidor deduplica por este valor. Estable entre reintentos: republicar el mismo mensaje no cambia el id |
| `type` | string | `DomainEvent.eventType()` | Qué pasó. Es también el routing key |
| `version` | entero | Constante por tipo | Versión **mayor** del esquema del `data`. Sólo cambia ante una ruptura (§evolución) |
| `source` | URN | Configuración | Quién lo emitió. Distingue entornos y evita que un mensaje de staging se procese como productivo |
| `subject` | string | `reservationId` | Entidad a la que se refiere. Es el **ámbito del orden** y la clave de partición si algún día el transporte la necesita |
| `sequence` | entero largo | `BIGSERIAL` de la fila del outbox | **Orden.** Monotónico creciente. Por reserva es estricto: el locking optimista serializa las escrituras sobre la misma fila, así que dos hechos de la misma reserva no pueden tomar secuencias invertidas |
| `occurredAt` | RFC 3339 UTC | `DomainEvent.occurredAt()` | Cuándo pasó el hecho. **No** cuándo se envió |
| `publishedAt` | RFC 3339 UTC | Reloj del relay | Cuándo salió. La diferencia con `occurredAt` es el lag del outbox: es la métrica que dice si el despacho está atrasado |
| `correlationId` | string | MDC del pedido que lo originó | Une el evento con las líneas de log del `POST` que lo produjo y con el header `X-Correlation-Id` que el cliente recibió |
| `data` | objeto | El evento de dominio | El payload |

`occurredAt` y `sequence` conviven porque miden cosas distintas. `occurredAt`
es un reloj de pared y puede ir para atrás entre instancias; `sequence` sale de
la base y no. Para ordenar se usa `sequence`; `occurredAt` es para el texto del
mensaje y para el análisis.

### Payload

Los cuatro tipos comparten `reservationId`, `userId` e `itinerary`. El
`itinerary` es el `ItinerarySummary` del dominio, no el itinerario completo:
el consumidor necesita saber qué se reservó, no la lista de tramos.

`origin` / `destination` son códigos IATA; `price.amount` viaja como **string**
para no perder precisión en consumidores que parsean JSON a `double` —el mismo
motivo por el que `Money` usa `BigDecimal`—; los ids viajan como string aunque
en la base sean `BIGSERIAL`, para que un cambio futuro del tipo de id no rompa
el contrato.

**Qué no viaja, y no es negociable:** email, nombre y documento del pasajero,
datos de pago. El destinatario se identifica por `userId` interno y el sistema
de notificaciones resuelve el contacto, que es dato suyo. Un cambio de email no
obliga a reemitir nada.

Lo que sí viaja es ruta y fecha de viaje, porque sin eso el consumidor no puede
redactar el mensaje. Atado a un `userId` eso **es** dato personal, así que el
broker es un sistema de tratamiento y no un caño: TLS en tránsito, credenciales
propias por servicio, cuerpos de mensaje fuera de los logs (INFO registra
`type`, `subject` y `messageId`; el payload va a DEBUG, igual que hoy en
`LoggingNotificationAdapter`), retención acotada y contenido de la DLQ tratado
como dato productivo.

### `reservation.created`

```json
{
  "messageId": "0f7a6f2e-6b77-4a3a-9a5f-3c4a6b2f10d1",
  "type": "reservation.created",
  "version": 1,
  "source": "urn:edteam:flight-reservations",
  "subject": "8421",
  "sequence": 10493,
  "occurredAt": "2026-09-23T14:05:12.481Z",
  "publishedAt": "2026-09-23T14:05:14.902Z",
  "correlationId": "3f7c2b81-5a4e-4d62-9f31-2b0c8d5e7a14",
  "data": {
    "reservationId": "8421",
    "userId": "317",
    "passengerCount": 2,
    "itinerary": {
      "origin": "EZE",
      "destination": "MAD",
      "firstDeparture": "2026-11-12T23:40:00Z",
      "segmentCount": 2,
      "price": { "amount": "1843.75", "currency": "USD" }
    }
  }
}
```

### `reservation.confirmed`

```json
{
  "messageId": "b1c9d0a4-2f18-4f7b-8a0e-9d2c5e6f7a31",
  "type": "reservation.confirmed",
  "version": 1,
  "source": "urn:edteam:flight-reservations",
  "subject": "8421",
  "sequence": 10688,
  "occurredAt": "2026-09-23T14:31:07.115Z",
  "publishedAt": "2026-09-23T14:31:09.402Z",
  "correlationId": "7d1e9a02-4c6b-4a18-9e53-8f0b1d2c3e45",
  "data": {
    "reservationId": "8421",
    "userId": "317",
    "itinerary": {
      "origin": "EZE",
      "destination": "MAD",
      "firstDeparture": "2026-11-12T23:40:00Z",
      "segmentCount": 2,
      "price": { "amount": "1843.75", "currency": "USD" }
    }
  }
}
```

### `reservation.modified`

Lleva el itinerario anterior además del nuevo: sin eso el consumidor sólo puede
decir "tu reserva cambió" y no "tu vuelo pasó del 12 al 14". Reconstruirlo
pidiéndonos el estado previo sería imposible —ya no existe— y volvería a acoplar
al consumidor con nosotros.

```json
{
  "messageId": "5e2a7c31-9b04-4d6a-b3f8-1c7e0a9d4b62",
  "type": "reservation.modified",
  "version": 1,
  "source": "urn:edteam:flight-reservations",
  "subject": "8421",
  "sequence": 11204,
  "occurredAt": "2026-09-25T09:12:44.008Z",
  "publishedAt": "2026-09-25T09:12:46.331Z",
  "correlationId": "c48b7e10-6d3a-4f92-8b15-0e7a2c9d3f68",
  "data": {
    "reservationId": "8421",
    "userId": "317",
    "previousItinerary": {
      "origin": "EZE",
      "destination": "MAD",
      "firstDeparture": "2026-11-12T23:40:00Z",
      "segmentCount": 2,
      "price": { "amount": "1843.75", "currency": "USD" }
    },
    "itinerary": {
      "origin": "EZE",
      "destination": "MAD",
      "firstDeparture": "2026-11-14T22:10:00Z",
      "segmentCount": 1,
      "price": { "amount": "2110.00", "currency": "USD" }
    }
  }
}
```

### `reservation.cancelled`

```json
{
  "messageId": "9a3f1b58-7c20-4e6d-8f14-2b5c9e0a7d36",
  "type": "reservation.cancelled",
  "version": 1,
  "source": "urn:edteam:flight-reservations",
  "subject": "8421",
  "sequence": 11890,
  "occurredAt": "2026-09-26T18:02:55.740Z",
  "publishedAt": "2026-09-26T18:02:57.119Z",
  "correlationId": "af0c5d93-1e74-4b28-9c60-3d8a1f2b5e97",
  "data": {
    "reservationId": "8421",
    "userId": "317",
    "itinerary": {
      "origin": "EZE",
      "destination": "MAD",
      "firstDeparture": "2026-11-14T22:10:00Z",
      "segmentCount": 1,
      "price": { "amount": "2110.00", "currency": "USD" }
    }
  }
}
```

### Qué tiene que hacer el consumidor (el contrato del otro lado)

La entrega es **at-least-once**: los duplicados y el desorden no son fallas del
broker, son el modo normal de operación.

1. **Deduplicar por `messageId`.** Guardar los ids vistos con una TTL mayor a la
   ventana de retención del outbox (7 días). Un `messageId` repetido se hace
   `ack` sin volver a procesar.
2. **Usar `(subject, sequence)` para detectar el desorden, nunca para
   descartar.** El descarte por `sequence` aplica **sólo al mismo `messageId`**
   ya aplicado: eso es un duplicado. Un **evento distinto** que llega con un
   `sequence` menor al último aplicado se **aplica igual** y se registra como
   anomalía.

   > **Corrección del contrato (ver [ADR 0005 §7](../adr/0005-garantias-de-entrega-y-remediacion-de-la-mensajeria.md)).**
   > La regla original decía "descartar un mensaje cuyo `sequence` sea menor o
   > igual al último ya aplicado". Es correcta para un duplicado y **falsa para
   > dos eventos distintos que llegaron desordenados**, que es justo lo que el
   > backoff del relay y el ciclo de retry producen por diseño: `created`
   > (seq 10) entra al retry, `confirmed` (seq 11) se procesa, vuelve el 10 y el
   > consumidor lo descarta con `ack`. El usuario nunca recibe el alta y nadie se
   > entera, porque para el broker el mensaje se procesó bien. El mismo defecto
   > invalidaba el replay de la DLQ. Una notificación tardía es peor que una
   > puntual y mucho mejor que una silenciosamente descartada.
3. **Lector tolerante.** Ignorar los campos que no conoce, no fallar por un
   campo opcional ausente.
4. **Tipo desconocido con binding que lo trajo:** loguear y hacer `ack`. El
   binding es demasiado amplio; no es un mensaje venenoso.
5. **Atarse con `reservation.*`**, nunca con `reservation.#`. En un topic
   exchange `*` matchea exactamente una palabra: eso es lo que hace que una
   versión mayor futura (`reservation.created.v2`) **no** llegue a los
   consumidores viejos. Un binding con `#` recibiría v1 y v2 y duplicaría todo.

### Evolución del esquema

**Cambios compatibles** (se hacen sin avisar, dentro de la misma `version`):
agregar un campo opcional al `data`, agregar un campo al envelope, agregar un
**tipo nuevo** de evento con su routing key nueva. Un consumidor tolerante no
se entera.

**Cambios incompatibles** (necesitan versión mayor): renombrar o borrar un
campo, cambiarle el tipo, cambiarle el significado sin cambiarle el nombre
—el peor de los tres, porque no rompe nada hasta que rompe algo—, o volver
obligatorio un campo que era opcional.

**Procedimiento para una versión mayor**, apoyado en el routing del topic
exchange:

1. Se publica en paralelo: el mismo hecho sale con routing key
   `reservation.created` (v1, `"version": 1`) y `reservation.created.v2`
   (`"version": 2`). Los bindings existentes (`reservation.*`) siguen recibiendo
   sólo v1: no hay nada que coordinar con el consumidor.
2. Cada consumidor migra cuando puede, cambiando su binding a
   `reservation.*.v2`.
3. Cuando no queda nadie atado a v1 —se ve en la consola del broker, sin
   preguntarle a nadie— se apaga la publicación de v1.

El contrato ejecutable existe, y hoy es un test y no un JSON Schema:
`DomainEventPayloadMapperTest` fija los campos de los cuatro tipos, que el
importe viaje como string, que los ids viajen como string y —con una
*allowlist*, no una *denylist*— que no aparezca ningún campo nuevo sin que
alguien decida si es dato personal. Un campo que desaparece del payload rompe el
build, no al consumidor en producción, que es la propiedad que se buscaba. El
`docs/messaging/schemas/<type>.v<n>.schema.json` sigue pendiente: hace falta para
que el consumidor externo valide del otro lado sin leer nuestro código.

---

## 5. Webhook de entrada: la decisión es que no

**No se agrega ningún webhook de entrada en esta iteración.** Los tres
candidatos, y por qué ninguno entra:

| Candidato | Por qué no |
|---|---|
| **Acuse de entrega del sistema de notificaciones** | No hay ninguna regla de negocio que reaccione a "el mail rebotó": no reintentamos por otro canal, no cambiamos el estado de la reserva, no avisamos a nadie. El dato terminaría en un log. A cambio habría que exponer un endpoint público, y —más caro todavía— el consumidor volvería a llamar al productor, que es exactamente el acoplamiento que este diseño elimina. Quién recibió qué es observabilidad del dueño del canal. |
| **Proveedor de pagos** | No existe la integración. La confirmación de hoy es `POST /v1/reservations/{id}/confirmation`, autenticada, hecha por el dueño de la reserva. Diseñar el webhook ahora sería inventar un contexto de negocio y su contrato con un proveedor que todavía no se eligió. |
| **`api-catalog`** | No ofrece callbacks y no lo controlamos. El TTL del caché ya cubre la desactualización. |

Un webhook no es gratis: es un endpoint público, con su propio esquema de
autenticación, su propia superficie de ataque y su propio problema de
idempotencia. Se paga cuando hay un hecho que ocurre del lado de un tercero y
que cambia nuestro estado. Hoy no hay ninguno.

### Cuando lo haya (el diseño queda decidido de antemano)

El primero que va a justificarse es el de pagos; el segundo, un cambio de
itinerario informado por la aerolínea, que produciría un `reservation.modified`
sin que nadie haya llamado a la API. Cuando llegue:

- **Adaptador de entrada**, hermano del REST:
  `infrastructure/adapter/in/webhook/PaymentWebhookController`. Llama a un
  puerto de entrada (`RegisterPaymentOutcomeUseCase`), nunca a un servicio, y no
  deja que un tipo del proveedor cruce hacia `application`. Es el mismo lugar
  que ya ocupa `OutboxDispatchScheduler`: un disparador más.
- **Autenticación: firma HMAC-SHA256 sobre el cuerpo crudo**, no JWT. El que
  llama es una máquina que no tiene credenciales de nuestro proveedor de
  identidad. Header `X-Signature: t=<epoch>,v1=<hmac>`, comparación en tiempo
  constante, secreto por proveedor desde el gestor de secretos, y dos secretos
  aceptados a la vez para poder rotar sin ventana de caída.
- **Ventana de replay:** se rechaza si `|now - t| > 5 min`. El timestamp entra
  en el HMAC; si no, se recorta y se reusa una firma vieja.
- **Cuerpo crudo antes de parsear:** la firma es sobre los bytes exactos. Con el
  JSON ya deserializado y vuelto a serializar, la firma no valida.
- **Idempotencia:** el id de evento del proveedor va a una tabla
  `webhook_event` con `UNIQUE`. Un duplicado devuelve `200` sin reprocesar. Los
  proveedores reintentan: la entrada también es at-least-once.
- **Responder rápido y procesar después:** se persiste el evento crudo y se
  devuelve `2xx`; el trabajo lo hace un consumidor propio. Si no, el timeout del
  proveedor se convierte en un problema de correctitud nuestro.
- **No creerle al cuerpo en lo que importa:** el monto y el estado se releen
  contra la API del proveedor antes de mover plata.
- **Fuera de la cadena JWT, dentro del rate limit.** Se agrega la ruta al
  `SecurityConfiguration` como excepción del resource server, y **no** se la
  exceptúa del `RateLimitFilter`.

---

## 6. Qué cambia en el código

### Se conserva

| Paquete | Pieza | Comentario |
|---|---|---|
| `domain/event` | `DomainEvent` y los cuatro records | **Sin un solo cambio.** No aparece el broker, ni el envelope, ni la versión de esquema. El transporte no llega hasta acá |
| `domain/model` | `ItinerarySummary`, `Money`, ids | Sin cambios |
| `application/port/out` | `EventOutboxPort` | Misma firma. El javadoc ya anticipaba `FOR UPDATE SKIP LOCKED`: ahora se cumple |
| `application/port/in` | `DispatchPendingNotificationsUseCase` | Sin cambios |
| `application/service` | `OutboxDispatcherService` | La lógica —lote, fallo aislado que no corta el lote, at-least-once— queda igual. Cambia una línea: en vez de `notify(message.event())` llama a `publish(message)`. Es el retorno de la hexagonal: cambia el transporte de todo el sistema y el despachador casi no se entera |
| `application/service/*` (casos de uso) | `CreateReservationService`, `ConfirmReservationService`, `ModifyReservationService`, `CancelReservationService` | Sin cambios. Siguen haciendo `eventOutbox.enqueue(...)` dentro de la transacción |
| `infrastructure/adapter/in/scheduling` | `OutboxDispatchScheduler` | Se conserva como disparador del relay. Con el outbox en la base y `SKIP LOCKED`, varias instancias pueden correrlo en paralelo sin duplicar: ya no hace falta el lock distribuido que el javadoc mencionaba. Se le agrega jitter al intervalo para que N instancias no despierten juntas |
| `infrastructure/config` | `SchedulingConfiguration` | El pool propio para el despacho sigue siendo correcto, y más ahora que hay I/O contra el broker |

### Se reemplaza

| Paquete | Sale | Entra | Por qué |
|---|---|---|---|
| `infrastructure/adapter/out/outbox` | `InMemoryEventOutbox` | `JdbcEventOutbox` sobre la tabla `outbox_message` | Es el cambio que importa: los pendientes dejan de perderse si el proceso se cae y dejan de ser uno por instancia. El stub **sobrevive como fixture de test** (se mueve a `src/test/.../support`), que es donde siempre debió estar |
| `infrastructure/adapter/out/notification` | `LoggingNotificationAdapter` como camino único | `RabbitEventPublisher` cuando hay broker configurado; `LoggingEventPublisher` cuando no | El fallback no es una concesión: es el mismo patrón que `StaticAirportCatalog` y el caché en memoria, y es lo que hace que `mvn test` y `spring-boot:run` no dependan de un contenedor. Arranca avisando en el log que los eventos no salen a ningún lado |
| `application/port/out` | `NotificationPort.notify(DomainEvent)` | `EventPublisherPort.publish(OutboxMessage)` | El puerto sobrevive como concepto —un puerto de salida para despachar hechos— pero el nombre miente en cuanto el destino es un exchange: nombra a un consumidor que el productor no debería conocer. Son tres usos en todo el código |
| `application/exception` | `NotificationDeliveryException` | `EventPublishException` | Mismo motivo |
| `application/outbox` | `OutboxMessage(id, event, attempts, status, enqueuedAt)` | `OutboxMessage(id, type, schemaVersion, subject, sequence, payload, correlationId, occurredAt, enqueuedAt, attempts, status)` | El payload se serializa **al encolar**, dentro de la transacción del caso de uso. Tres consecuencias: lo que se publica es exactamente lo que pasó aunque el código cambie entre el encolado y el despacho; el relay reenvía bytes y no necesita conocer los tipos de evento (agregar un quinto no lo toca); y no hay que deserializar una jerarquía sellada de vuelta. `payload` es un `String` JSON opaco para la aplicación: no arrastra ninguna clase del broker |
| `application/outbox` | `OutboxStatus.FAILED` como estado terminal mudo | El mismo valor, con salida: métrica, alerta y endpoint de replay | Deja de ser una promesa incumplida |
| `src/test` | `LoggingNotificationAdapterTest`, `InMemoryEventOutboxTest` | Tests del publicador, del outbox JDBC y del mapper | Se pierde el `switch` exhaustivo que redactaba el texto de la notificación. No es una pérdida real: redactar el mensaje nunca fue nuestro trabajo, es del dueño de las plantillas. El `switch` exhaustivo **se conserva** en el mapper de payload, así que el compilador sigue avisando si aparece un tipo de evento nuevo |

### Se agrega

| Paquete / archivo | Qué | Para qué |
|---|---|---|
| `infrastructure/adapter/out/messaging/RabbitEventPublisher` | Publicador AMQP | Arma el envelope, publica con `mandatory=true` y espera el *publisher confirm*. Marca `DISPATCHED` **sólo** con el ack del broker; un mensaje no ruteable (sin binding) es un fallo, no un descarte silencioso |
| `infrastructure/adapter/out/messaging/EventEnvelope` | DTO del envelope | El contrato serializado, con su test contra el JSON Schema |
| `infrastructure/adapter/out/messaging/DomainEventPayloadMapper` | Evento de dominio → JSON del `data` | Acá vive el `switch` exhaustivo sobre la interfaz sellada. Es el único punto que conoce los cuatro tipos, y está en infraestructura: el dominio no sabe serializarse |
| `infrastructure/adapter/out/messaging/RabbitTopologyConfiguration` | Declaración del exchange | En cualquier entorno, sólo `reservations.events`. Las colas del consumidor sólo bajo `declare-consumer-topology`, para el compose local. La declaración es perezosa y con reintento: un broker caído al arrancar no puede impedir el arranque |
| `infrastructure/adapter/out/outbox/JdbcEventOutbox` | Outbox durable | `enqueue` en la transacción del caso de uso; `pollPending` con `FOR UPDATE SKIP LOCKED` y `next_attempt_at <= now()`; backoff exponencial con jitter en `markFailed`, con el mismo vocabulario que la política de reintentos del catálogo. Toma el `correlationId` del MDC al encolar y el relay lo restituye al publicar, para que la traza no se corte |
| `infrastructure/adapter/in/scheduling/OutboxPurgeScheduler` | Purga diaria | Borra los `DISPATCHED` de más de 7 días. Sin esto la tabla crece para siempre y el índice de pendientes se degrada |
| `infrastructure/adapter/in/ops/OutboxEndpoint` | Endpoint Actuator `outbox` | Lista y reencola los `FAILED`. Va en el puerto de gestión (9090), que ya no se publica hacia afuera: es la herramienta de la dead letter del productor |
| `infrastructure/config/MessagingProperties` | Configuración | Exchange, habilitado, timeouts **por proveedor** —`connect-timeout: 2s`, `confirm-timeout: 5s`— siguiendo la decisión ya tomada de no declarar timeouts globales |
| `db/migration/V3__outbox.sql` | Tabla `outbox_message` | Con `sequence BIGSERIAL`, índice parcial `WHERE status = 'PENDING'` sobre `(next_attempt_at, sequence)` y otro sobre los `FAILED` |
| `docs/messaging/schemas/*.schema.json` | JSON Schema por tipo y versión | El contrato ejecutable |
| `compose.yaml` | Servicio `rabbitmq:4-management-alpine` | Con healthcheck, credenciales por `${VAR:-default}` y los puertos publicados **sólo en loopback**, por el mismo motivo por el que Redis lo está: un broker alcanzable desde la red es un broker donde cualquiera publica `reservation.cancelled` |
| `pom.xml` | `spring-boot-starter-amqp`, `org.testcontainers:rabbitmq` (test) | Cliente y test de integración con broker real, en la fase `verify` como el de Postgres |
| `src/test/.../architecture` | Regla ArchUnit nueva | `domain` y `application` no pueden depender de `org.springframework.amqp..` ni de `com.rabbitmq..` ni de `infrastructure.adapter.out.messaging..`. La misma regla que ya existe para el caché y para la seguridad: la disciplina que falla en el build |

### Configuración

```yaml
spring:
  rabbitmq:
    host: ${RABBIT_HOST:localhost}
    port: ${RABBIT_PORT:5672}
    username: ${RABBIT_USERNAME:reservations}
    password: ${RABBIT_PASSWORD:}
    connection-timeout: 2s
    publisher-confirm-type: correlated   # DISPATCHED sólo con ack del broker
    publisher-returns: true              # un mensaje sin binding es un error
    template:
      mandatory: true

reservations:
  messaging:
    # Apagarlo deja el publicador que sólo loguea: la aplicación arranca y los
    # tests corren sin broker, igual que arrancan sin Redis y sin el catálogo.
    enabled: ${MESSAGING_ENABLED:true}
    exchange: reservations.events
    confirm-timeout: 5s
    # Sólo en local: declara también las colas del consumidor para que
    # 'docker compose up' deje el circuito completo andando.
    declare-consumer-topology: ${MESSAGING_DECLARE_CONSUMER_TOPOLOGY:false}

  outbox:
    dispatch-enabled: true
    dispatch-interval: 5s
    batch-size: 50
    max-attempts: 5
    initial-backoff: 5s
    max-backoff: 5m
    retention: 7d

management:
  health:
    rabbit:
      # Apagado, por el mismo motivo que el de Redis: el broker caído degrada
      # (los eventos se acumulan en el outbox) y no impide responder pedidos.
      # Marcarnos DOWN nos sacaría de rotación por algo que no afecta al cliente.
      enabled: false
```

### Métricas

| Métrica | Qué dice | Cuándo alerta |
|---|---|---|
| `reservations.outbox.pending` (gauge) | Cuántos esperan | Crece sostenido: el broker no acepta o el relay no corre |
| `reservations.outbox.lag` (gauge) | `now - min(enqueued_at)` de los pendientes | El número que importa: cuánto tarda una notificación desde que el hecho ocurrió |
| `reservations.outbox.dispatched` / `.failed` (counters) | Ritmo de despacho y de fallo | — |
| `reservations.outbox.dead` (gauge) | Filas en `FAILED` | **> 0**: dead letter del productor, hay que mirarla |
| Profundidad de `notifications.reservation-events.dlq` | Dead letter del consumidor | **> 0** |

Las dos últimas son distintas a propósito y por eso se miden por separado: una
dice "no pudimos publicar", la otra dice "no pudieron procesar". Se resuelven
en lugares distintos y con gente distinta.
