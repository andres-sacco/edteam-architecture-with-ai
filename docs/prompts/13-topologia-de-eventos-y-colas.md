# 13 — Diseño de la topología de eventos, colas y webhooks

**Etapa:** Decisión arquitectónica

**Salida esperada:** Topología de mensajería: lista de colas/tópicos con su publicador, su consumidor y el contrato de cada mensaje

---

## Rol

Actúa como arquitecto de sistemas distribuidos.

## Contexto

El sistema de reservas de vuelos es un proyecto Maven con **Java 21 + Spring Boot 3.5** y arquitectura hexagonal (un solo módulo, separación por paquetes):

```
com.edteam.reservations
├── domain          # model, event, access, exception — sin Spring, sin JPA, sin HTTP
├── application     # port/in (casos de uso + comandos), port/out, service, query, outbox, audit
└── infrastructure  # adapter/in/rest, adapter/in/scheduling,
                    # adapter/out/persistence, adapter/out/airport,
                    # adapter/out/notification, adapter/out/outbox, adapter/out/audit,
                    # cache, config, security, logging
```

Hasta acá **toda la comunicación del sistema es síncrona sobre HTTP**, con una única excepción parcial:

- **Entrada:** una API REST (`/v1/reservations`, altas, lecturas, modificación, confirmación y cancelación) consumida por varios frontends con muchos usuarios concurrentes. Autenticada con JWT, con locking optimista expuesto como `ETag`/`If-Match`.
- **Salida hacia PostgreSQL** (Flyway, JPA) y hacia **Redis** como caché distribuida opcional.
- **Salida hacia un catálogo externo de ciudades** (`api-catalog`) por REST, con timeouts, reintentos sólo sobre el `GET` idempotente y caché con *stale-while-error*.
- **Salida hacia un sistema externo de notificaciones**, que es la parte ya asincrónica: existe un **outbox transaccional** incipiente.

El estado actual de esa pieza asincrónica, que es el punto de partida de este diseño:

- `domain/event` define una interfaz sellada `DomainEvent` con cuatro hechos de negocio: `reservation.created`, `reservation.confirmed`, `reservation.modified`, `reservation.cancelled`. Cada evento lleva `reservationId`, `userId`, `occurredAt`, `eventType` y un `ItinerarySummary`; identifica al destinatario por id interno y **no** por su email, que es dato del sistema de notificaciones.
- `application/port/out/EventOutboxPort` guarda los eventos (`enqueue`) en la misma transacción que la reserva y los toma para despachar (`pollPending`, `markDispatched`, `markFailed`).
- `application/port/out/NotificationPort` los envía afuera. Ningún caso de uso lo llama directo.
- `application/service/OutboxDispatcherService` es el despachador: toma un lote, envía uno por uno, un fallo aislado no corta el lote. La entrega es **at-least-once** declarada.
- `infrastructure/adapter/in/scheduling/OutboxDispatchScheduler` lo dispara cada 5s (`reservations.outbox.dispatch-interval`, `batch-size: 50`, `max-attempts: 5`).
- `infrastructure/adapter/out/outbox/InMemoryEventOutbox` es un **stub en memoria**: los pendientes se pierden si el proceso se cae y cada instancia tiene el suyo.
- `infrastructure/adapter/out/notification/LoggingNotificationAdapter` es otro **stub**: sólo escribe en el log.
- `OutboxStatus` ya contempla `FAILED` como "agotó reintentos, requiere intervención (dead letter)", pero **no existe ninguna dead-letter queue real**: un mensaje en `FAILED` simplemente deja de moverse.

La infraestructura local es `compose.yaml`: PostgreSQL 17, Redis 7 y el `api-catalog` con su MySQL. **No hay ningún broker de mensajería.**

## Tarea

Diseñar la topología de mensajería del sistema: qué se comunica de forma asincrónica, con qué mecanismo y bajo qué contrato.

1. Listar los **participantes** de la comunicación —el servicio de reservas, el de notificaciones, el catálogo, y cualquier otro que el diseño justifique— y qué necesita saber cada uno del otro.
2. Para cada comunicación, elegir el **mecanismo** y justificar por qué ése y no otro:
   - **evento** (algo pasó y se publica para quien le interese, sin saber quién escucha),
   - **cola** (trabajo encargado a un consumidor conocido, que espera aunque esté caído),
   - **webhook** (un tercero llama a una URL nuestra cuando ocurre algo de su lado),
   - o **llamada síncrona**, cuando la respuesta forma parte de la operación del usuario.
3. Definir las **colas y tópicos**: nombre, quién publica, quién consume, qué garantía de entrega tiene y qué pasa con el orden de los mensajes.
4. Definir el **contrato de cada mensaje**: envelope (id de mensaje, tipo, versión, `occurredAt`, clave de correlación), payload, y cómo evoluciona el esquema sin romper a los consumidores existentes.
5. Decidir si el **webhook de entrada** tiene lugar en este sistema (por ejemplo, un proveedor de pago o el propio sistema de notificaciones confirmando la entrega). Si lo tiene, diseñarlo como adaptador de entrada con su autenticación; si no lo tiene, decirlo explícitamente y por qué.
6. Definir cómo cambia el **camino de salida actual**: qué pasa con el outbox en memoria, con el despachador por scheduler y con el `NotificationPort`; qué se conserva y qué se reemplaza.

## Restricciones

- **Free tier**: el broker tiene que existir en una capa gratuita o correr en el `compose.yaml` del repositorio. Nada que exija un plan pago para levantar el proyecto localmente.
- **Minimizar el acoplamiento**: el productor no espera respuesta del consumidor ni conoce su existencia. Si el diseño necesita que el productor sepa quién consume, hay que justificarlo.
- La mensajería es **infraestructura**: `domain` y `application` no pueden importar el cliente del broker. Los eventos de dominio ya existen y no cambian de forma por el transporte elegido.
- **La aplicación tiene que seguir arrancando y los tests corriendo sin el broker disponible**, igual que hoy arranca sin Redis y sin el catálogo externo.
- **Nada de datos sensibles en los mensajes**: ni documento del pasajero, ni email, ni datos de pago. El evento identifica por id interno.
- La entrega es **at-least-once**: el diseño tiene que asumir mensajes duplicados y fuera de orden, no suponer que el broker los evita.
- Coherencia con lo ya decidido: las operaciones no idempotentes no se reintentan, los timeouts se declaran por proveedor y una dependencia caída degrada en lugar de tumbar el servicio.

## Formato de salida

1. **Tabla de comunicaciones**: origen | destino | qué se comunica | mecanismo elegido | por qué ése.
2. **Lista de colas y tópicos creados**: nombre | publicador | consumidor(es) | garantía de entrega | orden | retención.
3. **Contrato de mensaje** por tipo de evento: envelope y payload, con un ejemplo en JSON.
4. **Qué cambia en el código actual**: piezas que se conservan, se reemplazan o se agregan, por paquete.

