# 14 — Auditoría de la mensajería: idempotencia, dead-letter y reintentos

**Etapa:** Revisión

**Salida esperada:** Tabla de hallazgos con riesgo, evidencia, forma de detectarlo y mitigación

---

## Rol

Actúa como revisor de sistemas event-driven, con foco en las fallas que no se ven hasta que el sistema está en producción.

## Contexto

El sistema de reservas de vuelos (Java 21 + Spring Boot 3.5, arquitectura hexagonal, PostgreSQL + Redis) tiene una topología de mensajería recién diseñada y una implementación parcial ya en el repositorio.

**Lo que hay en el código hoy:**

- `domain/event`: interfaz sellada `DomainEvent` con `reservation.created`, `reservation.confirmed`, `reservation.modified` y `reservation.cancelled`.
- `application/port/out/EventOutboxPort`: `enqueue` en la misma transacción que la reserva; `pollPending`, `markDispatched`, `markFailed`.
- `application/service/OutboxDispatcherService`: toma un lote, envía uno por uno, captura la excepción de cada mensaje para no cortar el lote y declara entrega **at-least-once**.
- `infrastructure/adapter/in/scheduling/OutboxDispatchScheduler`: `@Scheduled` cada 5s, lote de 50, apagable por propiedad.
- `infrastructure/adapter/out/outbox/InMemoryEventOutbox`: outbox **en memoria**, con `maxAttempts = 5` y un estado `FAILED` que se describe como dead letter.
- `infrastructure/adapter/out/notification/LoggingNotificationAdapter`: stub que sólo escribe en el log; el `NotificationPort` documenta que el mismo evento puede llegar más de una vez y sugiere propagar el id como clave de idempotencia.

**Lo que aporta el paso anterior:** la topología diseñada en [13 — Topología de eventos y colas](13-topologia-de-eventos-y-colas.md): colas y tópicos, publicadores, consumidores y contratos de mensaje.

La entrada de este prompt son **las dos cosas**: el diseño y el código que hoy lo implementa a medias.

## Tarea

Auditar diseño e implementación buscando las inconsistencias que pasan inadvertidas en una revisión normal. Como mínimo:

1. **Idempotencia**: ¿qué pasa si el mismo mensaje se procesa dos veces? Identificar cada consumidor que no sea idempotente y qué rompe exactamente (notificación duplicada, doble efecto de negocio, contador inflado).
2. **Dead-letter queue**: ¿existe una DLQ real o sólo un estado terminal del que nadie se entera? ¿Quién mira los mensajes que agotaron intentos y cómo se reprocesan después de arreglar la causa?
3. **Reintentos**: ¿hay un límite? ¿Hay backoff, o se reintenta en caliente contra un destino que ya está caído? ¿Qué distingue un fallo transitorio de uno permanente, y se reintenta un mensaje que nunca va a poder procesarse (*poison message*)?
4. **Acoplamiento síncrono encubierto**: ¿hay algún punto donde el productor termine esperando la respuesta del consumidor, o donde la caída del consumidor afecte la latencia o la disponibilidad de la API?
5. **Pérdida de mensajes**: ¿en qué punto exacto del camino un mensaje puede desaparecer sin que nadie lo note (proceso que se cae, transacción que no incluye el encolado, ack antes de procesar)?
6. **Orden y concurrencia**: ¿qué pasa si `reservation.modified` llega antes que `reservation.created`, o si dos instancias del despachador toman el mismo mensaje?
7. **Observabilidad**: ¿se puede saber, sin entrar a la base, cuántos mensajes hay pendientes, cuántos fallaron y hace cuánto está trabado el más viejo?

Para **cada hallazgo**, además del problema, definir **cómo detectarlo de forma concreta** —una prueba que se pueda ejecutar, no una inspección visual—. Por ejemplo, y sin limitarse a esto:

- reprocesar el mismo mensaje dos veces y verificar que el estado final sea el mismo,
- verificar que exista una dead-letter queue configurada y que un mensaje que agota intentos efectivamente llegue ahí,
- simular la caída del consumidor y observar qué pasa con la cola, con la latencia de la API y con el consumo de memoria,
- matar el proceso entre el commit de la reserva y el envío, y verificar que la notificación igual salga.

## Restricciones

- **Sólo hallazgos con evidencia**: cada uno tiene que apuntar a un archivo y una línea del repositorio, o a un punto concreto del diseño. Nada de riesgos genéricos de manual.
- Distinguir lo que es **una falla real** de lo que es **una limitación asumida y documentada** (el outbox en memoria, por ejemplo, está declarado como stub deliberado): lo segundo se lista aparte, no como hallazgo.
- Priorizar por **impacto en el usuario y en el negocio**, no por facilidad de arreglo.
- No proponer todavía el código de la solución: este paso identifica y ordena; la remediación es el paso siguiente.
- Las pruebas de detección tienen que poder correr en el entorno del repositorio (`compose.yaml` + `mvn verify`), sin infraestructura paga.

## Formato de salida

1. **Tabla de hallazgos**: # | hallazgo | categoría (idempotencia / DLQ / reintentos / acoplamiento / pérdida / orden / observabilidad) | evidencia (archivo:línea o punto del diseño) | impacto | severidad.
2. **Tabla de detección**: hallazgo | prueba concreta que lo expone | resultado esperado si está bien | resultado esperado si está mal.
3. **Mitigación propuesta** por hallazgo, en una o dos líneas, sin escribir el código.
4. **Limitaciones asumidas**, listadas aparte con el motivo por el que no son hallazgos.
5. **Orden sugerido de remediación**, con el criterio usado para ordenarlo.

