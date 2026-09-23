# 20 — Auditoría de la observabilidad: logs sin estructura, datos sensibles y trazas rotas

**Etapa:** Revisión

**Salida esperada:** Tabla de hallazgos con riesgo, evidencia, forma de detectarlo y mitigación

---

## Rol

Actúa como revisor de observabilidad, con foco en las fallas que sólo se descubren el día del incidente, cuando el log que hacía falta no está o dice lo que no debería.

## Contexto

El sistema de reservas de vuelos (Java 21 + Spring Boot 3.5, arquitectura hexagonal, PostgreSQL + Redis + RabbitMQ) tiene una estrategia de observabilidad recién diseñada y una implementación parcial ya en el repositorio.

**Lo que hay en el código hoy:**

- `infrastructure/security/CorrelationIdFilter`: `X-Correlation-Id` por pedido, validado contra `[A-Za-z0-9_-]{8,64}` antes de aceptar el del cliente, puesto en el MDC (`correlationId`, `clientIp`) y devuelto en la respuesta, con limpieza en el `finally`.
- `application/service/OutboxDispatcherService` e `infrastructure/adapter/in/messaging/ReservationEventListener`: reponen el `correlationId` en el MDC a partir del envelope, y lo quitan al terminar.
- `infrastructure/logging/PiiMasker`: enmascara emails, omite documentos y nombres.
- `infrastructure/logging/LogSanitizer`: sanea saltos de línea y caracteres de control de datos externos y trunca a 512 caracteres, para que un proveedor no pueda fabricar registros falsos.
- Métricas Micrometer: `reservations.cache.*`, `reservations.outbox.*`, `reservations.messaging.*`, `reservations.security.pii`.
- Actuator en el puerto `9090`, no publicado hacia afuera, con `health,info,metrics,outbox,messaging-dlq`. `OutboxEndpoint` y `DeadLetterEndpoint` permiten inspeccionar y reprocesar dead letters.
- `logging.level.com.edteam.reservations: INFO` es toda la configuración de logging que existe: **no hay `logback-spring.xml`**, así que los registros salen en el texto plano por defecto de Spring Boot.
- Llamadas `log.warn(...)` y `log.trace(...)` repartidas por los adaptadores (`RetryingCityCatalogClient`, `CachingAirportCatalog`, `RestCityCatalogClient`, los de mensajería), con el dato interpolado dentro del mensaje.
- **No hay trazas**: ni `micrometer-tracing` ni OpenTelemetry en el `pom.xml`. El `correlationId` **no se propaga** en el header de las llamadas salientes al `api-catalog`.
- **No hay registry de Prometheus ni backend de observabilidad** en el `compose.yaml`, y **no hay ninguna alerta definida**.

**Lo que aporta el paso anterior:** el diseño de [19 — Diseño de la observabilidad](19-diseno-de-observabilidad.md): esquema de campos del log, tabla de niveles de severidad, catálogo de métricas, decisión sobre trazas y tabla de alertas.

La entrada de este prompt son **las dos cosas**: el diseño y el código que hoy lo implementa a medias.

## Tarea

Auditar diseño e implementación buscando las fallas que pasan inadvertidas en una revisión normal. Como mínimo:

