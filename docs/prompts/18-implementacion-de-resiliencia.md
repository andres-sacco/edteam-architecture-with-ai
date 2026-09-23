# 18 — Implementación de la resiliencia: circuit breaker, fallback y reintentos acotados

**Etapa:** Implementación

**Salida esperada:** Circuit breakers, fallbacks y políticas de reintentos en el código, con métricas y tests

---

## Rol

Actúa como desarrollador backend Java/Spring Boot con experiencia en sistemas distribuidos, implementando la estrategia de resiliencia ya diseñada y corrigiendo los hallazgos de la auditoría previa.

## Contexto

Existe un proyecto Maven con **Java 21 + Spring Boot 3.5** y arquitectura hexagonal (un solo módulo, separación por paquetes):

```
com.edteam.reservations
├── domain          # model, event, access, exception — sin Spring, sin JPA, sin HTTP
├── application     # port/in, port/out, service, query, outbox, audit, notification, exception
└── infrastructure  # adapter/in/rest, adapter/in/messaging, adapter/in/scheduling, adapter/in/ops,
                    # adapter/out/persistence, adapter/out/airport, adapter/out/messaging,
                    # adapter/out/outbox, adapter/out/inbox, adapter/out/audit,
                    # cache, config, jdbc, logging, security
```

La API REST (`/v1/reservations`) está autenticada con JWT, usa locking optimista con `ETag`/`If-Match`, persiste en PostgreSQL con Flyway y cachea en Redis con fallback en memoria. El catálogo externo de ciudades se consume por REST con timeouts propios y *stale-while-error*. Los eventos salen por un outbox durable hacia RabbitMQ, con consumidor idempotente y dos dead letters. El `compose.yaml` levanta PostgreSQL 17, Redis 7, RabbitMQ 4 y el `api-catalog`.

**Lo que ya existe y este paso lleva a su forma definitiva:**

- `RetryingCityCatalogClient`: decorador con 3 intentos, backoff exponencial de 100 ms a 500 ms con jitter, que reintenta sólo `AirportCatalogUnavailableException`. El `Sleeper` es inyectable.
- `CachingAirportCatalog`: TTL positivo/negativo y *stale-while-error* de 2 h; si no hay nada guardado, la excepción sube.
- `RestCityCatalogClient`: clasificación explícita de la respuesta en transitorio / permanente / respuesta de negocio.
- `infrastructure/cache`: degradación al origen ante error de Redis, `InMemoryCacheStore` como fallback, `MeteredCacheStore` publicando `reservations.cache.*`.
- `OutboxDispatcherService` + `JdbcEventOutbox`: reintentos con backoff, `retry-ceiling`, `claim-lease` y dead letter; métricas `reservations.outbox.*`.
- `ReservationEventListener`: reintentos por TTL de la cola de espera y DLQ; métricas `reservations.messaging.*`.
- `AdapterConfiguration`, `AirportCatalogProperties`, `CacheProperties`, `MessagingProperties`, `OutboxProperties`: el cableado y los valores configurables.
- **No hay ningún circuit breaker.** `resilience4j` no está en el `pom.xml`.

Las entradas de este prompt son el **diseño** de [16](16-diseno-de-resiliencia.md) y la **tabla de hallazgos priorizada** de [17](17-auditoria-de-resiliencia.md). Ese material dice *qué* hay que construir y *qué* está mal hoy; este paso lo lleva al código.

## Tarea

