# 0002 — Modelo relacional normalizado hasta 3FN sobre PostgreSQL

- **Estado:** Aceptado — dos de sus restricciones fueron revisadas por [0003](0003-autenticacion-autorizacion-y-datos-sensibles.md)
- **Fecha:** 2026-09-25
- **Prompt origen:** [04 — Modelo de datos normalizado hasta 3FN](../prompts/04-modelo-de-datos-3fn.md)
- **Llevado al código en:** [05 — Persistencia y dominio](../prompts/05-persistencia-y-dominio.md)
- **Custodiado por:** `src/main/resources/db/migration/V1__esquema_inicial.sql`, `ReservationPersistenceAdapterIT`

> Redactado retroactivamente el 2026-09-25. El número refleja el orden en que se
> tomó la decisión, no el orden en que se escribió el documento.

## Contexto

El dominio tenía cinco entidades y una relación que no era obvia: **los segmentos
se repiten entre itinerarios**. El vuelo EZE→MAD de un martes es el mismo vuelo
para todos los que lo reserven, y el itinerario es una secuencia ordenada de esos
vuelos con un precio propio. Una reserva tiene un itinerario, N pasajeros y
pertenece a un único usuario.

Los números del problema eran chicos y conocidos: hasta 100 000 registros por
entidad. Lo que no era chico era la concurrencia: **el sistema tenía que soportar
escrituras concurrentes sin generar reservas duplicadas**, y eso desde el día uno,
no como una preocupación hipotética.

El modelo de dominio que existía en ese momento (Reserva, Vuelo, Pasajero, Asiento)
no coincidía con ninguna de estas entidades: era el esqueleto de
[0001](0001-arquitectura-hexagonal-en-un-modulo.md) con nombres de ejemplo.

## Decisión

**Siete tablas en PostgreSQL, normalizadas hasta 3FN, con la unicidad y la
concurrencia resueltas por restricciones de la base y no por código.**

```
usuario ──< reserva >── itinerario ──< itinerario_segmento >── segmento
               │                             (orden)
               └──< reserva_pasajero >── pasajero
```

Las cuatro decisiones que cargan el peso:

1. **El segmento es una entidad compartida**, con
   `UNIQUE (origen, destino, aerolinea, fecha_vuelo)`. Dos itinerarios que pasan
   por el mismo vuelo apuntan a la misma fila. Es lo que evita que cambiar el
   horario de un vuelo haya que propagarlo a N copias.
2. **El orden del itinerario vive en la tabla intermedia**, no en el segmento:
   `itinerario_segmento` tiene `PRIMARY KEY (itinerario_id, orden)`. El mismo
   vuelo puede ser el primer tramo de un itinerario y el segundo de otro.
3. **El precio es del itinerario**, no de la reserva ni del segmento. Depende de
   la combinación completa de tramos, que es la definición de dependencia
   funcional de la clave del itinerario: ponerlo en otro lado es lo que rompe la
   3FN.
4. **Las dos garantías de concurrencia son de la base**:
   `UNIQUE (idempotency_key)` sobre `reserva` —dos altas simultáneas con la misma
   clave producen una sola reserva, y la que pierde recibe una violación de
   unicidad, no una segunda reserva— y una columna `version` para locking
   optimista. Nada de `SELECT ... FOR UPDATE` ni de locks de aplicación: con dos
   instancias, un lock en memoria no es un lock.

**PostgreSQL y no otro motor.** Relacional porque el modelo lo es y porque las
dos garantías de arriba son restricciones declarativas; PostgreSQL en concreto
porque corre en `compose.yaml` sin plan pago, tiene capa gratuita en varios
proveedores administrados, y porque las piezas que vinieron después —el outbox
con `FOR UPDATE SKIP LOCKED` ([0004](0004-mensajeria-asincronica-y-broker.md)) y
el trigger *append-only* de auditoría
([0003](0003-autenticacion-autorizacion-y-datos-sensibles.md))— se apoyan en
primitivas que ya estaban ahí.

### Alternativas descartadas

