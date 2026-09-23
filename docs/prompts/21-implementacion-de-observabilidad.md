# 21 — Implementación de la observabilidad: logging estructurado, métricas y alertas

**Etapa:** Implementación

**Salida esperada:** Logs en JSON con esquema, métricas del camino del pedido, trazabilidad completa, alertas y tests

---

## Rol

Actúa como desarrollador backend Java/Spring Boot con experiencia en operación de sistemas distribuidos, implementando la estrategia de observabilidad ya diseñada y corrigiendo los hallazgos de la auditoría previa.

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

La API REST (`/v1/reservations`) está autenticada con JWT, usa locking optimista con `ETag`/`If-Match`, persiste en PostgreSQL con Flyway y cachea en Redis con fallback en memoria. El catálogo externo de ciudades se consume por REST con timeouts propios, reintentos sobre el `GET` y *stale-while-error*. Los eventos salen por un outbox durable hacia RabbitMQ, con consumidor idempotente y dos dead letters. El `compose.yaml` levanta PostgreSQL 17, Redis 7, RabbitMQ 4 y el `api-catalog`.

**Lo que ya existe y este paso lleva a su forma definitiva:**

- `infrastructure/security/CorrelationIdFilter`: `X-Correlation-Id` por pedido, validado, en el MDC (`correlationId`, `clientIp`) y en la respuesta.
- `OutboxDispatcherService` y `ReservationEventListener`: reponen el `correlationId` en el MDC desde el envelope, así que la traza cruza el broker.
- `infrastructure/logging/PiiMasker` y `LogSanitizer`: enmascarado de PII y saneado de datos externos antes de loguear.
- `infrastructure/adapter/out/audit/JdbcAuditTrailAdapter`: rastro de auditoría en base, separado de los logs.
- Métricas Micrometer: `reservations.cache.*`, `reservations.outbox.*`, `reservations.messaging.*`, `reservations.security.pii`.
- Actuator en `MANAGEMENT_PORT: 9090`, no publicado hacia afuera, con `health,info,metrics,outbox,messaging-dlq`; health de `redis` y `rabbit` apagados a propósito.
- **No hay `logback-spring.xml`**: el formato es el texto plano por defecto. **No hay trazas** ni registry de Prometheus. **No hay alertas.** El `correlationId` no viaja a las llamadas salientes al catálogo.

Las entradas de este prompt son el **diseño** de [19](19-diseno-de-observabilidad.md) y la **tabla de hallazgos priorizada** de [20](20-auditoria-de-observabilidad.md). Ese material dice *qué* hay que construir y *qué* está mal hoy; este paso lo lleva al código.

## Tarea

1. **Logging estructurado en JSON**: agregar el `logback-spring.xml` que emite los registros con el esquema del diseño —un campo por dato, el MDC completo en el registro— con un perfil de desarrollo legible en consola y el JSON como formato de cualquier otro entorno. La dependencia que lo habilite queda declarada en el `pom.xml` con versión fija.
2. **Migrar las llamadas a `log.*` al esquema**: sacar los datos de adentro del mensaje interpolado y ponerlos en campos propios, empezando por los adaptadores del camino del pedido (`RestCityCatalogClient`, `RetryingCityCatalogClient`, `CachingAirportCatalog`) y los de mensajería. Aplicar la tabla de niveles del diseño: corregir cada `ERROR` que es esperado y cada pérdida del usuario que hoy es `WARN` o no se registra.
3. **Cerrar los saltos de trazabilidad** que encontró la auditoría: propagar el `correlationId` en el header de las llamadas salientes al `api-catalog`, dárselo a las tareas programadas —el relay del outbox y la purga, que no nacen de un pedido HTTP— y verificar que sobreviva en todo thread que el pedido genere.
4. **Registros que faltan**: agregar los eventos críticos que hoy no dejan huella —fallo de autenticación, rechazo por rate limiting, conflicto de versión del locking optimista, respuesta servida por *stale-while-error*, mensaje que llega a dead letter— con su nivel y sus campos.
5. **Métricas del camino del pedido**: completar el catálogo del diseño con lo que falta —latencia y tasa de error por operación de negocio, latencia y resultado de las llamadas al catálogo, respuestas servidas degradadas— respetando la cardinalidad acotada de las etiquetas.
6. **Exposición y recolección**: exponer las métricas en el formato que el diseño eligió, agregar al `compose.yaml` lo necesario para verlas en local, y dejarlo apagable por configuración como el resto de las dependencias opcionales.
7. **Trazas**, si el diseño las incluyó: instrumentar con el alcance definido y propagar el contexto por HTTP y por el broker, relacionándolo con el `correlationId` existente en lugar de duplicarlo. Si el diseño las pospuso, dejar escrito el punto de extensión.
8. **Alertas**: dejar en el repositorio la definición de las alertas de la tabla del diseño, con su métrica, umbral, ventana y la acción esperada de quien la recibe, versionadas junto al código.
9. **Tests**, uno por hallazgo de la auditoría: que un registro emitido cumpla el esquema de campos; que ninguna contraseña, token, clave ni PII aparezca en la salida de la suite completa —una verificación automatizable, no una inspección—; que un pedido con un `X-Correlation-Id` conocido deje ese mismo valor en todos sus registros, en el envelope del evento y en los del consumidor; que los eventos críticos agregados queden registrados con el nivel correcto.

