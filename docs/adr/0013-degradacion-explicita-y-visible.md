# 0013 — Cuando una dependencia no está, el sistema degrada y lo dice

- **Estado:** Aceptado
- **Fecha:** 2026-09-25
- **Prompt origen:** [16 — Diseño de la resiliencia](../prompts/16-diseno-de-resiliencia.md)
- **Llevado al código en:** [18 — Implementación de la resiliencia](../prompts/18-implementacion-de-resiliencia.md)
- **Auditoría que origina buena parte de esta decisión:** [`docs/resilience/audit.md`](../resilience/audit.md)
- **Complementa a:** [0012 — Circuit breakers y clasificación de fallos](0012-circuit-breakers-y-clasificacion-de-fallos.md)
- **Custodiado por:** `CatalogResilienceIT`, `ResilienceObservabilityIT`, `CachingAirportCatalogTest`

## Contexto

[0012](0012-circuit-breakers-y-clasificacion-de-fallos.md) decidió cuándo el
sistema deja de llamar a una dependencia. Queda la pregunta que el usuario
efectivamente ve: **qué se responde en ese momento.**

La respuesta que había era desigual y en dos casos directamente engañosa. El
*stale-while-error* del catálogo servía cualquier entrada guardada, **incluidas
las negativas**: con el proveedor caído, un "esa ciudad no existe" cacheado hacía
cinco minutos rechazaba durante dos horas una reserva de una ciudad que sí
existe, y el cliente veía un `400 UNKNOWN_AIRPORT` — un error que le pide
corregir un formulario correcto. Y una respuesta servida con datos viejos salía
exactamente igual que una fresca: ni un header, ni una métrica, ni una línea de
log. Un fallback que no se anuncia es un fallback que nadie sabe que está
actuando, ni el cliente ni quien opera.

## Decisión

**Toda degradación es explícita: escalonada, marcada en la respuesta, contada en
una métrica y registrada en el log. Y donde no hay degradación posible, el pedido
falla con un código honesto en vez de inventar un valor.**

### El catálogo degrada en cuatro escalones

| # | Situación | Qué se hace |
|---|---|---|
| 1 | Entrada fresca en caché | se sirve, sin marca |
| 2 | Entrada vencida, dentro de la ventana de gracia **y positiva** | se sirve *stale* (hasta 2 h 30 m de atraso), con `X-Degraded`, métrica y `WARN` |
| 3 | Entrada *stale* **negativa** | **no se sirve** |
| 4 | Nada guardado | no hay fallback: `503 AIRPORT_CATALOG_UNAVAILABLE` + `Retry-After` |

**La asimetría entre el escalón 2 y el 3 es la decisión.** Los dos errores no
cuestan lo mismo: servir un positivo viejo acepta una reserva contra una ciudad
que casi con seguridad sigue existiendo —el catálogo cambia con frecuencia
mensual—; servir un negativo viejo **rechaza** una reserva legítima y le echa la
culpa al usuario. Ante la duda se falla hacia el lado de aceptar y avisar, no
hacia el de rechazar en silencio.

### Un `503` honesto en lugar de un `400` mentiroso

Cuando no se pudo averiguar si el aeropuerto existe, la respuesta es
`503 AIRPORT_CATALOG_UNAVAILABLE` con `Retry-After`, nunca
`400 UNKNOWN_AIRPORT`. La diferencia no es cosmética: un `400` le dice al cliente
que se equivocó y que no vuelva a intentar lo mismo; un `503` con `Retry-After`
le dice que el problema es nuestro y cuándo reintentar.

### El fallback no miente en silencio

Tres registros, y los tres los escribe el mismo lugar (`DegradationRecorder`)
para que no puedan separarse:

- **`X-Degraded: airport-catalog`** en la respuesta, expuesto también en la
  allowlist de CORS: si el frontend no lo puede leer, no puede avisar que lo que
  está mostrando puede estar viejo.