| Alternativa | Por qué se descartó | Qué la volvería a poner sobre la mesa |
|---|---|---|
| **Desnormalizar el itinerario** (guardar los segmentos como JSON en la reserva) | Menos joins y menos tablas, pero cada reserva tendría su copia del vuelo y una corrección de horario sería un `UPDATE` masivo con `jsonb`. Con 100 k registros el join no es el problema | Que el itinerario pase a ser una foto histórica inmutable —el día que haya que conservar lo que el cliente compró aunque el vuelo cambie—; ahí conviven las dos formas, no se reemplaza una por otra |
| **Locking pesimista (`SELECT … FOR UPDATE`)** | Serializa a los clientes que leen y escriben la misma reserva, que en la práctica son el titular y nadie más. El conflicto real es raro; pagar un lock en cada escritura para el caso raro es al revés | Contención medida sobre una misma fila, que hoy no existe: una reserva la toca su dueño |
| **Una base documental (MongoDB)** | La reserva se leería en un solo documento, pero el segmento compartido y la unicidad de la clave de idempotencia entre documentos hay que escribirlos a mano. La garantía que más importaba era justamente la que una restricción declarativa da gratis | Que el modelo deje de tener relaciones que importen y el volumen crezca dos órdenes de magnitud |
| **Clave natural en `pasajero`** (`UNIQUE (documento)`) | **Se adoptó y después se revirtió.** Deduplicaba pasajeros entre reservas, y de paso convertía el alta en un oráculo: mandando un documento ajeno, el `201` devolvía el nombre real de su titular. Lo quitó [0003](0003-autenticacion-autorizacion-y-datos-sensibles.md) (V2, T-06) | La reconciliación de identidades sigue siendo deseable **como proceso interno**, nunca como efecto observable del alta |

## Consecuencias

### A favor

- **Dos altas simultáneas con la misma `Idempotency-Key` producen una sola
  reserva**, y la garantía no depende de que el código acierte el orden de las
  operaciones: la da un índice único. `ReservationPersistenceAdapterIT` lo
  ejercita contra un PostgreSQL real con Testcontainers.
- **Una modificación sobre una lectura vieja falla en lugar de pisar.** La columna
  `version` es lo que después permitió exponer el conflicto al cliente como
  `ETag`/`If-Match` ([0008](0008-concurrencia-optimista-e-idempotencia-en-el-protocolo.md))
  en vez de resolverlo con un *last write wins* silencioso.
- **Corregir el horario de un vuelo es un `UPDATE` de una fila**, y se ve reflejado
  en todos los itinerarios que lo incluyen.
- **El esquema documenta sus propias reglas.** `CHECK (origen <> destino)`,
  `CHECK (precio >= 0)` y `CHECK (estado IN (…))` están en el DDL con un
  `COMMENT ON CONSTRAINT` que explica para qué está cada uno.

### En contra, y asumido

- **Leer una reserva completa son cinco tablas.** El `GET /v1/reservations/{id}`
  hidrata `reserva → itinerario → itinerario_segmento → segmento` más
  `reserva_pasajero → pasajero`. Es caro en hidratación, no en búsqueda, y es lo
  que llevó a cachear la **versión** y no el cuerpo
  ([0011](0011-alcance-de-la-cache.md)).
- **El listado paga el join dos veces**, una para la página y otra para el
  `count`, y ordena por un campo sin índice. Está anotado como deuda en
  [`docs/performance/cache-bottlenecks.md`](../performance/cache-bottlenecks.md)
  §P1: faltan `idx_reserva_fecha_creacion (fecha_creacion DESC, id DESC)` e
  `idx_segmento_fecha_vuelo`. La caché del total tapa el síntoma y no el problema,
  y eso está dicho ahí.
- **El locking optimista traslada el conflicto al cliente.** Un `409` no es un
  error del sistema: es trabajo que el frontend tiene que saber rehacer. Si el
  cliente no relee y reintenta, el usuario ve un error y no entiende por qué.
- **El modelo no versiona el itinerario.** Si un segmento cambia de horario, las
  reservas ya creadas cambian con él: no queda registro de lo que el cliente
  compró. Es una limitación conocida y la única alternativa es la desnormalización
  que se descartó arriba.
- **Los `BIGSERIAL` son secuenciales y quedaron expuestos en la API.** En el
  modelo es lo correcto; en el borde HTTP resultó ser enumerable, y lo tuvo que
  cerrar [0003](0003-autenticacion-autorizacion-y-datos-sensibles.md) con
  autorización por recurso. Los ids siguen siendo secuenciales: la defensa es la
  autorización, no la opacidad del identificador.
