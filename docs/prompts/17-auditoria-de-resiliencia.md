# 17 — Auditoría de la resiliencia: circuitos, fallbacks y reintentos que no hacen lo que dicen

**Etapa:** Revisión

**Salida esperada:** Tabla de hallazgos con riesgo, evidencia, forma de detectarlo y mitigación

---

## Rol

Actúa como revisor de confiabilidad, con foco en las fallas de resiliencia que sólo se ven cuando la dependencia se cae de verdad.

## Contexto

El sistema de reservas de vuelos (Java 21 + Spring Boot 3.5, arquitectura hexagonal, PostgreSQL + Redis + RabbitMQ) tiene una estrategia de resiliencia recién diseñada y una implementación parcial ya en el repositorio.

**Lo que hay en el código hoy:**

- `infrastructure/adapter/out/airport/catalog/RestCityCatalogClient`: clasifica la respuesta del catálogo. 5xx, 429, timeout y error de conexión son `AirportCatalogUnavailableException` (transitorio); 4xx que no es 404, credencial vencida o cuerpo fuera de contrato son `AirportCatalogIntegrationException` (permanente); 404 y 200 con cuerpo vacío son `Optional.empty()`, que es una respuesta y no un fallo.
- `infrastructure/adapter/out/airport/catalog/RetryingCityCatalogClient`: 3 intentos, backoff exponencial de 100 ms a 500 ms con jitter sorteado sobre la mitad superior, reintenta **sólo** la excepción transitoria. El `Sleeper` es inyectable para que los tests verifiquen la política sin dormir de verdad.
- `infrastructure/adapter/out/airport/CachingAirportCatalog`: TTL positivo de 30 m, negativo de 5 m y *stale-while-error* de 2 h. Si no hay **nada** guardado, la excepción sube.
- `infrastructure/cache`: `RedisCacheStore` con `timeout: 200ms`, `MeteredCacheStore` y `InMemoryCacheStore` como fallback. Un error de Redis degrada al origen.
- `infrastructure/adapter/out/messaging`: `RabbitEventPublisher` con publisher confirms y `confirm-timeout: 5s`; `LoggingEventPublisher` cuando la mensajería está apagada; `UnavailableDeadLetterQueue` cuando no hay broker.
- `application/service/OutboxDispatcherService` + `infrastructure/adapter/out/outbox/JdbcEventOutbox`: backoff de 5 s a 5 m, `max-attempts: 10`, `retry-ceiling: 6h`, `claim-lease: 2m`, `SELECT ... FOR UPDATE SKIP LOCKED`.
- `infrastructure/adapter/in/messaging/ReservationEventListener`: reintentos del consumidor por TTL de la cola de espera (`retry-delay: 30s`, `max-retry-rounds: 5`) y DLQ.
- `application.yml`: health indicators de `redis` y `rabbit` **apagados a propósito**, `server.shutdown: graceful` con 25 s, threads virtuales encendidos, pool Hikari de 20.

**Lo que aporta el paso anterior:** el diseño de [16 — Diseño de la resiliencia](16-diseno-de-resiliencia.md): umbrales del circuit breaker, políticas de reintentos y fallbacks por dependencia.

La entrada de este prompt son **las dos cosas**: el diseño y el código que hoy lo implementa a medias.

## Tarea

Auditar diseño e implementación buscando las fallas que pasan inadvertidas en una revisión normal. Como mínimo:

