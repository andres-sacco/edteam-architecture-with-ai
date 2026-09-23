# 19 — Diseño de la observabilidad: logging estructurado, métricas y trazas

**Etapa:** Decisión arquitectónica

**Salida esperada:** Esquema de campos del log, tabla de niveles de severidad, catálogo de métricas y trazas

---

## Rol

Actúa como ingeniero de observabilidad diseñando la estrategia de logging, métricas y trazas del sistema.

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

Observabilidad es poder entender qué pasa adentro del sistema mirando desde afuera, y se apoya en tres patas: **logs** (registros de eventos puntuales, para investigar qué pasó), **métricas** (números agregados en el tiempo, para ver cómo se comporta) y **trazas** (el recorrido completo de un pedido a través de todos los componentes).

**Los servicios y eventos críticos del sistema:**

- **API REST** (`/v1/reservations`): alta, lectura, listado, modificación, confirmación y cancelación. Autenticada con JWT, con locking optimista por `ETag`/`If-Match`, rate limiting por instancia y CORS por allowlist.
- **PostgreSQL** (JPA + Flyway): reservas, usuarios, auditoría, outbox e inbox de deduplicación.
- **Redis**: caché distribuida, opcional, con fallback en memoria.
- **Catálogo externo de ciudades** (`api-catalog`, REST): se consulta una vez por cada código del itinerario en cada `POST` y `PUT`.
- **RabbitMQ**: publicación de los cuatro hechos de negocio (`reservation.created`, `confirmed`, `modified`, `cancelled`) desde el outbox, consumidor idempotente, cola de espera y dos dead letters.
- **Tareas programadas**: el relay del outbox cada 5 s y la purga diaria de despachados.

**Lo que ya existe en materia de observabilidad:**

- `infrastructure/security/CorrelationIdFilter`: da a cada pedido un `X-Correlation-Id`, lo valida contra un formato estricto antes de aceptar el del cliente, lo pone en el MDC (`correlationId`, más `clientIp`) y lo devuelve en la respuesta. Lo limpia en el `finally` para que no se filtre al próximo pedido del thread.
- El correlation id **viaja por la mensajería**: `EventEnvelope` lo lleva, `OutboxDispatcherService` y `ReservationEventListener` lo reponen en el MDC al despachar y al consumir.
- `infrastructure/logging/PiiMasker`: enmascara emails (`an***@example.com`) y omite por completo documentos y nombres de pasajero.
- `infrastructure/logging/LogSanitizer`: sanea saltos de línea y caracteres de control de todo dato de origen externo antes de loguearlo, y lo trunca a 512 caracteres. Su propio javadoc dice que **no reemplaza la solución de fondo, que es loguear en JSON estructurado**.
- `infrastructure/adapter/out/audit/JdbcAuditTrailAdapter`: rastro de auditoría en base, separado de los logs.
- **Métricas ya publicadas** por Micrometer: `reservations.cache.{gets,puts,evictions,errors,size}`, `reservations.outbox.{enqueued,claimed,dispatched,failed,deferred,dead,pending,lag,dispatched.retained}`, `reservations.messaging.{consumed,dlq.depth,enabled}`, `reservations.security.pii`.
- **Actuator** en un puerto propio (`MANAGEMENT_PORT: 9090`, no publicado hacia afuera), exponiendo `health,info,metrics,outbox,messaging-dlq`. Los dos últimos son endpoints propios (`OutboxEndpoint`, `DeadLetterEndpoint`) para inspeccionar y reprocesar dead letters.
- Health indicators de `redis` y `rabbit` **apagados a propósito**: su caída degrada y no debe sacar la instancia de rotación.

**Lo que no existe hoy, y es el punto de partida de este diseño:**

- **Los logs no son estructurados.** No hay `logback-spring.xml`: sale el formato de texto por defecto de Spring Boot, con el mensaje interpolado y el MDC fuera del renglón. El único control es `logging.level.com.edteam.reservations: INFO`.
- **No hay convención de niveles de severidad** ni de qué campos lleva cada log. Cada clase escribe como le parece.
- **No hay trazas**: ni `micrometer-tracing` ni OpenTelemetry en el `pom.xml`. El correlation id es la única costura entre componentes, y **no se propaga a las llamadas salientes** al catálogo.
- **No hay métricas del camino del pedido**: no se mide la latencia de las llamadas al catálogo, ni la tasa de error por endpoint de negocio, ni cuántas respuestas salieron degradadas.
- **No hay backend de observabilidad** en el `compose.yaml` ni registry de Prometheus: las métricas sólo se leen por `/actuator/metrics` una por una.
- **No hay alertas**: ninguna definición de qué valor de qué métrica amerita despertar a alguien.

## Tarea

