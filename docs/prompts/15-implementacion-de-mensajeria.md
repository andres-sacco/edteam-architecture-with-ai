# 15 — Implementación de la mensajería: outbox durable, broker y consumidor idempotente

**Etapa:** Implementación

**Salida esperada:** Adaptadores de mensajería en el código, con idempotencia, dead-letter queue, reintentos acotados y tests

---

## Rol

Actúa como desarrollador backend Java/Spring Boot con experiencia en sistemas event-driven, implementando la topología ya diseñada y corrigiendo los hallazgos de la auditoría previa.

## Contexto

Existe un proyecto Maven con **Java 21 + Spring Boot 3.5** y arquitectura hexagonal (un solo módulo, separación por paquetes):

```
com.edteam.reservations
├── domain          # model, event, access, exception — sin Spring, sin JPA, sin HTTP
├── application     # port/in, port/out, service, query, outbox, audit, exception
└── infrastructure  # adapter/in/rest, adapter/in/scheduling,
                    # adapter/out/persistence, adapter/out/airport,
                    # adapter/out/notification, adapter/out/outbox, adapter/out/audit,
                    # cache, config, security, logging
```

La API REST (`/v1/reservations`) está autenticada con JWT, usa locking optimista con `ETag`/`If-Match`, persiste en PostgreSQL con Flyway y cachea en Redis con fallback en memoria. El catálogo externo de ciudades se consume por REST con timeouts propios, reintentos sólo sobre el `GET` y *stale-while-error*. El `compose.yaml` levanta PostgreSQL 17, Redis 7 y el `api-catalog`.

**El camino asincrónico que ya existe** y que este paso lleva a su forma definitiva:

- `domain/event`: `DomainEvent` sellada con `reservation.created`, `reservation.confirmed`, `reservation.modified`, `reservation.cancelled`. Identifican al destinatario por `userId` interno, nunca por email.
- `application/port/out/EventOutboxPort` (`enqueue` transaccional, `pollPending`, `markDispatched`, `markFailed`) y `application/port/out/NotificationPort` (`notify`, documentado como at-least-once).
- `application/service/OutboxDispatcherService`: despacha por lotes, un fallo aislado no corta el lote.
- `infrastructure/adapter/in/scheduling/OutboxDispatchScheduler`: `@Scheduled` cada 5s, lote de 50, `max-attempts: 5`, apagable con `reservations.outbox.dispatch-enabled=false` (es lo que hacen los tests).
- `infrastructure/adapter/out/outbox/InMemoryEventOutbox`: **stub en memoria**, se pierde al reiniciar y cada instancia tiene el suyo.
- `infrastructure/adapter/out/notification/LoggingNotificationAdapter`: **stub** que sólo escribe en el log.
- `OutboxStatus.FAILED` está descripto como dead letter, pero **no hay ninguna DLQ real**.

Las entradas de este prompt son la **topología diseñada** en [13](13-topologia-de-eventos-y-colas.md) y la **tabla de hallazgos priorizada** de [14](14-auditoria-de-mensajeria.md). Ese material dice *qué* hay que construir y *qué* está mal hoy; este paso lo lleva al código.

## Tarea