1. **Circuit breaker en las dependencias que el diseño identificó**, implementado como decorador detrás del puerto correspondiente, en `infrastructure/adapter/out`. Con los umbrales de la tabla del diseño: ventana, porcentaje de fallo, llamadas mínimas, tiempo abierto, llamadas en semiabierto. Un 404 del catálogo no cuenta como fallo del circuito.
2. **Clasificación de fallos compartida**: un único lugar que decida si una excepción es transitoria (cuenta para el circuito y se reintenta) o permanente (no cuenta y no se reintenta), reusado por el circuito y por el retry en lugar de duplicado.
3. **Orden de los decoradores** tal como lo fijó el diseño —cache, circuit breaker, retry, cliente HTTP—, cableado explícitamente en `config` y no por orden accidental de beans.
4. **Fallback por dependencia**: la respuesta alternativa cuando el circuito está abierto o se agotaron los intentos. Tiene que quedar registrado que la respuesta salió degradada —métrica y log, no sólo el valor— y el caso donde **no** hay fallback posible tiene que fallar de forma explícita, no devolver un valor inventado.
5. **Reintentos corregidos** según los hallazgos: backoff exponencial con jitter y techo en todos lados, ningún reintento sobre operaciones no idempotentes sin clave que las proteja, y sin multiplicación por anidamiento entre capas.
6. **Presupuesto de tiempo del pedido**: implementar el techo de latencia del pedido completo que el diseño definió, de modo que un itinerario con muchas ciudades no acumule el peor caso de cada una en serie.
7. **Observabilidad de la resiliencia**: exponer por Actuator/Micrometer el estado de cada circuito, sus transiciones, los reintentos por dependencia y las respuestas servidas por fallback. Sin esto no hay forma de enterarse de que el sistema está degradado.
8. **Configuración por propiedades**: cada umbral, cada backoff y cada techo se configura desde `application.yml` con su valor por defecto, documentado con el motivo del número, igual que el resto del archivo.
9. **Tests**, uno por hallazgo de la auditoría: que el circuito se abra al alcanzar el umbral y no antes; que vuelva a cerrarse solo cuando la dependencia se recupera; que el fallback responda con un dato razonable y se registre como degradado; que una escritura nunca se reintente sin clave de idempotencia; que el peor caso de latencia del `POST` respete el presupuesto; que un 404 del catálogo no abra el circuito.

## Restricciones

- **Respetar el orden de prioridad de la tabla de hallazgos**: primero lo que más impacta al usuario, no lo más fácil de implementar.
- **Free tier**: nada que exija un plan pago ni un servicio externo. Si se agrega una librería, tiene que ser de uso libre y quedar declarada en el `pom.xml` con versión fija.
- **La resiliencia es infraestructura**: `domain` y `application` no importan la librería de resiliencia ni sus anotaciones. Los puertos no cambian de firma por el circuito que se les pone delante.
- `HexagonalArchitectureTest` (ArchUnit) tiene que seguir en verde; si hace falta, agregar la regla que impida que la librería de resiliencia se filtre hacia adentro.
- **La aplicación tiene que seguir arrancando y los tests corriendo sin las dependencias levantadas**, igual que hoy arranca sin Redis, sin broker y sin catálogo.
- **Un circuito abierto no puede ser permanente**: la recuperación es automática, sin reinicio ni intervención manual.
- **No se reintentan operaciones no idempotentes.** Si alguna lo necesita, va con su clave de idempotencia y hay que decir cuál es.
- **El fallback no miente en silencio**: toda respuesta degradada deja rastro en una métrica y en un log, y el log pasa por el enmascarado de PII existente.
- Coherencia con lo ya decidido: timeouts declarados por proveedor, degradación en lugar de propagación del error, health indicators de dependencias opcionales apagados.
- Los tests existentes tienen que seguir pasando; si alguno deja de tener sentido bajo el nuevo esquema, adaptarlo y justificar el cambio.

## Formato de salida

1. **Tabla de trazabilidad**: hallazgo de la auditoría | mitigación implementada | archivos afectados | test que lo verifica.
2. **Lista de archivos a crear o modificar**, agrupados por paquete, con el contenido completo de cada uno (decoradores de circuito, clasificador de fallos, configuración, propiedades, `pom.xml`, `application.yml`, métricas, tests).
3. **Tabla de la resiliencia final**: dependencia | timeout | umbral del circuito | política de reintentos | fallback | métrica que lo expone.
4. **Presupuesto de latencia final** por camino, desglosado, comparado con el de la auditoría.
5. **Hallazgos que no se remedian en este paso**, con el motivo y qué haría falta para revisarlos.
6. **Comandos de verificación**: cómo levantar el entorno, cómo correr el build y los tests, y cómo reproducir a mano la apertura del circuito bajando el `api-catalog` de `compose.yaml` y su cierre al volver a levantarlo.
