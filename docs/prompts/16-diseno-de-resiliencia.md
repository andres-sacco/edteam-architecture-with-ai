# 16 — Diseño de la resiliencia: circuit breaker, fallback y política de reintentos

**Etapa:** Decisión arquitectónica

**Salida esperada:** Tabla por dependencia con el umbral del circuit breaker, la política de reintentos y el fallback

---

## Rol

Actúa como ingeniero de confiabilidad (SRE) diseñando la resiliencia de las llamadas a dependencias externas.

## Contexto

El sistema de reservas de vuelos es un proyecto Maven con **Java 21 + Spring Boot 3.5** y arquitectura hexagonal (un solo módulo, separación por paquetes):

```
com.edteam.reservations
├── domain          # model, event, access, exception — sin Spring, sin JPA, sin HTTP
├── application     # port/in, port/out, service, query, outbox, audit, notification, exception
└── infrastructure  # adapter/in/rest, adapter/in/messaging, adapter/in/scheduling, adapter/in/ops,
                    # adapter/out/persistence, adapter/out/airport, adapter/out/messaging,
                    # adapter/out/outbox, adapter/out/inbox, adapter/out/audit,
                    # cache, config, jdbc, logging, security
```

La API REST (`/v1/reservations`) está autenticada con JWT, usa locking optimista con `ETag`/`If-Match` y la consumen varios frontends con muchos usuarios concurrentes. El `compose.yaml` levanta PostgreSQL 17, Redis 7, RabbitMQ 4 y el `api-catalog` con su MySQL.

**Las dependencias que este sistema llama hacia afuera, y lo que cada una tiene hoy:**

| Dependencia | Dónde vive | Qué protección tiene hoy |
|---|---|---|
| Catálogo de ciudades (`api-catalog`, REST) | `infrastructure/adapter/out/airport/catalog` | `connect-timeout: 500ms`, `read-timeout: 2s`, reintentos **sólo** sobre el `GET /city/{code}` (`RetryingCityCatalogClient`: 3 intentos, backoff exponencial de 100 ms a 500 ms con jitter) y *stale-while-error* de 2 h en `CachingAirportCatalog` |
| PostgreSQL (JPA + Flyway) | `infrastructure/adapter/out/persistence` | Pool Hikari de 20 conexiones, `connection-timeout: 3000` |
| Redis (caché distribuida) | `infrastructure/cache` | `timeout: 200ms`, `connect-timeout: 200ms`, fallback a `InMemoryCacheStore` si está apagado, degradación al origen ante error |
| RabbitMQ (publicación de eventos) | `infrastructure/adapter/out/messaging` | Outbox durable en PostgreSQL, `confirm-timeout: 5s`, reintentos con backoff de 5 s a 5 m, `max-attempts: 10`, `retry-ceiling: 6h`, dead letter propia |
| Sistema de notificaciones (consumidor) | fuera de este servicio | Reintentos por TTL de la cola de espera (`retry-delay: 30s`, `max-retry-rounds: 5`) y DLQ |

**Lo que ya está decidido y no se discute acá:**

- Los **timeouts se declaran por proveedor**, no globales: el que tolera el catálogo no tiene por qué ser el del próximo servicio que se integre.
- **Sólo se reintentan operaciones idempotentes.** El `GET` del catálogo sí; el alta de una reserva no —es idempotente sólo gracias a la `Idempotency-Key`— y el `PUT` depende de una versión que un reintento ciego pisaría.
- Una **dependencia caída degrada en lugar de tumbar el servicio**: los health indicators de Redis y RabbitMQ están apagados a propósito para no sacar la instancia de rotación por un componente del que no depende para responder.
- El arranque no depende de nadie: la aplicación levanta sin Redis, sin broker y sin catálogo.

**Lo que no existe hoy, y es el punto de partida de este diseño:**

- **No hay ningún circuit breaker.** Ni en el catálogo, ni en el broker, ni en la base. `resilience4j` no está en el `pom.xml`. Cuando el catálogo se cae, cada pedido nuevo vuelve a pagar el camino completo —hasta 6,5 s por ciudad, y un ida y vuelta con escala consulta 8 ciudades en serie— antes de caer al *stale-while-error*.
- **No hay presupuesto de tiempo para el pedido completo**: cada ciudad tiene su propio techo, el itinerario entero no. Está anotado como deuda en el javadoc de `RetryingCityCatalogClient`.
- **No hay aislamiento de recursos** (bulkhead): las llamadas al catálogo, las consultas a la base y el despacho del outbox comparten el mismo proceso y el mismo pool de conexiones.