1. **Circuitos mal calibrados**: ¿hay algún circuit breaker cuyo umbral haga que nunca se abra —porque la ventana es demasiado grande, el mínimo de llamadas nunca se alcanza o el error real no cuenta como fallo—? ¿Y alguno que se abra con demasiada facilidad y deje sin servicio algo que estaba sano?
2. **Fallbacks que confunden**: ¿algún fallback devuelve datos tan desactualizados que el usuario toma una decisión equivocada? ¿Alguno devuelve un error genérico que tapa la causa? ¿Alguno traga una excepción que el flujo necesitaba ver? El *stale-while-error* de 2 h del catálogo es el caso obvio a revisar, pero no el único.
3. **Reintentos sobre operaciones no idempotentes**: ¿hay algún punto donde se reintente una escritura sin clave de idempotencia que la proteja? Incluir los reintentos implícitos: el del cliente HTTP, el del consumidor de mensajes y el del relay del outbox.
4. **Backoff fijo o ausente**: ¿hay algún reintento sin espera, con espera fija o sin jitter, que reintente en caliente contra un destino que ya está caído y empeore la sobrecarga? ¿Hay algún techo, o la progresión exponencial se vuelve absurda?
5. **Interacción entre capas**: ¿se multiplican los reintentos al anidarse —el retry del cliente por dentro del retry del caso de uso, o el del consumidor por encima del del broker—? ¿Cuántos pedidos reales genera en el peor caso un solo pedido del usuario?
6. **Agotamiento de recursos**: con threads virtuales y un pool de 20 conexiones, ¿qué pasa con la latencia y con el pool cuando el catálogo acepta la conexión y no contesta? ¿Hay algún punto donde una dependencia lenta consuma memoria sin límite (colas internas, buffers, reintentos acumulados)?
7. **Presupuesto de tiempo**: sumar timeouts, reintentos y backoff del camino completo de un `POST /v1/reservations` con un itinerario de ida y vuelta con escala —8 consultas de ciudad en serie— y decir cuánto tarda en el peor caso. Comparar con lo que el cliente HTTP del frontend está dispuesto a esperar.
8. **Observabilidad de la resiliencia**: ¿se puede saber, sin entrar al servidor, si un circuito está abierto, hace cuánto, cuántos reintentos se están haciendo y cuántas respuestas salieron por el fallback?

Para **cada hallazgo**, además del problema, definir **cómo detectarlo de forma concreta** —una prueba que se pueda ejecutar, no una inspección visual—. Por ejemplo, y sin limitarse a esto:

- simular la caída de una dependencia y verificar que el circuito efectivamente se abra, y en cuántas llamadas;
- confirmar que el fallback responde con datos razonables y no con un error genérico, y verificar cuán viejo puede ser el dato que devuelve;
- revisar la métrica de reintentos bajo carga con la dependencia caída: si crece sin límite, hay un problema de configuración;
- confirmar que toda escritura reintentada viaja con una clave de idempotencia;
- restablecer la dependencia y verificar que el circuito vuelve a cerrarse solo, sin reiniciar el proceso.

## Restricciones

- **Sólo hallazgos con evidencia**: cada uno tiene que apuntar a un archivo y una línea del repositorio, a una propiedad de `application.yml` o a un punto concreto del diseño. Nada de riesgos genéricos de manual.
- Distinguir lo que es **una falla real** de lo que es **una limitación asumida y documentada** (los health indicators apagados a propósito, por ejemplo, o la ausencia de presupuesto de tiempo ya anotada en el javadoc): lo segundo se lista aparte, no como hallazgo.
- Priorizar por **impacto en el usuario y en el negocio**, no por facilidad de arreglo. Un circuito que nunca se abre en el camino del `POST` pesa más que uno mal calibrado en un camino secundario.
- No proponer todavía el código de la solución: este paso identifica y ordena; la remediación es el paso siguiente.
- Las pruebas de detección tienen que poder correr en el entorno del repositorio (`compose.yaml` + `mvn verify`), sin infraestructura paga. Bajar un contenedor de `compose.yaml` cuenta como forma válida de simular una caída.
- Los números del presupuesto de latencia se calculan con los valores reales de `application.yml`, no con los defaults de la librería.

## Formato de salida

1. **Tabla de hallazgos**: # | hallazgo | categoría (circuito / fallback / reintentos / backoff / anidamiento / recursos / latencia / observabilidad) | evidencia (archivo:línea, propiedad o punto del diseño) | impacto | severidad.
2. **Tabla de detección**: hallazgo | prueba concreta que lo expone | resultado esperado si está bien | resultado esperado si está mal.
3. **Presupuesto de latencia medido**, por camino (`POST`, `PUT`, `GET`), con el peor caso desglosado y el número de pedidos reales que genera uno del usuario.
4. **Mitigación propuesta** por hallazgo, en una o dos líneas, sin escribir el código.
5. **Limitaciones asumidas**, listadas aparte con el motivo por el que no son hallazgos.
6. **Orden sugerido de remediación**, con el criterio usado para ordenarlo.
