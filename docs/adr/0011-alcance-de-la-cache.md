# 0011 — Qué se cachea: sólo datos sin PII, con TTL positivo y negativo

- **Estado:** Aceptado — el *stale-while-error* lo acotó [0013](0013-degradacion-explicita-y-visible.md)
- **Fecha:** 2026-09-25
- **Prompt origen:** [09 — Detección de cuellos de botella para caché](../prompts/09-cuellos-de-botella-cache.md)
- **Diseño completo:** [`docs/performance/cache-bottlenecks.md`](../performance/cache-bottlenecks.md)
- **Complementa a:** [0010 — La caché es distribuida, opcional y vive en decoradores](0010-cache-distribuida-opcional-con-fallback-en-memoria.md)
- **Custodiado por:** `CacheIT`, `CachingAirportCatalogTest`, `CachingReservationSearchQueryTest`, `ReservationVersionCacheTest`

> Redactado retroactivamente el 2026-09-25. El número refleja el orden en que se
> tomó la decisión, no el orden en que se escribió el documento.

## Contexto

[0010](0010-cache-distribuida-opcional-con-fallback-en-memoria.md) decidió *dónde*
vive la caché. Esta decisión es la otra mitad: **qué entra**, y es la que estaba
apretada por las restricciones.

Dos límites, y ninguno negociable. El primero es la memoria: free tier, del orden
de 30 MB, así que no se puede cachear "lo que ande". El segundo es más duro —**no
se cachean datos sensibles de pasajeros ni de pago, en ninguna capa**— y en ese
momento tenía una razón concreta y no formal: la aplicación **todavía no tenía
capa de seguridad**. Cualquier entrada cacheada era legible por cualquiera que
alcanzara el endpoint.

Los tres endpoints que más pesaban no coincidían con los que más se podían
cachear. El `GET /{id}` es el más llamado y su cuerpo es exactamente lo que está
prohibido guardar. El listado es el más caro y sus páginas llevan datos de hasta
100 reservas por entrada. Y la única dependencia de red del camino de escritura
—el catálogo— no es un endpoint nuestro.

Encima, el locking optimista pone una trampa: una lectura servida desde una
caché desactualizada hace que el cliente mande un `If-Match` viejo y coma un
`409` **evitable**. Una caché mal hecha acá es peor que no tener caché.

## Decisión

**Se cachean tres cosas, y ninguna es un dato personal: la existencia de una
ciudad, el total del listado y el número de versión de una reserva.**

| # | Qué se cachea | Clave | TTL | Invalidación |
|---|---|---|---|---|
| P0 | La ciudad existe / no existe | `catalog:city:{CODE}` | 30 m positivo, **5 m negativo** | sólo TTL |
| P1 | El `count(criteria)` del listado | `rsv:count:{hash(filtros)}` | 45 s | sólo TTL |
| P2 | La versión de la reserva, para responder `304` | `rsv:ver:{id}` | 60 s | `DEL` después del commit de toda escritura |

Las cuatro reglas que hacen que esto sea correcto y no sólo rápido:

1. **Dos TTL para el catálogo, y el negativo es más corto.** Una ciudad que hoy
   no existe puede darse de alta mañana, y el costo de equivocarse en esa
   dirección es rechazar una reserva válida. Cachear sólo los positivos dejaría
   sin protección el caso que más golpea al proveedor: una ráfaga de códigos mal
   tipeados.
2. **Ante un fallo del catálogo se sirve el último valor conocido**
   (*stale-while-error*, ventana de 2 h). Convierte una caída del proveedor en
   una degradación en lugar de un rechazo de reservas.
3. **El camino de escritura nunca lee de la caché.** `Modify`, `Cancel` y
   `Confirm` leen con `findById()` dentro de su transacción, contra PostgreSQL. La
   versión cacheada sirve **únicamente** para responder `If-None-Match`. Con eso,
   el peor caso de una entrada vieja es un `200` con cuerpo fresco en vez de un
   `304`: se pierde un ahorro, no se genera un conflicto. Consecuencia de diseño:
   el decorador **no puede ir sobre `ReservationRepositoryPort`**, que usan
   lectura y escritura por igual.
4. **La escritura borra la clave, no la actualiza.** Un `DEL` es idempotente y
   seguro ante fallos parciales; un `SET` con un valor equivocado se queda pegado
   hasta que expire. El TTL corto es la red por si el `DEL` se pierde.