Los tres modos de falla que este diseño tiene que cubrir son:

1. **Errores aleatorios**: un porcentaje chico de pedidos falla sin patrón claro —un timeout ocasional de red—.
2. **Tiempos de espera largos**: la dependencia responde, pero tan lento que satura threads y conexiones mientras se la espera.
3. **Caídas totales**: el servicio deja de responder y cada llamada nueva también queda colgada.

## Tarea

Diseñar la resiliencia de cada dependencia crítica combinando los tres patrones: **Circuit Breaker**, **Retries** y **Fallback Method**.

1. **Inventariar las dependencias** y, para cada una, decir a cuál de los tres modos de falla es vulnerable y cuál es el daño concreto sobre el usuario cuando ocurre.
2. **Circuit Breaker**: decidir para cuáles dependencias corresponde y para cuáles no, y justificarlo. Para las que sí, definir la ventana de medición, el umbral de apertura (porcentaje de fallos y/o de llamadas lentas sobre cuántas llamadas mínimas), cuánto tiempo queda abierto, cuántas llamadas de prueba admite el estado semiabierto y qué excepciones cuentan como fallo. Un 404 del catálogo —"esa ciudad no existe"— **no** es un fallo del circuito.
3. **Retries**: para cada dependencia, decir si se reintenta, sobre qué operaciones exactamente, cuántos intentos, con qué backoff y con qué jitter. Justificar qué clase de fallo se reintenta (transitorio) y cuál no (permanente), y cómo se distinguen en el código.
4. **Fallback**: definir la respuesta alternativa de cada dependencia cuando el circuito está abierto o se agotaron los intentos. Decir explícitamente qué ve el usuario, qué grado de desactualización puede tener el dato y en qué casos **no** hay fallback posible y el pedido tiene que fallar.
5. **Cómo se combinan los tres**: fijar el orden de los decoradores —cache, circuit breaker, retry, cliente HTTP— y justificar por qué ése. Un reintento por dentro del circuito y uno por fuera no cuentan lo mismo.
6. **Presupuesto de tiempo del pedido**: definir el techo de latencia de un `POST /v1/reservations` en el peor caso y verificar que la combinación de timeouts, reintentos y backoff lo respeta. Si no lo respeta, decir qué se recorta.
7. **Interacción con lo ya construido**: qué pasa con el `stale-while-error` del catálogo, con el fallback en memoria de la caché y con el outbox cuando se agrega el circuito. Qué se conserva, qué se reemplaza y qué queda redundante.

## Restricciones

- **Definir los tres números de cada dependencia**: umbral de apertura del circuito, backoff de los reintentos y comportamiento del fallback. Un diseño sin valores concretos no es un diseño.
- **Cada valor se justifica con el comportamiento observado de la dependencia**, no con el default de la librería. Decir de dónde sale el número.
- **La resiliencia es infraestructura**: `domain` y `application` no pueden importar anotaciones ni clases de la librería de resiliencia. `HexagonalArchitectureTest` (ArchUnit) tiene que seguir en verde.
- **Free tier**: nada que exija un plan pago ni un servicio externo para levantar el proyecto localmente.
- **No se reintentan operaciones no idempotentes.** Si el diseño propone reintentar una escritura, tiene que decir con qué clave de idempotencia se protege.
- **El fallback no puede mentir en silencio**: si devuelve un dato viejo, hay que decir cómo se entera el sistema (métrica, log, header) de que está degradado.
- **Un circuito abierto no puede ser permanente**: tiene que haber un camino de recuperación automática, sin intervención manual.
- Coherencia con lo ya decidido: timeouts por proveedor, degradación en lugar de propagación del error, y arranque sin dependencias levantadas.

## Formato de salida

1. **Tabla por dependencia**: dependencia | modo de falla al que es vulnerable | umbral del circuit breaker (ventana, % de fallo, llamadas mínimas, tiempo abierto, llamadas en semiabierto) | política de reintentos (operaciones, intentos, backoff, jitter) | fallback | qué ve el usuario cuando actúa.
2. **Tabla de clasificación de fallos**: excepción o código de respuesta | ¿cuenta para el circuito? | ¿se reintenta? | por qué.
3. **Orden de los decoradores** por dependencia, con el diagrama de cómo se envuelven y la justificación del orden.
4. **Presupuesto de latencia** del `POST` y del `PUT` en el peor caso, desglosado por llamada.
5. **Qué cambia en el código actual**: piezas que se conservan, se reemplazan o se agregan, por paquete.