## Restricciones

- **Respetar el orden de prioridad de la tabla de hallazgos**: primero los datos sensibles y lo que impide diagnosticar, después lo que sólo incomoda.
- **Formato JSON** con un campo por dato. Ningún dato importante vuelve a viajar interpolado en el texto del mensaje.
- **Sin datos sensibles en los logs**: ni contraseñas, ni tokens, ni la API key del catálogo, ni la clave de cifrado, ni documento, nombre o email en claro. Todo dato de origen externo sigue pasando por `LogSanitizer`, y los emails por `PiiMasker`.
- **La observabilidad es infraestructura**: `domain` no loguea; `application` usa SLF4J sin conocer el formato ni el backend; el `logback-spring.xml` y el registry viven en `infrastructure` y en los recursos. `HexagonalArchitectureTest` (ArchUnit) tiene que seguir en verde.
- **Free tier**: lo que se agregue al `compose.yaml` corre local y gratis. Nada que exija una cuenta paga para levantar el proyecto.
- **La aplicación tiene que seguir arrancando y los tests corriendo sin el backend de observabilidad disponible**, igual que hoy arranca sin Redis, sin broker y sin catálogo.
- **Cardinalidad acotada**: ninguna etiqueta de métrica toma un número no acotado de valores. Nada de ids de reserva ni de usuario como etiqueta.
- **Actuator sigue en su puerto propio, no publicado hacia afuera**, y los endpoints `outbox` y `messaging-dlq` se conservan tal como están.
- **El volumen es parte del entregable**: decir cuántas líneas por pedido genera el esquema implementado y, si crece, qué se bajó de nivel para compensarlo.
- Los tests existentes tienen que seguir pasando; si alguno deja de tener sentido bajo el nuevo esquema, adaptarlo y justificar el cambio.

## Formato de salida

1. **Tabla de trazabilidad**: hallazgo de la auditoría | mitigación implementada | archivos afectados | test que lo verifica.
2. **Lista de archivos a crear o modificar**, agrupados por paquete, con el contenido completo de cada uno (`logback-spring.xml`, clases de `infrastructure/logging`, filtros, adaptadores, configuración, `pom.xml`, `application.yml`, `compose.yaml`, definición de alertas, tests).
3. **Esquema de log final** con un registro de ejemplo por tipo de evento, tal como sale del código implementado.
4. **Tabla de métricas final**: nombre | tipo | etiquetas | qué pregunta responde | alerta asociada.
5. **Mapa de trazabilidad final**: cada salto del camino de un pedido, con cómo viaja el `correlationId` en cada uno.
6. **Hallazgos que no se remedian en este paso**, con el motivo y qué haría falta para revisarlos.
7. **Comandos de verificación**: cómo levantar el entorno, cómo correr el build y los tests, cómo emitir un pedido con un `X-Correlation-Id` conocido y seguirlo de punta a punta, y cómo verificar que no haya PII en la salida.