- **Una métrica** de respuestas degradadas, etiquetada por dependencia.
- **Un `WARN`** con la antigüedad del dato, pasado por el enmascarado de PII.

### Dónde **no** hay fallback, y es deliberado

| Dependencia | Qué pasa |
|---|---|
| **PostgreSQL** | El pedido falla. Es el sistema de registro: no hay dato viejo que servir ni respuesta parcial honesta |
| **IdP / JWKS** | El pedido falla con `401`/`500`. Aceptar un token sin validarlo no es degradar |
| **Notificaciones** | El fallback es **operativo, no automático**: la dead letter y su endpoint de replay. Alguien la drena |

### Alternativas descartadas

| Alternativa | Por qué se descartó | Qué la volvería a poner sobre la mesa |
|---|---|---|
| **Servir también las entradas negativas vencidas** | Es lo que había. Convierte una caída del proveedor en el rechazo de reservas válidas durante dos horas, con un mensaje que culpa al usuario | Nada |
| **Aceptar la reserva sin validar los aeropuertos** cuando el catálogo no responde | Es el fallback más generoso y produce reservas contra códigos inexistentes que después hay que corregir a mano, en un sistema donde el itinerario ya se cobró | Una validación diferida con un proceso de corrección y un estado de reserva «pendiente de validación»: es un cambio de negocio, no de resiliencia |
| **Devolver `200` con el dato viejo y sin marcarlo** | Es lo que hacía. Nadie —ni el cliente ni quien opera— puede distinguir un sistema sano de uno degradado | Nada |
| **Ventana de gracia ilimitada** | Un dato indefinidamente viejo deja de ser una degradación y pasa a ser un catálogo propio desactualizado que nadie decidió tener | Nada; si el dato es tan estable, corresponde replicarlo a propósito ([0009](0009-integracion-con-el-catalogo-externo.md)) |
| **Circuito y fallback en PostgreSQL** | No existe una respuesta degradada honesta para "no sé si tu reserva se guardó" | Réplicas de lectura: un `GET` sí podría degradar |

## Consecuencias

### A favor

- **Una caída del catálogo deja de rechazar reservas válidas**, y eso se prueba:
  `CatalogResilienceIT` baja el proveedor y verifica que el alta sigue saliendo
  con `X-Degraded` y que un negativo vencido **no** se sirve.
- **El cliente puede reaccionar.** Un `503` con `Retry-After` es reintentable por
  el frontend sin intervención humana; un `400` no lo es.
- **Se puede contestar "¿cuántas respuestas salieron degradadas?"** con un número
  y mostrárselo a producto para decidir si el fallback es aceptable.
- **La degradación es un estado observable, no una hipótesis.** Aparece en la
  respuesta, en una métrica y en el log, y los tres se escriben juntos.

### En contra, y asumido

- **Se puede crear una reserva contra un dato de hasta 2 h 30 m de antigüedad.**
  Si en esa ventana una ciudad dejó de operar, la reserva se crea contra un
  aeropuerto que ya no existe. Es el riesgo aceptado, y la ventana es el
  parámetro con el que se regula.
- **`X-Degraded` es un header no estándar** que cada cliente tiene que aprender y
  que la mayoría va a ignorar. Sin un frontend que lo muestre, el usuario no se
  entera de nada.
- **El `503` es un error para el usuario igual.** Cuando no hay nada cacheado —el
  arranque en frío con el catálogo caído— la reserva no se puede crear, y la
  honestidad del código no lo arregla.
- **Tres registros por cada respuesta degradada** son volumen extra justo durante
  un incidente, que es cuando más logs hay. El `WARN` es por respuesta, no por
  ciudad, para acotarlo.
- **La dead letter de notificaciones sigue necesitando una persona.** Es el único
  fallback del sistema que no se resuelve solo, y está asumido desde
  [0005](0005-garantias-de-entrega-y-remediacion-de-la-mensajeria.md).
