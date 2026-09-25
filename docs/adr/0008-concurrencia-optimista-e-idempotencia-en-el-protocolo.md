# 0008 — La concurrencia optimista y la idempotencia se expresan en el protocolo HTTP

- **Estado:** Aceptado
- **Fecha:** 2026-09-25
- **Prompt origen:** [07 — Adaptador REST de entrada](../prompts/07-adaptador-rest-entrada.md)
- **Se apoya en:** [0002 — Modelo relacional en 3FN](0002-modelo-relacional-3fn-en-postgresql.md)
- **Custodiado por:** `ReservationControllerTest`, `ReservationApiIT`, `EntityVersionTest`

> Redactado retroactivamente el 2026-09-25. El número refleja el orden en que se
> tomó la decisión, no el orden en que se escribió el documento.

## Contexto

[0002](0002-modelo-relacional-3fn-en-postgresql.md) resolvió las dos carreras del
lado de la base: una columna `version` para el locking optimista y un índice
único sobre la clave de idempotencia. Lo que quedaba abierto era cómo se ven esas
dos garantías desde afuera.

Los casos de uso ya hablaban de las dos: `CreateReservationCommand` recibe una
`idempotencyKey` que genera el cliente, y los comandos de modificación reciben un
`expectedVersion` que el cliente leyó antes. Pero eso son parámetros de un método
Java. La pregunta del adaptador era en qué parte del pedido HTTP viajan, y la
respuesta fácil —un campo más en el cuerpo JSON— tiene un costo: inventa un
vocabulario propio para dos problemas que HTTP ya resolvió, y obliga a cada
cliente nuevo a aprenderlo leyendo nuestra documentación en lugar de la de HTTP.

## Decisión

**Las dos garantías viajan en headers estándar, no en el cuerpo.**

| Garantía | Cómo viaja | Qué pasa si no coincide |
|---|---|---|
| Idempotencia del alta | `Idempotency-Key` en el `POST`, **obligatorio**, generado por el cliente | Dos pedidos con la misma clave producen una sola reserva; el perdedor de la carrera recibe `409` |
| Concurrencia optimista | `ETag` en toda respuesta que devuelve una reserva; `If-Match` **obligatorio** en `PUT`, `DELETE` y confirmación | Versión distinta de la almacenada → `409 CONCURRENT_UPDATE` |
| Lectura condicional | `If-None-Match` en el `GET` por id, **opcional y tolerante** | Coincide → `304` sin cuerpo |

Los detalles que no son adorno:

- **El `ETag` es la versión del agregado**, no un hash del cuerpo. `EntityVersion`
  lo serializa y lo parsea, y acepta la forma débil (`W/"7"`) porque hay proxies
  que reescriben así el `ETag` que ellos mismos reenvían.
- **La asimetría entre `If-Match` e `If-None-Match` es deliberada.** El primero es
  obligatorio y estricto: un valor que no se entiende tiene que fallar, porque de
  él depende no pisar el trabajo de otro. El segundo es opcional y tolerante: lo
  peor que pasa si se ignora es responder `200` con el cuerpo fresco en vez de
  `304`, o sea perder un ahorro.
- **El servidor no guarda el `ETag` del cliente.** Quien tiene que conservarlo es
  quien va a mandar el `If-Match`, y eso es estado de la aplicación cliente.
- **La clave de idempotencia es única por `(usuario, clave)`**, no global. Eso lo
  decidió después [0003](0003-autenticacion-autorizacion-y-datos-sensibles.md)
  (T-16): una clave filtrada en el log de un proxy no sirve desde otra identidad.
- **El conflicto se devuelve, no se resuelve.** Un `409` es el sistema diciendo
  "leíste algo viejo, releé"; nunca se aplica un *last write wins* silencioso.

### Alternativas descartadas

| Alternativa | Por qué se descartó | Qué la volvería a poner sobre la mesa |
|---|---|---|
| **`version` como campo del cuerpo JSON** | Inventa un mecanismo propio para algo que HTTP tiene estandarizado, no lo entienden los caches ni los proxies intermedios, y no sirve para el `DELETE`, que no tiene cuerpo | Nada |
| **`If-Match` opcional** | Un cliente que se lo olvida vuelve al *last write wins* sin enterarse, y el bug aparece recién bajo concurrencia. Obligatorio, el que se lo olvida falla siempre y lo arregla el primer día | Nada |
| **Locking pesimista en el endpoint** | Ya descartado en [0002](0002-modelo-relacional-3fn-en-postgresql.md): serializa a clientes que en la práctica no compiten | Contención medida sobre la misma reserva |
| **Idempotencia deducida del contenido** (hash del cuerpo del `POST`) | Dos reservas legítimamente idénticas —la misma persona comprando dos veces el mismo vuelo para dos pasajeros distintos en pedidos separados— colapsarían en una. La clave tiene que ser del cliente porque sólo él sabe si es un reintento | Nada |
| **Clave de idempotencia con expiración y respuesta cacheada** (la reserva original se reenvía en el reintento) | Es el comportamiento que describe el borrador de IETF y es mejor que un `409`, pero exige guardar la respuesta del alta —que lleva datos de pasajeros— con su política de retención. Se descartó por eso, no por complejidad | Un almacén de respuestas con cifrado y retención acotada; el cambio es compatible hacia atrás |
| **`428 Precondition Required` cuando falta `If-Match`** | Está definido para exactamente este caso y quedó fuera del conjunto de códigos que el contrato acordó (200/201/400/404/409). Se dejó como estaba | Una revisión del contrato; es un cambio de una línea en el handler y otra en el documento |

## Consecuencias

### A favor

- **Un cliente que ya sabe HTTP no tiene que aprender nada nuestro** para no pisar
  datos ajenos ni duplicar reservas.
- **El `304` salió gratis.** Como el `ETag` ya se emitía, soportar
  `If-None-Match` fue agregar una rama en el controller — y habilitó cachear
  únicamente la versión, que es la única parte de una reserva sin datos
  personales ([0011](0011-alcance-de-la-cache.md)).
- **La reserva no se duplica aunque el cliente reintente**, y la garantía es de la
  base, no del orden en que corra el código.

### En contra, y asumido

- **El cliente tiene más trabajo, y si no lo hace ve errores.** Tiene que generar
  un UUID por intento de alta, conservar el `ETag` de cada reserva que muestra y
  releer cuando recibe un `409`. Un frontend que no implemente el reintento deja
  al usuario con un error que no entiende.
- **El reintento del alta con la misma clave devuelve `409`, no la reserva
  original.** Es correcto —no se crea nada— pero es peor respuesta que reenviar el
  recurso, y obliga al cliente a hacer un `GET` para saber qué pasó.
- **El `ETag` filtra el número de versión de la reserva.** Es un entero que revela
  cuántas veces se modificó. Se aceptó: es información sobre un recurso que el
  solicitante ya está autorizado a ver.
- **Un `ETag` servido desde caché puede generar un `409` evitable**, y ése es el
  modo de falla que dominó el diseño de la caché: por eso el camino de escritura
  nunca lee de caché y la invalidación es un `DEL` y no un `SET`
  ([0011](0011-alcance-de-la-cache.md)).
- **`Cache-Control: private, no-store` en las respuestas con cuerpo.** Las
  peticiones condicionales conviven con la prohibición de que un proxy guarde
  datos de pasajeros: el ahorro es del servidor, no del intermediario.
