# Modelo de amenazas (STRIDE)

> Salida del prompt [11 — Auditoría de seguridad (STRIDE)](../prompts/11-auditoria-de-seguridad-stride.md).
> Entrada del prompt [12 — Remediación de seguridad](../prompts/12-remediacion-de-seguridad.md).

Análisis hecho sobre el código de este repositorio, no sobre el enunciado: cada amenaza
cita el archivo, la constraint o la property que la habilita, y varias son verificables
con un `curl` contra una instancia local.

---

## 1. Qué se protege y dónde están los bordes

**Activos, por valor de negocio descendente:**

| Activo | Dónde vive | Por qué importa |
|---|---|---|
| Datos de pasajeros (`documento`, `fecha_nacimiento`, nombre) | `pasajero`, y en el cuerpo de las 5 respuestas | PII regulada (GDPR / Ley 25.326). Una fuga es notificable y multable |
| Reservas y su estado | `reserva` | Es el producto. Una cancelación indebida es un pasajero que no vuela |
| Precio del itinerario | `itinerario.precio` | Es la facturación. Hoy lo fija el cliente (ver T-04) |
| Identidad del comprador | `usuario.email` | Es el identificador de toda la API y el destinatario de las notificaciones |
| Datos de pago | Todavía no existen | Cuando entren, arrastran el alcance PCI-DSS al servicio (ver T-24) |

**Bordes de confianza** (ninguno tiene control de acceso hoy):

```
[ web / mobile / partners ]  ──HTTP 8080, sin TLS ni auth──►  ┌───────────────┐
[ cualquiera en la red    ]  ──/swagger-ui.html, /actuator──► │  reservations │
                                                              └───┬───┬───┬───┘
        PostgreSQL (usuario/password en application.yml) ◄────────┘   │   │
        Redis 6379 (sin password, sin TLS, cache compartido) ◄────────┘   │
        api-catalog por http:// con X-API-Key en header ◄─────────────────┘
```

El borde crítico es el primero: **no existe**. `ReservationController` recibe pedidos
anónimos y no hay `spring-boot-starter-security` en el `pom.xml`, así que todo lo que
sigue se evalúa asumiendo un atacante no autenticado con acceso de red al puerto 8080.

---

## 2. Amenazas priorizadas

Severidad = probabilidad de explotación × impacto de negocio. **Crítica** significa
explotable hoy, sin credenciales, con consecuencia directa sobre PII o facturación.

### 2.1 Críticas

