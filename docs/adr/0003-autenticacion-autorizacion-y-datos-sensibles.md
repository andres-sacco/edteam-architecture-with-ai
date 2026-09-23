# 0003 — Autenticación, autorización por recurso y datos sensibles

- **Estado:** Aceptado
- **Fecha:** 2026-09-23
- **Prompt origen:** [12 — Remediación de seguridad](../prompts/12-remediacion-de-seguridad.md)
- **Entrada:** [Modelo de amenazas STRIDE](../security/threat-model.md)

## Contexto

La API no tenía capa de seguridad: `spring-boot-starter-security` no estaba en el
`pom.xml` y `ReservationController` atendía pedidos anónimos. Sobre esa base, el
[modelo de amenazas](../security/threat-model.md) encontró siete amenazas críticas, y
seis de ellas son consecuencia de la misma: **no hay forma de saber quién pide qué**.

Los tres hechos que ordenaron el trabajo:

1. **El identificador de reserva es un `BIGSERIAL` expuesto tal cual.** Un `for` sobre
   los ids volcaba documento y fecha de nacimiento de todos los pasajeros del sistema
   (T-02), y `GET /v1/reservations` sin filtro devolvía las reservas de todos de a 100
   por página (T-03).
2. **Las escrituras sólo pedían `If-Match`,** y el `ETag` se conseguía del `GET`
   anónimo: dos pedidos cancelaban la reserva de cualquiera (T-05).
3. **El email del comprador viajaba en el cuerpo del alta** (T-07) y la clave de
   idempotencia era global (T-16): dos formas distintas de operar a nombre de otro.

Además, el borde de confianza con la base y con el proveedor externo estaba abierto:
credenciales en el repositorio (T-14), documento de pasajero en claro en la columna
(T-21), PII en los logs (T-12) y ninguna trazabilidad de quién hizo qué (T-11).

## Decisión

### 1. Autenticación: resource server OAuth2, token Bearer

Se agrega `spring-boot-starter-oauth2-resource-server`. La aplicación **verifica**
firmas; no emite credenciales. La emisión es problema del proveedor de identidad, que
es lo correcto cuando la API la consumen varios frontends y partners.

Dos modos, y ninguna combinación produce una aplicación que arranque sin validar
tokens (`JwtDecoderFactory` corta el arranque):

| Modo | Cuándo | Cómo |
|---|---|---|
| JWKS del emisor | Cualquier entorno real | `reservations.security.jwt.jwk-set-uri`, obligatoriamente HTTPS |
| HMAC de desarrollo | Local y tests | `dev-tokens: true` + una clave placeholder publicada, con aviso en cada arranque |

El modo de desarrollo existe por la misma razón que el stub del catálogo y el cache en
memoria: **el build no puede depender de que haya un servicio externo levantado**.

Se validan firma, expiración, emisor y **audiencia**. La audiencia es lo que impide que
un token que el mismo IdP emitió para otro servicio sirva acá.

### 2. Autorización: la regla de negocio es del dominio; el filtro, del adaptador

Es la decisión estructural del ADR, y la que más fácil se rompe.

```
infrastructure.security     →  QUIÉN LLEGA A UN ENDPOINT
  SecurityConfiguration        cadena de filtros, denyAll() por defecto
  JwtActorConverter            claims  →  Actor de dominio

domain.access               →  QUIÉN VE O TOCA UNA RESERVA
  Actor                        email + roles, sin una sola clase de Spring
  ReservationAccessPolicy      "una reserva pertenece a un único usuario"

application.port.in         →  el comando LLEVA el solicitante
  CreateReservationCommand(actor, …)   GetReservationQuery(id, actor)   …
```

Un `@PreAuthorize` en un servicio de aplicación, o un `SecurityContextHolder` leído
desde un caso de uso, habría resuelto el problema del día y atado la lógica de negocio
al framework: a partir de ahí la autorización sólo funciona si el pedido entró por
HTTP, y el próximo adaptador de entrada —un consumidor de mensajería, un job— queda sin
ninguna. `HexagonalArchitectureTest` agrega dos reglas que rompen el build si eso pasa.

**Un recurso ajeno responde 404, no 403.** Si los dos casos se distinguieran, el par de
códigos sería un censo: recorriendo los ids se sabría cuántas reservas hay y cuáles
están ocupadas. El 403 se reserva para el único caso en que el rechazo no revela nada
—pedir el listado de otro usuario, donde el cliente ya sabe cuál es su propio email—.

**El listado deja de admitir «todas».** El filtro por usuario se deriva del token; sólo
el rol `backoffice` puede pedir el de otro, y queda auditado.

**El comprador sale del token.** `UserRequest` desaparece del cuerpo del alta. La clave
de idempotencia pasa a ser única por `(usuario, clave)`, de modo que una clave filtrada
en el log de un proxy no sirve desde otra identidad.

### 3. Datos sensibles

| Qué | Decisión |
|---|---|
| **Qué se loguea** | El usuario se identifica por su id interno, nunca por su email; donde el email es inevitable va enmascarado (`an***@example.com`). El texto de las notificaciones —que lleva ruta y fechas de viaje— baja a `DEBUG` |
| **Qué se serializa** | La respuesta del alta refleja lo que el cliente envió. Se elimina la deduplicación global de pasajeros por documento: era una optimización de almacenamiento que convertía el alta en un oráculo de datos ajenos |
| **Qué se protege en reposo** | El documento se cifra con AES-256-GCM en la columna, con prefijo de versión para poder rotar la clave. Un `pg_dump` deja de ser un dump de PII |
| **Qué llega de afuera** | Todo dato externo se sanea antes de loguearlo: en un log de texto un `\n` no agrega una línea, agrega registros |