1. **Outbox durable**: reemplazar `InMemoryEventOutbox` por una implementación sobre PostgreSQL con su migración Flyway (`outbox_message`), escrita en la misma transacción que la reserva. El `pollPending` tiene que permitir que varias instancias despachen en paralelo sin entregar dos veces el mismo mensaje (`SELECT ... FOR UPDATE SKIP LOCKED`).
2. **Adaptador de salida hacia el broker**: implementar el publicador de la topología elegida como adaptador en `infrastructure/adapter/out`, detrás de un puerto. Agregar el broker al `compose.yaml` con su configuración por variables de entorno.
3. **Adaptador de entrada consumidor**: implementar el consumo como adaptador en `infrastructure/adapter/in`, delegando en un caso de uso de `application`. El consumidor no puede contener lógica de negocio.
4. **Idempotencia**: implementar la deduplicación del lado del consumidor con la clave definida en el contrato (id de mensaje o clave de negocio), con su almacenamiento y su ventana de retención. Procesar dos veces el mismo mensaje tiene que dejar exactamente el mismo estado.
5. **Dead-letter queue real**: un mensaje que agota sus intentos va a una DLQ, no a un estado del que nadie se entera. Definir e implementar cómo se inspecciona y cómo se reprocesa después de arreglar la causa.
6. **Reintentos acotados con backoff**: límite de intentos, espera exponencial con jitter y distinción explícita entre fallo transitorio (se reintenta) y permanente (va directo a la DLQ, no se reintenta).
7. **Observabilidad**: exponer por Actuator/Micrometer los mensajes pendientes, la antigüedad del más viejo, los despachados, los fallidos y el tamaño de la DLQ. Sin esto no hay forma de enterarse de que la cola se trabó.
8. **Tests**, uno por hallazgo de la auditoría: que el mismo mensaje procesado dos veces no duplique efectos; que un mensaje que agota intentos termine en la DLQ; que la caída del consumidor no afecte la latencia de la API; que un proceso que muere entre el commit y el envío no pierda la notificación; que dos despachadores concurrentes no entreguen el mismo mensaje.
9. **Documentación**: ADR en `docs/adr/` con la topología y las garantías elegidas, y actualización del `README.md` con cómo levantar el broker y cómo inspeccionar la DLQ.

## Restricciones

- **Respetar el orden de prioridad de la tabla de hallazgos**: primero lo que más impacta al usuario, no lo más fácil de implementar.
- **Free tier**: el broker corre en el `compose.yaml` o en una capa gratuita. Nada que exija un plan pago para levantar el proyecto.
- **La mensajería es infraestructura**: `domain` y `application` no importan el cliente del broker ni sus anotaciones. Los puertos no cambian de firma por el transporte elegido, y los eventos de dominio no se contaminan con el envelope del mensaje.
- `HexagonalArchitectureTest` (ArchUnit) tiene que seguir en verde; si hace falta, agregar la regla que impida que el broker se filtre hacia adentro.
- **La aplicación tiene que seguir arrancando y los tests corriendo sin el broker disponible**, igual que hoy arranca sin Redis y sin el catálogo externo: el publicador real se activa por configuración y hay un fallback local.
- **Un broker caído no puede tumbar el servicio**: los eventos se acumulan en el outbox y se despachan cuando vuelve; la API responde igual.
- **El productor no espera al consumidor.** Si alguna parte del flujo necesita la respuesta, hay que decirlo y justificar por qué esa comunicación no es asincrónica.
- **Nada de datos sensibles en los mensajes ni en la DLQ**: ni documento del pasajero, ni email, ni datos de pago. Vale lo mismo para los logs del consumidor, que hoy pasan por el enmascarado de PII.
- Coherencia con lo ya decidido: timeouts declarados por proveedor, reintentos sólo sobre operaciones idempotentes, y degradación en lugar de propagación del error.
- Los tests existentes tienen que seguir pasando; si alguno deja de tener sentido bajo el nuevo esquema, adaptarlo y justificar el cambio.

## Formato de salida

1. **Tabla de trazabilidad**: hallazgo de la auditoría | mitigación implementada | archivos afectados | test que lo verifica.
2. **Lista de archivos a crear o modificar**, agrupados por paquete, con el contenido completo de cada uno (migración Flyway, adaptador de outbox, publicador, consumidor, deduplicación, configuración, propiedades, `compose.yaml`, tests, ADR).
3. **Tabla de la topología final**: cola/tópico | publicador | consumidor | garantía | DLQ asociada | clave de idempotencia.
4. **Hallazgos que no se remedian en este paso**, con el motivo y qué haría falta para revisarlos.
5. **Comandos de verificación**: cómo levantar el broker, cómo correr el build y los tests, y cómo reproducir a mano la prueba de doble procesamiento y la de caída del consumidor.