| # | Amenaza | STRIDE | Mitigación propuesta |
|---|---|---|---|
| T-01 | **Acceso anónimo a las 5 operaciones.** No hay autenticación ni autorización: cualquiera que alcance el puerto opera como cualquier usuario. Es la amenaza raíz de las cinco que siguen | S, E, I, T | `spring-boot-starter-security` con recurso OAuth2/JWT (`Bearer`, declarado con `@SecurityScheme` para que salga en el documento generado). El `sub` del token pasa a ser el usuario del pedido; `401`/`403` declarados en las `@ApiResponse`. Todo endpoint `denyAll()` por defecto y se habilita explícitamente |
| T-02 | **IDOR sobre `GET /v1/reservations/{id}`.** El id es un `BIGSERIAL` expuesto tal cual: `for i in $(seq 1 100000)` vuelca documento y fecha de nacimiento de todos los pasajeros del sistema | I | Autorización a nivel de recurso: el caso de uso recibe el identificador del solicitante y `GetReservationService` devuelve `404` (no `403`) si la reserva no es suya. Complementario: reemplazar el id secuencial por UUIDv7 u opaco, para que la enumeración deje de ser gratis aun con un bug de autorización |
| T-03 | **Volcado masivo del listado.** `GET /v1/reservations` sin `userId` devuelve las reservas de todos, paginadas de a 100. Un scraper baja la base entera en minutos | I | El filtro por usuario deja de ser un query param opcional y pasa a derivarse del token; sólo un rol `partner`/`backoffice` puede listar de otros, y con auditoría. Rate limit por identidad sobre el listado |
| T-04 | **El cliente fija el precio de su reserva.** `ItineraryRequest.price` viaja en el cuerpo y se persiste tal cual (`itinerario.precio`); la única validación es `CHECK (precio >= 0)`. Un `POST` con `"price":"0.01"` crea una reserva válida por un centavo, y el `PUT` permite bajarlo después | T | El precio no se acepta del cliente: se cotiza del lado del servidor contra el proveedor (puerto `PricingPort`, hoy inexistente) y el cliente sólo manda la referencia de la cotización, con vencimiento corto. Mientras tanto: recalcular y rechazar cualquier desvío, y firmar la cotización |
| T-05 | **Cancelación y modificación de reservas ajenas.** `DELETE` y `PUT` sólo piden `If-Match`, cuyo valor se obtiene del `GET` anónimo. Dos pedidos bastan para cancelar la reserva de otro: sabotaje masivo, y con las aerolíneas integradas, cancelaciones irreversibles | T, D | La misma autorización por recurso de T-02, aplicada en los casos de uso y no en el controller. Transición de estado auditada (ver T-11) y, para cancelaciones, confirmación fuera de banda al email del titular |
| T-06 | **Oráculo de datos de pasajeros por documento.** `ReservationPersistenceAdapter.resolvePassenger` reutiliza la fila existente por `uq_pasajero_doc` y la respuesta devuelve los datos **almacenados**: enviando un documento ajeno en el alta, el cuerpo del `201` responde con el nombre, apellido y fecha de nacimiento reales de su titular. A la inversa, registrar primero un documento con datos falsos se los impone a la reserva legítima que venga después | I, T | Que la deduplicación no cruce el borde: la respuesta refleja lo que el cliente envió, y la reconciliación de la identidad del pasajero queda del lado del servidor. Mejor aún, dejar de usar el documento como clave natural global — que el pasajero sea propio de la reserva y la deduplicación, un proceso interno. El documento, cifrado o hasheado en la columna (ver T-21) |
| T-07 | **Suplantación del comprador en el alta.** El email del `UserRequest` no se verifica: cualquiera reserva a nombre de `victima@ejemplo.com`, y la notificación de "tu reserva" le llega a la víctima desde nuestro canal — phishing con nuestra reputación | S | El usuario sale del token, no del cuerpo (`UserRequest` desaparece del alta, como ya anticipa el README). Hasta entonces, verificación del email antes de emitir cualquier notificación |

### 2.2 Altas