**Lo que se decidió NO cachear, con el motivo escrito:** el cuerpo del `GET /{id}`
y las páginas del listado (llevan documento y fecha de nacimiento); los usuarios
(nombre y apellido son datos personales, y ya se resuelven por índice único); y
`findByIdempotencyKey` —cachear su negativo rompería la idempotencia: dos altas
simultáneas con la misma clave podrían ver ambas «no existe»—.

### Alternativas descartadas

| Alternativa | Por qué se descartó | Qué la volvería a poner sobre la mesa |
|---|---|---|
| **Cachear el cuerpo de `GET /{id}`** | Es el endpoint más llamado y sería el mayor ahorro. Lleva documento y fecha de nacimiento de cada pasajero: prohibido de plano. Cachear la versión captura buena parte del beneficio sin guardar un solo dato personal | Cifrado de la entrada con la misma clave que la columna y una política de retención propia; el ahorro no justifica hoy esa maquinaria |
| **Cachear las páginas del listado** | Dos razones independientes, cada una suficiente: cardinalidad explosiva (filtros × página × tamaño × orden) contra memoria limitada, y PII de hasta 100 reservas por entrada | Que el listado devuelva sólo ids y el detalle se pida aparte |
| **Cachear la reserva completa e invalidar por evento** | Coherencia por invalidación activa sobre un dato con PII: se junta lo peor de los dos mundos | Nada |
| **`SET` de la nueva versión en lugar de `DEL`** | Escribe el valor correcto el 99 % de las veces y deja pegado un valor incorrecto el 1 % restante, que es el que genera el `409` evitable | Nada |
| **TTL único para positivos y negativos** | Un TTL largo para los negativos retrasa el alta de una ciudad nueva; uno corto para los positivos tira a la basura el dato que más se repite | Nada |
| **Cachear `findByIdempotencyKey`** | Rompe una garantía de corrección para ahorrar un lookup sub-milisegundo sobre un índice único | Nada |

## Consecuencias

### A favor

- **No hay un solo dato de pasajero ni de pago en Redis.** Es verificable por
  inspección: las tres claves guardan un booleano, un `long` y un `long`.
- **El presupuesto de memoria entra holgado.** Decenas de KB el catálogo,
  cientos de KB los totales, ~90 B por versión: muy por debajo de los 30 MB del
  free tier, incluso con el conjunto caliente completo.
- **La caché no introduce `409`.** La regla 3 lo garantiza por construcción, no
  por cuidado: el camino de escritura no tiene forma de leer la caché.
- **Una caída del catálogo deja de rechazar reservas** mientras haya un valor
  conocido, y eso es lo que después se volvió la política de degradación formal
  ([0013](0013-degradacion-explicita-y-visible.md)).

### En contra, y asumido

- **El endpoint más llamado sigue yendo a la base cuando cambia.** Sólo se ahorra
  el caso `304`; un `GET` sin `If-None-Match`, o de una reserva que cambió, hidrata
  las cinco tablas igual.
- **El total del listado puede mentir hasta 45 segundos.** Que diga 1.204 cuando
  son 1.205 es aceptable para paginar y no lo sería para nada contable. Está
  documentado y es la razón por la que no hay invalidación activa: no se puede
  saber qué filtros matchea una reserva nueva sin recorrer las claves.
- **La caché del listado tapa un problema que sigue ahí.** Faltan
  `idx_reserva_fecha_creacion` e `idx_segmento_fecha_vuelo`; con `OFFSET`
  creciente el listado se degrada igual, porque cada página es una clave distinta.
  Deuda explícita, anotada en el documento de cuellos de botella.
- **El *stale-while-error* puede servir un dato de hasta 2 h.** Es la ventana más
  larga del sistema y se aceptó porque el catálogo de ciudades cambia con
  frecuencia mensual. [0013](0013-degradacion-explicita-y-visible.md) la acotó
  después: sólo se sirven entradas **positivas**, y la respuesta lo dice.
- **Una invalidación perdida deja un `ETag` viejo hasta 60 s.** El proceso puede
  morir entre el commit y el `DEL`. El daño se autolimita al TTL y, por la regla
  3, nunca pasa de un `304` de más.
