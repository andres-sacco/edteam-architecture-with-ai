# 0004 — Mensajería asincrónica: eventos sobre RabbitMQ con outbox en PostgreSQL

- **Estado:** Aceptado
- **Fecha:** 2026-09-23
- **Prompt origen:** [13 — Topología de eventos y colas](../prompts/13-topologia-de-eventos-y-colas.md)
- **Diseño completo:** [`docs/messaging/topology.md`](../messaging/topology.md)
- **Auditoría:** [`docs/messaging/audit.md`](../messaging/audit.md)

## Contexto

Toda la comunicación del sistema es HTTP síncrono salvo una pieza a medio
construir: el outbox hacia el sistema externo de notificaciones. Hoy el outbox
vive en el heap (`InMemoryEventOutbox`), así que los pendientes se pierden si el
proceso se cae y cada instancia tiene el suyo; el envío es un log
(`LoggingNotificationAdapter`); y `OutboxStatus.FAILED` documenta una
dead-letter queue que no existe: un mensaje que agota los reintentos
simplemente deja de moverse y nadie se entera.

No hay broker en `compose.yaml`. Hay que elegir uno —o decidir no tener
ninguno— sabiendo que cualquier opción tiene que levantarse en el repositorio
sin un plan pago, que `domain` y `application` no pueden importar su cliente, y
que la aplicación tiene que seguir arrancando cuando no esté disponible.

## Decisión

**Los cuatro hechos de negocio se publican como eventos a un *topic exchange*
de RabbitMQ, con el outbox transaccional mudado a una tabla de PostgreSQL.**

1. **Mecanismo: evento, no cola.** El relay publica a `reservations.events`
   (topic exchange) con el `eventType()` como routing key. El productor no
   nombra ningún destinatario: no sabe si hay un consumidor, tres o ninguno. Una
   cola directa hacia notificaciones haría exactamente lo mismo hoy y obligaría
   a tocar nuestro código el día que aparezca un segundo interesado.
2. **Broker: RabbitMQ.** Imagen OSS en `compose.yaml`, capa gratuita en
   CloudAMQP. Da el routing declarativo que necesita el pub/sub y, como
   primitivas del broker, la DLQ, la TTL y los límites de cola que de otro modo
   habría que escribir. Kafka/Redpanda se descartó: aporta orden por partición y
   *replay*, que este sistema no usa, a un costo operativo alto. Redis Streams
   se descartó aunque ya esté levantado: ese Redis está declarado como caché
   descartable (`--save ""`, `allkeys-lru`) y usarlo como broker significaría
   que una ráfaga de tráfico desaloja notificaciones por política LRU.
3. **El outbox pasa a PostgreSQL** (`outbox_message`, escrito en la transacción
   del caso de uso, leído con `FOR UPDATE SKIP LOCKED`). El broker no reemplaza
   al outbox: es lo que está **después** del outbox. Esa separación es lo que
   permite que la aplicación arranque y responda con el broker caído.
4. **Dos dead letters, no una.** Lo que no se pudo **publicar** queda en la
   tabla (`status = FAILED`, con endpoint de replay en el puerto de gestión); lo
   que no se pudo **procesar** queda en la DLQ del consumidor en el broker. Si
   lo que está caído es el broker, un dead letter dentro del broker es
   inalcanzable justo cuando hace falta.
5. **El payload se serializa al encolar**, dentro de la transacción. Lo que se
   publica es exactamente lo que pasó aunque el código cambie entre el encolado
   y el despacho, y el relay reenvía bytes sin conocer los tipos de evento.
6. **Sin webhook de entrada.** Ni acuse de entrega de notificaciones —no hay
   regla de negocio que reaccione, y volvería a acoplar al consumidor con el
   productor— ni pagos, porque esa integración no existe. El diseño del primero
   que se justifique queda escrito de antemano en §5 del documento de topología.
7. **El puerto se renombra.** `NotificationPort` → `EventPublisherPort`: el
   nombre nombraba a un consumidor que el productor no debería conocer.

Los eventos de dominio **no cambian**. El envelope —`messageId`, `sequence`,
`correlationId`, versión de esquema— se arma en infraestructura a partir de la
fila del outbox, no dentro del evento.

## Consecuencias

**A favor**

- Una notificación deja de perderse por un reinicio o por una caída del
  consumidor: la fila está comprometida junto con la reserva.
- Varias instancias despachan en paralelo sin duplicar ni necesitar un lock
  distribuido, gracias a `SKIP LOCKED`.
- Un consumidor nuevo se suscribe sin que cambie una línea del productor.
- El `FAILED` deja de ser un callejón sin salida: hay métrica, alerta y replay.
- `OutboxDispatcherService`, los casos de uso y los eventos de dominio no se
  tocan. Es la hexagonal pagando: cambia el transporte de todo el sistema y la
  lógica de despacho cambia una línea.

**En contra, y asumido**

- **Un componente más de infraestructura**, con su credencial, su TLS, su
  monitoreo y su modo de falla propio.
- **La entrega es at-least-once**: duplicados y desorden son el modo normal, no
  una anomalía. El costo se traslada al consumidor, que debe deduplicar por
  `messageId` e ignorar `sequence` viejas. Es explícito en el contrato.
- **Sin broker, los eventos no salen.** El fallback loguea y marca
  `DISPATCHED`: mismo trato que el stub del catálogo y el caché en memoria, y
  con el mismo riesgo de que alguien lo deje encendido donde no corresponde. Se
  mitiga avisando en cada arranque.
- **Se pierde el texto de la notificación** que redactaba
  `LoggingNotificationAdapter`. Redactar nunca fue nuestro trabajo; el `switch`
  exhaustivo sobre la interfaz sellada se conserva en el mapper de payload.
- **Los mensajes llevan ruta y fecha de viaje** atadas a un `userId`: sin eso el
  consumidor no puede redactar nada. El broker pasa a ser un sistema de
  tratamiento de datos personales, con lo que eso implica (TLS, retención
  acotada, cuerpos fuera de los logs, DLQ tratada como dato productivo). Email,
  nombre y documento siguen sin viajar.
- **Hay una tabla más que mantener**: sin la purga de `DISPATCHED`, crece para
  siempre.