| # | Amenaza | STRIDE | Mitigación propuesta |
|---|---|---|---|
| T-08 | **Swagger UI y `/v3/api-docs` abiertos.** `springdoc.swagger-ui.enabled: true` publica el mapa completo de la API y un cliente que ejecuta pedidos reales desde el navegador, sin autenticación | I | `springdoc.swagger-ui.enabled: ${SWAGGER_UI_ENABLED:false}` y encendido sólo en entornos no productivos; el documento, detrás de autenticación o publicado en el portal de partners, no en el runtime |
| T-09 | **Sin rate limiting ni cuotas.** `maximum-pool-size: 20` con `connection-timeout: 3000`: unos cientos de listados concurrentes con `OFFSET` alto —y sin los índices que el README reconoce faltantes— agotan el pool y devuelven `500` a todo el mundo. El `POST` es peor: hasta 20 consultas al catálogo en serie, con peor caso de ~6,5 s cada una | D | Rate limit por identidad y por IP en el borde (gateway o filtro con bucket4j), cuotas distintas por tipo de cliente, y un límite de pedidos en vuelo. Agregar los índices pendientes y paginar por keyset en lugar de `OFFSET` |
| T-10 | **Amplificación contra el catálogo externo.** Un código IATA inexistente no tiene hit positivo; con 17.576 combinaciones y `negative-cache-ttl: 5m`, un atacante convierte cada pedido nuestro en 3 intentos contra el proveedor y nos hace ganar un `429` (o la factura) | D | Validar el código contra un maestro local antes de salir a la red, circuit breaker sobre el cliente del catálogo (ya identificado como pendiente en el README), y cuota propia de llamadas salientes por unidad de tiempo |
| T-11 | **Sin trazabilidad de quién hizo qué.** `reserva` guarda `fecha_actualizacion` pero no el actor, y los logs de `CancelReservationService` sólo llevan `id` y `version`. Ante una disputa con un pasajero o una aerolínea, no hay forma de probar quién canceló | R | Log de auditoría append-only (actor, acción, recurso, versión, IP, timestamp, correlation id) escrito en la misma transacción que el cambio; `correlation-id` propagado desde el borde y presente en todo log |
| T-12 | **PII en los logs.** `UserPersistenceAdapter:65` loguea el email en `INFO` y `LoggingNotificationAdapter:46` loguea email + itinerario. Los logs suelen salir del perímetro hacia un SaaS de observabilidad, que hereda el dato regulado | I | Enmascarar la PII en el log (`a***@ejemplo.com`), bajar a `DEBUG` lo que no haga falta en producción, y referenciar al usuario por su id interno en vez de por su email |
| T-13 | **Redis sin autenticación ni TLS.** `compose.yaml` lo publica en 6379 sin password y `REDIS_PASSWORD` tiene default vacío. Quien lo alcance envenena `rsv:ver:{id}`: fuerza `304` sobre datos viejos o `409` evitables, y vacía el cache del catálogo para arrastrar el sistema al escenario de T-09 | T, D | `requirepass` y TLS obligatorios, Redis en red privada sin puerto publicado, credencial desde el gestor de secretos. La invariante de diseño ("una caché caída no puede tumbar el servicio") ya acota el daño de la caída, pero no el del envenenamiento |
| T-14 | **Credenciales en claro en el repositorio.** `application.yml` lleva usuario y password de PostgreSQL; `compose.yaml`, los de PostgreSQL y el root de MySQL. Quedan en el historial de git y en la imagen | I, E | Credenciales sólo por entorno (`${DB_PASSWORD}`), gestor de secretos en producción, rotación de las expuestas y escaneo de secretos en el pipeline |
| T-15 | **Tráfico sin cifrar.** La aplicación escucha HTTP en 8080 y el catálogo se consulta por `http://` con la API key en un header: en una red hostil se leen las reservas y se roba la credencial | I, T | TLS terminado en el borde y HSTS; `https://` obligatorio hacia el catálogo y hacia cualquier proveedor futuro; rechazar el arranque si una `base-url` de producción no es HTTPS |
| T-16 | **`Idempotency-Key` ajena devuelve la reserva del otro.** `CreateReservationService` busca por la clave y devuelve `200` con la reserva completa del cliente que la usó primero. La clave es un UUID —no se adivina—, pero viaja en un header que queda en proxies, logs de acceso y SDKs móviles | I, S | Alcanzar la clave al usuario: la unicidad pasa a ser `(usuario_id, idempotency_key)` y la búsqueda se filtra por el usuario del token, así una clave filtrada no sirve desde otra identidad |
| T-17 | **El outbox en memoria nunca se purga y no sobrevive al reinicio.** `InMemoryEventOutbox` deja `DISPATCHED` y `FAILED` en el `ConcurrentHashMap` para siempre: crecimiento sostenido del heap proporcional al tráfico. Y un reinicio pierde lo pendiente, sin registro de qué no se envió | D, R | La tabla `outbox_message` ya prevista, con `SELECT ... FOR UPDATE SKIP LOCKED`, purga de los despachados y dead letter consultable de los `FAILED` |
| T-18 | **Actuator expuesto.** `health,info,metrics` sin autenticación: `metrics` revela volumetría de negocio y `http.server.requests` la superficie real de la API | I | Actuator en un puerto de gestión separado, no publicado hacia afuera; `health` con `show-details: never` (ya es el default) y el resto detrás de autenticación |

### 2.3 Medias

