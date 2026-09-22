# Cuellos de botella candidatos a caché

> Salida del prompt [09 — Cuellos de botella para caché](../prompts/09-cuellos-de-botella-cache.md).
> Entrada del prompt [10 — Implementación de la caché](../prompts/10-implementacion-de-cache.md).

Análisis hecho sobre el código de este repositorio, no sobre el enunciado: los costos
por pedido salen de leer los adaptadores, las consultas y el esquema.

---

## 1. Costo real de cada endpoint

| Endpoint | Consultas a PostgreSQL | Llamadas HTTP externas | Notas |
|---|---|---|---|
| `GET /v1/reservations/{id}` | 1 (`@EntityGraph`: reserva + usuario + itinerario + segmentos + pasajeros) | 0 | Lookup por PK, pero hidrata 5 tablas |
| `GET /v1/reservations` | **3** (`count`, `findPageOfIds`, `findAllByIdIn`) | 0 | Las dos primeras joinean reserva → itinerario → itinerario_segmento → segmento |
| `POST /v1/reservations` | 1 + (1–2 por segmento) + (1–2 por pasajero) + inserts | **2 por segmento** | Las llamadas al catálogo dominan la latencia |
| `PUT /v1/reservations/{id}` | 1 lectura + resolución de segmentos + escritura | **2 por segmento** | Mismo costo de validación que el alta |
| `DELETE /v1/reservations/{id}` | 1 lectura + 1 escritura | 0 | El más barato; no valida aeropuertos |

Dos hechos del código que condicionan todo lo que sigue:

- **`RestCityCatalogClient` no define timeouts ni reintenta** (decisión explícita, documentada
  en la propia clase). Un catálogo lento deja colgado el pedido de reserva que lo disparó.
- **`spring.datasource.hikari.maximum-pool-size: 20`** con threads virtuales habilitados: miles
  de pedidos concurrentes compiten por 20 conexiones con `connection-timeout: 3000`. El
  mecanismo por el que la caché ayuda no es sólo ahorrar latencia de consulta — es **liberar
  conexiones del pool**, que es el recurso realmente escaso bajo concurrencia.

### Dato sensible, endpoint por endpoint

`PassengerResponse` expone `firstName`, `lastName`, `birthDate` y `documentNumber`.
Cualquier representación completa de una reserva —individual o dentro de una página— lleva
documento y fecha de nacimiento de personas físicas. La restricción de no cachear datos
sensibles **descarta cachear los cuerpos de respuesta de los tres endpoints de lectura**.
Lo que sigue trabaja alrededor de esa restricción: se cachean metadatos y escalares, no
representaciones.

---

## 2. Lista priorizada

### P0 — Catálogo externo de ciudades (detrás de `POST` y `PUT`)

**Qué es:** `GET /city/{code}` del `api-catalog`, invocado por `AirportExistenceValidator`
una vez por cada aeropuerto del itinerario (origen y destino de cada tramo).

**Por qué encabeza la lista:**

1. Es la **única dependencia de red en el camino del pedido**, y la única sin timeout ni
   reintento. Es el punto de falla que convierte una degradación del proveedor en reservas
   colgadas.
2. Un itinerario de ida y vuelta con escala son 8 llamadas HTTP secuenciales por reserva.
3. El dato es **casi estático**: el catálogo de ciudades cambia con frecuencia mensual, no
   por pedido.
4. **Costo en memoria despreciable**: son booleanos indexados por un código de 3 letras.
   Unos cientos de entradas, del orden de decenas de KB. Es el mejor ratio
   beneficio/memoria de toda la lista, que es justo lo que importa en un free tier.
5. **Cero datos sensibles**: un código IATA y un booleano.
6. El proveedor responde **429** cuando lo saturamos (`RestCityCatalogClient` ya lo
   clasifica como fallo transitorio). Una caché compartida es también protección contra
   rate limiting.

**Qué cambia respecto de hoy:** ya existe `CachingAirportCatalog`, pero es un
`ConcurrentHashMap` **local al proceso**. Con N instancias hay N cachés frías: cada deploy
y cada scale-out dispara una estampida contra el catálogo justo cuando el sistema está más
frágil. Mover ese decorador a Redis lo vuelve compartido y sobreviviente a reinicios,
**sin tocar `CatalogAirportCatalog` ni el caso de uso**: es exactamente el reemplazo que el
decorador fue diseñado para permitir.

- **Clave:** `catalog:city:{CODE}` · **Valor:** booleano
- **TTL:** 30 m para positivos (el actual), más corto —5 m— para negativos: un código que
  hoy no existe puede darse de alta, y el costo de equivocarse es rechazar una reserva válida.