Diseñar la estrategia de observabilidad del sistema, cubriendo las tres patas.

1. **Esquema de logging estructurado**: definir los campos de un registro en JSON —los obligatorios en todos (timestamp, nivel, logger, mensaje, `correlationId`, servicio, entorno, thread) y los contextuales por tipo de evento (pedido HTTP, llamada saliente, evento de dominio, tarea programada)—. Decir de dónde sale cada campo y cuáles vienen del MDC.
2. **Tabla de niveles de severidad**: qué significa exactamente `ERROR`, `WARN`, `INFO`, `DEBUG` y `TRACE` en este sistema, con un ejemplo de cada uno tomado del código real y el criterio que los separa. Una dependencia que degrada y se recupera no es lo mismo que un pedido que el usuario perdió.
3. **Qué se loguea y qué no**: los eventos que sí o sí tienen que dejar registro (alta, confirmación, cancelación, fallo de autenticación, apertura de circuito, mensaje a dead letter) y los que son ruido. Definir la política de datos sensibles: qué campos nunca se escriben, cuáles se enmascaran y con qué forma.
4. **Catálogo de métricas**: partiendo de las que ya existen, definir qué falta para responder las preguntas que importan —cuántas reservas por minuto, qué porcentaje falla, cuánto tarda el `POST`, cuánto tarda el catálogo, cuántas respuestas salieron degradadas, cuánto lag tiene el outbox—. Para cada métrica: nombre, tipo (contador, gauge, histograma), etiquetas y qué decisión permite tomar. Cuidar la cardinalidad de las etiquetas: un id de reserva como etiqueta es una métrica por reserva.
5. **Trazas**: decidir si el sistema necesita trazas distribuidas y con qué alcance. Si las necesita, definir qué es un span, dónde empiezan y terminan, cómo se propaga el contexto hacia el catálogo por HTTP y hacia el consumidor por el broker, y qué relación tiene con el `correlationId` que ya existe. Si no las necesita todavía, decirlo explícitamente y decir qué tendría que pasar para que sí.
6. **Alertas**: definir las pocas que valen la pena. Para cada una: métrica, umbral, ventana, severidad, qué está roto desde la perspectiva del usuario y qué hace quien la recibe. Distinguir las que despiertan a alguien de las que sólo abren un ticket.
7. **Dónde se ven**: definir cómo se recolectan logs y métricas y qué hace falta agregar al `compose.yaml` para poder verlos en local.

## Restricciones

- **Formato JSON** para los logs, con un campo por dato. El mensaje interpolado en texto libre deja de ser la forma de transportar información.
- **Sin datos sensibles en los logs**: ni contraseñas, ni tokens, ni la clave de cifrado, ni documento, nombre o email en claro del pasajero. El enmascarado existente (`PiiMasker`) es el piso, no el techo.
- **Todo registro lleva el identificador de correlación**, incluidos los de las tareas programadas y los del consumidor de mensajes, que no nacen de un pedido HTTP.
- **La observabilidad es infraestructura**: `domain` no loguea. `application` puede loguear decisiones de negocio a través de SLF4J, pero no conoce el formato ni el backend. `HexagonalArchitectureTest` tiene que seguir en verde.
- **Free tier**: lo que se agregue al `compose.yaml` tiene que correr local y gratis. Nada que exija una cuenta paga para levantar el proyecto.
- **Cardinalidad acotada**: ninguna etiqueta de métrica puede tomar un número no acotado de valores.
- **El costo del log es parte del diseño**: un `INFO` por cada consulta al catálogo en el camino del `POST` son 8 líneas por reserva. Decir qué volumen genera el esquema propuesto y por qué es aceptable.
- Coherencia con lo ya decidido: Actuator en un puerto no publicado, health indicators de dependencias opcionales apagados, y el rastro de auditoría en base como pieza separada de los logs.

## Formato de salida

1. **Esquema de campos del log**: campo | tipo | obligatorio/contextual | origen (MDC, excepción, parámetro) | ejemplo. Más un registro de ejemplo en JSON por cada tipo de evento.
2. **Tabla de niveles de severidad**: nivel | qué significa acá | ejemplo real del código | quién lo mira y cuándo.
3. **Política de datos sensibles**: campo | tratamiento (se omite / se enmascara / se escribe) | por qué.
4. **Catálogo de métricas**: nombre | tipo | etiquetas | qué pregunta responde | existe hoy / hay que agregarla.
5. **Decisión sobre trazas**, con el alcance y la propagación, o la justificación de por qué todavía no.
6. **Tabla de alertas**: alerta | métrica y umbral | ventana | severidad | qué está roto para el usuario | acción esperada.
7. **Qué cambia en el código actual y en el `compose.yaml`**: piezas que se conservan, se reemplazan o se agregan, por paquete.