El cifrado **no es determinista** a propósito. Eso rompe la búsqueda por documento, que
es exactamente lo que había que romper.

### 4. Trazabilidad

Tabla `auditoria`, append-only garantizado por un trigger de PostgreSQL —un registro que
la aplicación puede reescribir no prueba nada— y escrita por un puerto
(`AuditTrailPort`), no por un logger: el registro y el cambio que describe tienen que
confirmarse juntos.

Dos propagaciones, y la distinción es el requisito:

- **`ALLOWED`** en la transacción del caso de uso. Si hay rollback, no hubo cambio que
  auditar.
- **`DENIED`** en una transacción propia. El rechazo termina en excepción y esa
  excepción hace rollback: con propagación normal, la evidencia del intento se iría
  junto con el intento.

No se auditan las lecturas exitosas: sería una fila por `GET` y una segunda copia de la
PII con su propia política de retención. La auditoría prueba **quién tocó qué**, no
repite el contenido.

### 5. Superficie y abuso

- Swagger UI y `/v3/api-docs` **apagados por defecto**: en producción no existen, y el
  contrato que leen los partners sale del archivo versionado, no del runtime. Encendidos
  se sirven **sin token**, y es deliberado: exigirlo no los protege, los rompe. Una
  navegación del navegador no puede llevar un header `Authorization`, así que el 401
  llegaría antes de que exista la pantalla donde apretar *Authorize*, y la UI busca el
  documento por XHR sin credencial. Quedaría una UI inusable con la falsa sensación de
  estar protegida. El control es el interruptor; lo que la UI **ejecuta** —`/v1/**`—
  sigue exigiendo token.
- Actuator en un puerto de gestión propio que el despliegue no publica; en el puerto de
  la aplicación exige token igual, salvo las sondas.
- CORS por allowlist explícita por entorno, nunca `*`.
- Cuota por identidad —y por IP cuando no hay identidad—, con cuotas distintas para
  lectura y escritura. **Es la última línea, no la primera:** el lugar del rate limiting
  es el gateway, donde el pedido se rechaza antes de gastar un hilo y donde la cuenta es
  una sola para todas las instancias.
- Credenciales sólo por entorno. Ninguna queda en el repositorio; los valores de
  `.env.example` y los placeholders de `application.yml` son de desarrollo y están
  marcados como tales, y la aplicación avisa en cada arranque cuando los está usando.

## Consecuencias

### A favor

- Las seis amenazas críticas de identidad (T-01, T-02, T-03, T-05, T-07, T-16) se
  cierran con un solo cambio estructural, y se cierran **para cualquier adaptador de
  entrada**, no sólo para el REST.
- La regla de negocio sobre propiedad de una reserva se prueba con cinco objetos y
  ningún framework (`ReservationAccessPolicyTest`).
- Rotar una credencial deja de requerir un commit.
- Un dump de la base ya no es un dump de PII.

### En contra, y asumido

- **Se escriben más filas de `pasajero`.** Un pasajero que viaja en tres reservas ahora
  son tres filas. Es el precio de que la respuesta no refleje datos que el cliente no
  envió. Reconciliar identidades sigue siendo deseable: como proceso interno, no como
  efecto observable del alta.
- **La columna `documento` dejó de ser buscable.** Cualquier caso de uso futuro que
  necesite buscar por documento necesitará un índice ciego (HMAC en una columna aparte),
  no volver al cifrado determinista.
- **`GetReservationService` dejó de ser `readOnly`.** El rechazo escribe auditoría. Es
  una transacción de escritura por lectura autorizada, sin escrituras reales.
- **La cuota por proceso no es la cuota del sistema.** Con N instancias la cuota
  efectiva es N veces la configurada. Es aceptable para una última línea de defensa y
  está documentado.
- **El precio lo sigue fijando el cliente (T-04).** No se cierra acá: requiere un
  `PricingPort` contra un proveedor que todavía no existe. Es el próximo paso y bloquea
  la integración de pagos.
- **El modo de tokens de desarrollo es un riesgo si se despliega mal configurado.** Se
  acota con un aviso en cada arranque y con el corte del arranque cuando se lo apaga sin
  configurar un JWKS, pero sigue siendo una propiedad que alguien puede dejar en `true`.

### Cambios visibles para los clientes

Reflejados en [`docs/api/openapi.yaml`](../api/openapi.yaml), que ahora se regenera con
un comando en lugar de convertirse a mano:

| Cambio | Detalle |
|---|---|
| `Authorization: Bearer` obligatorio | Esquema `bearerAuth` declarado en el documento |
| **401** en las cinco operaciones | Sin token, vencido o inválido |
| **403** en el listado | Pedir el de otro usuario sin rol de backoffice |
| **404** ampliado | Una reserva ajena responde igual que una inexistente |
| **429** en las cinco operaciones | Cuota superada, con `Retry-After` |
| `CreateReservationRequest` sin `user` | El comprador sale del token |
| `X-Correlation-Id` en la respuesta | Para poder rastrear un pedido puntual |

El `userId` del listado deja de ser un filtro libre: para un titular es redundante y
para otro usuario es 403.