1. **Logs sin estructura**: recorrer las llamadas a `log.*` del repositorio y marcar dónde el dato importante viaja interpolado en texto libre en lugar de ir en un campo propio. Un registro del que no se puede filtrar por código de ciudad, por id de reserva o por dependencia no sirve para investigar.
2. **Datos sensibles en los logs**: buscar explícitamente contraseñas, tokens, claves, headers de autorización, la API key del catálogo, emails, documentos y nombres de pasajero escritos en claro. Incluir lo que se loguea indirectamente: el cuerpo de un error del proveedor, una excepción con el mensaje completo, una URL con parámetros.
3. **Niveles de severidad inconsistentes**: encontrar los casos donde algo esperado se registra como `ERROR` y los casos donde algo que el usuario perdió se registra como `WARN` o no se registra. Un `ERROR` que aparece cien veces por hora en operación normal deja de significar algo.
4. **Trazabilidad rota de punta a punta**: verificar si un mismo pedido se puede seguir desde el `POST` del frontend hasta el consumidor del evento. Marcar cada salto donde el `correlationId` se pierde: la llamada saliente al catálogo, las tareas programadas, los threads que no lo heredan, la purga del outbox.
5. **Registros que faltan**: eventos críticos que hoy no dejan ninguna huella. Como mínimo revisar el fallo de autenticación, el rechazo por rate limiting, el conflicto de versión del locking optimista, la respuesta servida por *stale-while-error* y el mensaje que llega a dead letter.
6. **Métricas que no responden ninguna pregunta**, y preguntas sin métrica: contrastar el catálogo del diseño con lo que hoy existe. Revisar la cardinalidad de las etiquetas propuestas.
7. **Alertas**: si el diseño propone alguna, verificar que sea accionable —que quien la reciba sepa qué hacer— y que su umbral no genere ruido. Marcar también lo que puede romperse sin que ninguna alerta se entere.
8. **Costo y volumen**: estimar cuántas líneas de log genera un `POST /v1/reservations` con el esquema propuesto y cuántas genera el relay del outbox por hora en régimen normal. Un `INFO` por consulta al catálogo son 8 líneas por reserva.

Para **cada hallazgo**, además del problema, definir **cómo detectarlo de forma concreta** —una prueba que se pueda ejecutar, no una inspección visual—. Por ejemplo, y sin limitarse a esto:

- tomar una muestra real de logs del arranque y de un `POST` completo y contrastarla campo por campo contra el esquema definido;
- buscar explícitamente contraseñas, tokens y PII en la salida capturada de la suite de tests antes de salir a producción, como una verificación automatizable;
- emitir un pedido con un `X-Correlation-Id` conocido y verificar que ese mismo valor aparezca en todos los registros del pedido, en el envelope del evento y en los del consumidor;
- provocar el fallo de una dependencia y verificar que quede registrado con el nivel que le corresponde y con la causa, no sólo el síntoma;
- pedirle a la IA que revise una muestra de logs reales contra el esquema y reporte las desviaciones.

## Restricciones

- **Sólo hallazgos con evidencia**: cada uno tiene que apuntar a un archivo y una línea del repositorio, a una propiedad de `application.yml` o a un punto concreto del diseño. Nada de riesgos genéricos de manual.
- Distinguir lo que es **una falla real** de lo que es **una limitación asumida y documentada** (el rastro de auditoría vive en base y no en los logs a propósito, por ejemplo, y los health indicators están apagados por decisión): lo segundo se lista aparte, no como hallazgo.
- Priorizar por **impacto en el incidente**: primero lo que impide diagnosticar o lo que expone un dato regulado, después lo que sólo incomoda.
- **Los hallazgos de datos sensibles son severidad máxima por defecto**, incluso si el dato está en un `DEBUG` que hoy no se emite: un cambio de nivel en caliente lo enciende.
- No proponer todavía el código de la solución: este paso identifica y ordena; la remediación es el paso siguiente.
- Las pruebas de detección tienen que poder correr en el entorno del repositorio (`compose.yaml` + `mvn verify`), sin infraestructura paga.

## Formato de salida

1. **Tabla de hallazgos**: # | hallazgo | categoría (estructura / datos sensibles / severidad / trazabilidad / registro faltante / métricas / alertas / costo) | evidencia (archivo:línea o punto del diseño) | impacto | severidad.
2. **Tabla de detección**: hallazgo | prueba concreta que lo expone | resultado esperado si está bien | resultado esperado si está mal.
3. **Muestra de logs analizada**: los registros reales revisados y, para cada uno, qué campo del esquema le falta o le sobra.
4. **Mapa de trazabilidad**: cada salto del camino de un pedido, con si el `correlationId` sobrevive o se pierde y dónde exactamente.
5. **Mitigación propuesta** por hallazgo, en una o dos líneas, sin escribir el código.
6. **Limitaciones asumidas**, listadas aparte con el motivo por el que no son hallazgos.
7. **Orden sugerido de remediación**, con el criterio usado para ordenarlo.