| # | Amenaza | STRIDE | Mitigación propuesta |
|---|---|---|---|
| T-19 | **CORS sin definir con múltiples frontends.** Hoy no hay configuración; la presión de integrar web y mobile termina en un `@CrossOrigin("*")`. Y sin autenticación, un sitio cualquiera ya puede disparar altas con un `POST` simple | S, T | Allowlist explícita de orígenes por entorno, nunca `*` junto con credenciales, y que la decisión quede en configuración y no en anotaciones sueltas por controller |
| T-20 | **PII sin política de retención.** La cancelación es baja lógica: documento y fecha de nacimiento quedan indefinidamente, y no hay forma de ejercer el derecho de supresión (tampoco existe el recurso de usuarios) | I | Política de retención con purga o anonimización vencido el plazo legal, y un caso de uso de supresión cuando entre `/v1/users` |
| T-21 | **Sin cifrado en reposo ni a nivel de columna.** Un dump de `pasajero` es un dump de PII en claro; el backup hereda el problema | I | Cifrado en reposo en el motor, cifrado o tokenización de `documento`, backups cifrados con claves gestionadas y acceso a la base auditado |
| T-22 | **Alta ilimitada de usuarios y reservas basura.** Cada alta anónima inserta una fila en `usuario`; nada impide envenenar el maestro con millones de emails inventados | D | El mismo rate limiting de T-09 más la verificación de email de T-07: sin identidad verificada no hay alta de usuario |
| T-23 | **Log forging desde el catálogo.** `RestCityCatalogClient` loguea el `body` del proveedor sin sanitizar: un proveedor comprometido inyecta saltos de línea y fabrica entradas de log | R | Sanitizar y truncar todo dato externo antes de loguearlo; logs en JSON estructurado, donde el salto de línea deja de ser un separador de registros |
| T-24 | **La pasarela de pagos arrastra el alcance PCI-DSS.** No hay datos de pago todavía, y por eso es el momento de decidir: si el PAN toca este servicio, todo el repositorio entra en alcance | I, T | Tokenización del lado del proveedor (el PAN nunca llega a nuestro backend: checkout hospedado o campos embebidos), y el servicio guarda sólo el token y los últimos cuatro dígitos |
| T-25 | **Cadena de suministro sin control.** El `pom.xml` no tiene análisis de dependencias ni SBOM, y una dependencia comprometida corre con todos los privilegios del servicio | T, E | `dependency-check` o equivalente en el build con umbral que rompa el pipeline, SBOM por release, y versiones pinneadas revisadas periódicamente |

---

## 3. Lo que el diseño ya resuelve

No todo está por hacerse; conviene no romperlo en la remediación:

| Control existente | Qué amenaza acota |
|---|---|
| `Cache-Control: no-store, private` en las 5 operaciones | Representaciones con PII guardadas por proxies, CDN o disco del navegador |
| No se cachean cuerpos de respuesta (sólo escalares y metadatos) | Que un cache compartido se vuelva un repositorio de PII |
| `ProblemDetail` uniforme, `500` sin detalle y stack trace sólo del lado del servidor | Fuga de internals por mensajes de error |
| Validación declarativa con cotas (`1–9` pasajeros, `1–10` tramos, `size ≤ 100`, longitudes máximas) | Cuerpos y páginas desmedidos como vector de agotamiento |
| DTOs propios del adaptador; el agregado nunca se serializa | Sobreexposición de campos y mass assignment |
| Optimistic locking con `If-Match` e `Idempotency-Key` con `UNIQUE` | Escrituras que se pisan y duplicados por reintento |
| Timeouts y reintentos propios del catálogo, con jitter | Que un proveedor lento cuelgue el pool de conexiones |
| Consultas con JPA parametrizado y `ddl-auto: validate` | Inyección SQL y desincronización de esquema |
| Degradación de la caché a origen; `management.health.redis` apagado | Que la caída del cache saque instancias de rotación |

---

## 4. Orden de remediación sugerido

Entrada del prompt 12. El orden no es el de la tabla: es el que más riesgo saca por unidad de trabajo.

1. **Autenticación y autorización** (T-01, T-02, T-03, T-05, T-07, T-16). Un solo cambio estructural cierra las seis; todo lo demás es secundario mientras la API sea anónima. En hexagonal el filtro va en `adapter/in/rest` y en la config, pero la **decisión de autorización pertenece a los casos de uso**: el comando lleva quién lo pide y el servicio decide, para que no dependa de que el próximo adaptador de entrada se acuerde de chequear.
2. **Cerrar la superficie expuesta** (T-08, T-18, T-15, T-14). Configuración y despliegue, sin tocar código de dominio: horas de trabajo, mucho riesgo menos.
3. **Precio del lado del servidor** (T-04) y **fuga por deduplicación de pasajeros** (T-06). Son cambios de diseño en la aplicación, y los dos bloquean la integración de pagos.
4. **Disponibilidad** (T-09, T-10, T-22, T-17). Rate limiting en el borde, circuit breaker y outbox persistente.
5. **Trazabilidad y datos** (T-11, T-12, T-13, T-20, T-21, T-23). Auditoría, enmascarado y ciclo de vida de la PII.
6. **Cuando entre pagos** (T-24) y **continuo** (T-25, T-19).