- **Invalidación:** sólo por TTL. No hay evento que avise que el catálogo cambió.
- **Extra recomendado:** *stale-while-error* — si el catálogo devuelve 5xx/429/timeout,
  servir el último valor conocido aunque esté vencido. Convierte una caída del proveedor en
  una degradación silenciosa en lugar de un rechazo de reservas.

---

### P1 — `GET /v1/reservations` (listado), sólo el `count`

**Por qué es el segundo:** es el endpoint más caro del sistema. Tres consultas por pedido,
dos de ellas con el join `reserva → itinerario → itinerario_segmento → segmento` filtrando
`orden = 0`. Sin filtros, el `count` recorre la tabla entera de reservas con un nested loop
por fila. Y es el endpoint que los frontends llaman en cada carga de pantalla, con los
mismos filtros por defecto una y otra vez.

**Qué cachear y qué no:**

- ✅ **El escalar `count(criteria)`**. Es la mitad cara del pedido, es un `long`, y no
  contiene ningún dato personal. La clave es la tupla de filtros **normalizada y sin
  `page`/`size`/`sort`** — el total no depende de la paginación, así que las 5 páginas que
  recorre un usuario comparten una sola entrada.
- ❌ **Los cuerpos de las páginas.** Dos razones independientes, cada una suficiente:
  cardinalidad explosiva (filtros × página × tamaño × orden) contra una memoria limitada,
  y **PII de todos los pasajeros de hasta 100 reservas por entrada**.

- **Clave:** `rsv:count:{hash(userEmail, statuses, departureFrom, departureTo)}` · **Valor:** `long`
- **TTL:** 30–60 s. El total de una paginación es una pista para la UI, no un invariante de
  negocio: que diga 1.204 cuando son 1.205 no rompe nada, y un TTL corto hace innecesaria
  cualquier invalidación activa.
- **Invalidación:** ninguna, por TTL. Invalidar por escritura exigiría saber qué filtros
  matchea una reserva nueva — imposible sin recorrer las claves.
- **Memoria:** ~100 B por combinación de filtros viva en la ventana del TTL. Del orden de
  cientos de KB.

> **Advertencia honesta:** acá la caché tapa un problema que conviene arreglar igual. No hay
> índice sobre `reserva(fecha_creacion)` —que es el orden por defecto— ni sobre
> `segmento(fecha_vuelo)`, que es el otro campo ordenable y filtrable. Con `OFFSET` creciente
> el listado se degrada sin que la caché lo evite, porque cada combinación de página es una
> clave distinta. Antes que cachear más de este endpoint: agregar
> `idx_reserva_fecha_creacion (fecha_creacion DESC, id DESC)` e `idx_segmento_fecha_vuelo`.

---

### P2 — `GET /v1/reservations/{id}`, sólo la versión (`ETag`)

**Por qué es el tercero y no el primero:** es el endpoint más llamado —cada frontend
refresca la reserva que el usuario está mirando—, pero su consulta es un lookup por clave
primaria: cara en hidratación (5 tablas), no en búsqueda. Y su cuerpo es exactamente lo que
la restricción prohíbe cachear.

**La jugada:** no cachear el cuerpo, **cachear el número de versión**. El controller ya emite
`ETag` con la versión del agregado. Con la versión en Redis, un pedido con `If-None-Match`
que coincide se responde **`304 Not Modified` con un `GET` a Redis**, sin tocar PostgreSQL,
sin hidratar cinco tablas y sin serializar ni un dato de pasajero.

- **Clave:** `rsv:ver:{id}` · **Valor:** `long` (la versión)
- **Memoria:** ~90 B por entrada. Aun con 100k reservas en caliente son ~9 MB, dentro de un
  free tier típico de 30 MB — y con TTL sólo vive el conjunto caliente real.
- **Sensibilidad:** un entero. No hay nada que proteger.
- **Beneficio:** ahorra la consulta *y* el payload. En un listado que el usuario deja abierto
  con polling, la mayoría de los refrescos son 304.

**Invalidación — y la interacción con el locking optimista.** Éste es el punto donde una
caché mal hecha genera exactamente el `409` que el sistema quiere evitar:

> Si la versión cacheada quedó vieja (dice `7`, la real es `8`), un cliente con
> `If-None-Match: "7"` recibe `304`, conserva su representación vieja, y en el `PUT` manda
> `If-Match: "7"` → **409 evitable**. Es peor que no cachear.

Tres reglas que lo cierran:

1. **La escritura borra la clave, no la actualiza.** `create`, `modify`, `cancel` y `confirm`
   hacen `DEL rsv:ver:{id}` después del commit. Un `DEL` es idempotente y seguro ante fallos
   parciales; un `SET` con un valor equivocado se queda pegado hasta el TTL.
2. **TTL corto igual (60 s)**, como red de seguridad: si una invalidación se pierde
   —proceso caído entre el commit y el `DEL`—, el daño se autolimita a un minuto en lugar
   de ser permanente.
3. **El camino de escritura nunca lee de la caché.** `ModifyReservationService`,
   `CancelReservationService` y `ConfirmReservationService` siguen leyendo con
   `reservationRepository.findById()` dentro de su transacción, contra PostgreSQL. La caché
   sirve únicamente para responder `If-None-Match`. Con eso, el peor caso de una entrada
   desactualizada es **un `200` con cuerpo fresco en vez de un `304`** — se pierde un ahorro,
   no se genera un conflicto.

**Consecuencia de diseño:** por esta regla, el decorador **no puede ir sobre
`ReservationRepositoryPort`**, que usan lectura y escritura por igual. La versión cacheada
tiene que colgar del camino de lectura solamente.

---

### P3 — `PUT` y `DELETE`: no se cachean, pero participan

Son escrituras: no hay nada que cachear en ellas. Su rol en este diseño es doble:

- El `PUT` **se beneficia de P0**: valida aeropuertos con el mismo `AirportExistenceValidator`
  que el alta, así que hereda todo el ahorro del catálogo cacheado.
- Los dos **son responsables de invalidar** `rsv:ver:{id}` (P2).

El `DELETE` es hoy el endpoint más barato del sistema y no necesita nada.

---

## 3. Lo que se decidió NO cachear

| Candidato | Por qué no |
|---|---|
| Cuerpo de `GET /{id}` y de las páginas del listado | Llevan `documentNumber` y `birthDate` de pasajeros: la restricción lo prohíbe de plano |
| Páginas del listado (ids incluidos) | Cardinalidad filtros × página × tamaño × orden contra memoria limitada; hit rate bajo |
| `findByIdempotencyKey` del `POST` | Ya es un hit sobre un índice único, sub-milisegundo. Y cachear el negativo rompería la idempotencia: dos altas simultáneas con la misma clave podrían ver ambas "no existe". El riesgo de corrección no se paga con el ahorro |
| Usuarios (`usuario` por email) | Se resuelve una vez por alta, por índice único. Además el nombre y apellido son datos personales |
| Segmentos y pasajeros resueltos en la escritura | Son parte de una transacción de escritura; cachearlos introduce lecturas obsoletas dentro del commit |
| Outbox y notificaciones | Ya están fuera del camino del pedido: corren en el scheduler |

---

## 4. Lo que una caché no arregla

Para que la prioridad quede en contexto, estos tres puntos tienen mejor relación
costo/beneficio que cualquier entrada de la lista de arriba y no requieren Redis:

1. **Timeouts en el cliente del catálogo** (`spring.http.client`). Sin read timeout, la caché
   baja la *cantidad* de llamadas expuestas pero no acota el daño de la que sí sale.
2. **Índices faltantes**: `reserva(fecha_creacion DESC, id DESC)` y `segmento(fecha_vuelo)`.
3. **`Cache-Control: private, no-store`** en las respuestas de reservas. Como los cuerpos
   llevan PII, hay que impedir explícitamente que un proxy compartido o un CDN los guarde.
   Va en la misma dirección que la decisión de cachear sólo el `ETag` del lado del servidor.

---

## 5. Resumen

| # | Punto | Qué se cachea | Clave | TTL | Invalidación | Memoria |
|---|---|---|---|---|---|---|
| P0 | Catálogo externo (`POST`/`PUT`) | Existe / no existe la ciudad | `catalog:city:{CODE}` | 30 m / 5 m (neg.) | Sólo TTL | ~decenas de KB |
| P1 | `GET /reservations` | El `count` del filtro | `rsv:count:{hash(filtros)}` | 30–60 s | Sólo TTL | ~cientos de KB |
| P2 | `GET /reservations/{id}` | La versión, para `304` | `rsv:ver:{id}` | 60 s | `DEL` post-commit en toda escritura | ~90 B × conjunto caliente |
| — | `PUT` / `DELETE` | Nada | — | — | Invalidan P2 | — |

Total estimado bien por debajo de los 30 MB de un free tier, y sin un solo dato de pasajero
ni de pago en Redis.
