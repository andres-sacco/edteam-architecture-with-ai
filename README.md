# Sistema de reservas de vuelos — esqueleto

Backend del sistema de reservas de vuelos. **Java 21 + Spring Boot 3.5 + Maven**, con
arquitectura hexagonal (puertos y adaptadores).

El dominio, los casos de uso y la persistencia sobre PostgreSQL están implementados.
Endpoints REST y seguridad quedan fuera del alcance a propósito
(ver [Fuera de alcance](#fuera-de-alcance)).

## Cómo ejecutarlo

Requiere JDK 21 (hay un `.sdkmanrc`: `sdk env install && sdk env`) y Docker.

```bash
docker compose up -d
```

```bash
./mvnw spring-boot:run
```

La aplicación queda escuchando en **http://localhost:8080** y Flyway crea el esquema en
el arranque. Todavía no hay endpoints de negocio; para verificar que levantó:

```bash
curl http://localhost:8080/actuator/health
```

Los tests, separados por lo que necesitan:

```bash
./mvnw test
```

```bash
./mvnw verify
```

`test` corre los 180 unitarios: rápidos y sin Docker. `verify` agrega los 27 de
integración, que levantan un PostgreSQL con Testcontainers.

## Estructura

```
com.edteam.reservations
├── domain                      # El centro. Sin Spring, sin JPA, sin HTTP.
│   ├── model                   #   Reservation (agregado), Itinerary, Segment,
│   │                           #   Passenger, User, value objects
│   ├── event                   #   Eventos de dominio (interfaz sellada)
│   └── exception               #   Errores de negocio
├── application                 # Orquestación. Depende sólo del dominio.
│   ├── port/in                 #   Casos de uso + comandos (lo que entra)
│   ├── port/out                #   Contratos hacia afuera (lo que necesita)
│   ├── service                 #   Implementación de los casos de uso
│   ├── outbox                  #   Modelo del outbox de eventos
│   └── exception               #   Errores de orquestación
└── infrastructure              # Detalles reemplazables.
    ├── adapter/in/rest         #   (vacío) acá van los controllers
    ├── adapter/in/scheduling   #   Disparador del despacho de notificaciones
    ├── adapter/out/persistence #   PostgreSQL: entidades JPA, mappers, adapter
    ├── adapter/out/airport     #   Maestro de aeropuertos (stub) + cache
    ├── adapter/out/notification#   Notificaciones (stub que loguea)
    ├── adapter/out/outbox      #   Outbox en memoria (stub)
    └── config                  #   Cableado y properties
```

El modelo de datos está en `src/main/resources/db/migration`, versionado con Flyway.
Las clases usan nombres en inglés (como el resto del código) y las tablas y columnas,
en español (como el modelo de datos): la traducción vive en los mappers y en
`ReservationStatusJpa`.

La regla de dependencia (todo apunta hacia adentro) no depende de la disciplina del
equipo: está verificada por `HexagonalArchitectureTest` con ArchUnit, así que se rompe
el build si alguien la viola.

## Decisiones

**Por qué hexagonal.** El sistema tiene que ser consumido por web, mobile y partners
externos. Con la lógica detrás de puertos de entrada, la API REST es un adaptador más:
mañana se agrega un consumidor de mensajería o un cliente gRPC sin tocar los casos de uso.

**Maestro de aeropuertos: decisión abierta.** Puede ser una tabla propia o un proveedor
externo. Justamente por eso los casos de uso dependen de `AirportCatalogPort`, no de una
implementación. Hoy responde un set fijo en memoria, decorado con `CachingAirportCatalog`
(TTL configurable): se consulta un aeropuerto por cada punta de cada tramo y cambia muy poco.

**Persistencia.** El esquema lo gobierna Flyway e Hibernate corre con
`ddl-auto: validate`, así que la aplicación no arranca si el mapeo y las tablas se
desincronizan. Las entidades JPA no salen de `adapter.out.persistence`: el dominio nunca
importa `jakarta.persistence` —lo verifica un test de ArchUnit— y los mappers son el
único punto donde los dos modelos se conocen.

Segmentos y pasajeros se comparten entre reservas, así que antes de insertar se reutiliza
la fila existente por su clave natural (`(origen, destino, aerolinea, fecha_vuelo)` y
`documento`). El itinerario, en cambio, se crea siempre: el modelo de datos no le define
clave natural porque la misma combinación de tramos puede venderse a distinto precio.

**Notificaciones asincrónicas.** Las operaciones no llaman al sistema de notificaciones:
registran un evento de dominio que se encola en un outbox (`EventOutboxPort`) dentro de la
misma transacción que la reserva. Un disparador aparte
(`OutboxDispatchScheduler` → `DispatchPendingNotificationsUseCase`) lo envía después. Si el
sistema de notificaciones está caído, las reservas siguen funcionando y los mensajes se
reintentan; agotados los intentos, el mensaje queda en `FAILED` (dead letter).
La entrega es *at-least-once*: el consumidor tiene que ser idempotente.

**Concurrencia.** Son preocupaciones reales desde el día uno, así que el esqueleto ya las
contempla:

| Preocupación | Cómo se resuelve |
|---|---|
| Dos usuarios modificando la misma reserva | Optimistic locking: `expectedVersion` en los comandos, `@Version` en la entidad → `ConcurrentUpdateException` (409 en REST) |
| Reservas duplicadas por reintentos | `idempotency_key` con `UNIQUE`: la búsqueda previa cubre el reintento secuencial y la constraint cierra la carrera |
| Dos reservas simultáneas del mismo vuelo | `INSERT ... ON CONFLICT DO NOTHING` al resolver segmentos y pasajeros, para no romper la transacción |
| Estado compartido entre hilos | El agregado es inmutable: cada operación devuelve una instancia nueva |
| Latencia y carga sobre el maestro de aeropuertos | Cache con TTL delante del puerto |
| Notificaciones lentas compitiendo con los pedidos | Despacho fuera del hilo del usuario, con pool propio |
| Muchos pedidos concurrentes de I/O | Threads virtuales (`spring.threads.virtual.enabled`) |
| Deploys sin cortar pedidos en curso | Graceful shutdown |

**Reloj inyectado.** Los casos de uso no llaman a `Instant.now()`: reciben un `Clock`. Así
las reglas temporales ("no se puede reservar un vuelo que ya partió") son verificables.

## Fuera de alcance

Cada punto tiene su lugar ya preparado:

| Pendiente | Dónde va | Qué hay que hacer |
|---|---|---|
| Endpoints REST | `infrastructure/adapter/in/rest` | Controllers contra los puertos de entrada, DTOs propios y un `@RestControllerAdvice` que mapee las excepciones (ver el `package-info.java`) |
| Outbox persistente | `infrastructure/adapter/out/outbox` | El outbox sigue en memoria porque el modelo de datos no tiene su tabla. Con una tabla `outbox_message` escrita en la misma transacción que la reserva, la notificación deja de perderse si se cae el proceso; el `pollPending` pasa a `SELECT ... FOR UPDATE SKIP LOCKED` |
| Seguridad | — | `spring-boot-starter-security` junto con el adaptador REST |
| Casos de uso de usuario | `application` | Hoy la reserva sólo referencia al usuario por id y la clave foránea garantiza que exista. El alta y la consulta de usuarios necesitan su propio puerto |
| Reintento de la carrera por idempotencia | `adapter/in/rest` | Cuando dos pedidos con la misma clave corren a la vez, el perdedor recibe `DuplicateReservationException`. Reintentar una vez desde el adaptador de entrada devuelve la reserva ganadora |

## Tests

207 tests. Los unitarios (`mvn test`) no necesitan infraestructura; los de integración
(`mvn verify`) levantan PostgreSQL con Testcontainers.

**Unitarios (180)**

- **Dominio** — reglas del agregado y de los value objects, con tiempo fijo.
- **Aplicación** — casos de uso con los puertos mockeados: qué se persiste, qué se
  notifica y, sobre todo, qué **no** se hace cuando una validación falla.
- **Mappers** — la traducción entre dominio y JPA en los dos sentidos.
- **Adaptadores** — cache del maestro de aeropuertos, outbox y notificaciones.
- **Arquitectura** — ArchUnit sobre las reglas de dependencia entre capas, incluida la de
  que el dominio no importe `jakarta.persistence`.

**Integración (27)**

- `ReservationPersistenceAdapterIT` — el adaptador contra PostgreSQL: reutilización de
  segmentos y pasajeros, orden de los tramos, `UNIQUE` de idempotencia, clave foránea de
  usuario, optimistic locking, fechas en UTC, y dos tests concurrentes (cuatro hilos
  modificando la misma reserva; cuatro reservando el mismo vuelo a la vez).
- `ReservationsApplicationIT` — levanta el contexto completo, valida que el mapeo coincida
  con el esquema de Flyway y corre el flujo crear → consultar → confirmar → modificar →
  cancelar, más la idempotencia y el despacho del outbox.
