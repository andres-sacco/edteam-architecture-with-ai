# 0014 — El pedido tiene un presupuesto de tiempo, y cada techo está declarado

- **Estado:** Aceptado
- **Fecha:** 2026-09-25
- **Prompt origen:** [16 — Diseño de la resiliencia](../prompts/16-diseno-de-resiliencia.md)
- **Llevado al código en:** [18 — Implementación de la resiliencia](../prompts/18-implementacion-de-resiliencia.md)
- **Auditoría que origina esta decisión:** [`docs/resilience/audit.md`](../resilience/audit.md) · **Medición:** [`docs/resilience/implementation.md`](../resilience/implementation.md) §4
- **Custodiado por:** `LatencyBudgetTest`, `BudgetedCityCatalogFanoutTest`, `BulkheadCityCatalogClientTest`, `HexagonalArchitectureTest#transactionalMethodsDeclareATimeout`

## Contexto

Cada dependencia tenía su techo y el pedido completo no tenía ninguno. La suma
daba un número que nadie había calculado, y la auditoría lo calculó: **un `POST`
con once ciudades y el catálogo colgado tardaba ≈ 91 s**, generando 33 pedidos al
proveedor por una sola acción del usuario. El `PUT`, ≈ 94 s.

Los dos números están más allá de cualquier referencia razonable. El apagado
elegante de la aplicación es de 25 s: un pedido de 91 s no termina, lo corta el
proceso al reiniciar. Un proxy típico corta a los 60 s. Y el cliente —una persona
mirando un formulario— abandona muchísimo antes, y reintenta, multiplicando la
carga contra un sistema que ya está en problemas.

Tres causas concretas: las ciudades se resolvían **en serie**, cada una con su
propio techo y sin ninguno compartido; la lectura de la caché también era en
serie, una llamada a Redis por ciudad; y varias transacciones no declaraban
`timeout`, así que una consulta lenta no tenía corte.

## Decisión

**Un presupuesto de tiempo para la validación del itinerario, resuelto con
fan-out en hilos virtuales, y un techo declarado en cada tramo del pedido.**

1. **`itinerary-budget: 1600ms`, compartido por todas las ciudades.**
   `BudgetedCityCatalogFanout` reparte las resoluciones y todas compiten contra
   el mismo reloj. Once ciudades cuestan 1,6 s, no once veces el peor caso de
   una. Agotado el presupuesto, lo que falta se resuelve con lo que haya en
   caché o el pedido falla como en el escalón 4 de
   [0013](0013-degradacion-explicita-y-visible.md).
2. **El fan-out va en hilos virtuales**, que ya están encendidos. Paralelizar no
   cuesta un pool: es el único motivo por el que esto es barato.
3. **El reintento consulta el presupuesto antes de existir.** `RetryingCityCatalogClient`
   sólo reintenta si lo que queda alcanza para un intento completo. Un reintento
   que arranca sabiendo que no va a llegar es puro daño.
4. **Bulkhead de 50 llamadas concurrentes al catálogo.** El presupuesto acota el
   tiempo de **un** pedido; el bulkhead acota cuántas llamadas nuestras puede
   haber en vuelo contra el proveedor cuando hay muchos pedidos a la vez.
5. **La caché se lee de a una sola vez.** Las once claves de ciudad salen en un
   `MGET`: 200 ms en lugar de 2 200 ms, y es el cambio más barato de toda la lista.
6. **Todo método transaccional declara su techo**, y hay una regla de ArchUnit
   que rompe el build si alguno no lo hace. Además, `statement_timeout` en el
   driver y `connection-timeout: 1s` en el pool (baja de 3 s).
7. **La cuenta es un test.** `LatencyBudgetTest` recalcula el presupuesto desde
   los valores configurados y falla el día que alguien cambie un timeout sin
   rehacer la suma. Un presupuesto que vive en un documento se desactualiza en el
   primer cambio.

Resultado medido, en el peor caso: **`POST` ≈ 4,1 s** (≈ 1,5 s con el circuito
del catálogo abierto) y **`PUT` ≈ 7,1 s**, contra 91 s y 94 s. Los pedidos reales
al catálogo por cada pedido del usuario pasan de 33 a **≤ 11**, y a **0** con el
circuito abierto.

### Alternativas descartadas

| Alternativa | Por qué se descartó | Qué la volvería a poner sobre la mesa |
|---|---|---|
| **Bajar los timeouts individuales hasta que la suma cierre** | Con once ciudades en serie, cerrar en 4 s exige ~360 ms por ciudad, que es menos de lo que el proveedor tarda cuando está sano. Se recortaría el caso normal para acotar el peor | Un itinerario de tamaño acotado por contrato |
| **Limitar la cantidad de tramos del itinerario** | Resuelve la aritmética cambiando el producto. Un ida y vuelta con escala son ocho ciudades y es un caso legítimo | Que aparezca un límite de negocio real |
| **Validar los aeropuertos de forma asincrónica**, después de responder | Sacaría el catálogo del camino del pedido, y crearía reservas contra códigos inexistentes que habría que corregir después, con el itinerario ya cobrado. Es un cambio de negocio disfrazado de mejora técnica | Un estado «pendiente de validación» en la reserva y un proceso de corrección |
| **Un pool de hilos dedicado al fan-out** | Los hilos virtuales ya están encendidos: un pool agrega un recurso que dimensionar y otro que se puede agotar | Que el fan-out pase a hacer trabajo bloqueante que los hilos virtuales no absorban |
| **Un timeout global de pedido en el servidor web** | Corta el pedido pero no libera la conexión de base ni cancela la llamada en vuelo: el trabajo sigue corriendo y el recurso sigue tomado. Corta el síntoma, no el consumo | Complemento, no reemplazo: sigue siendo razonable agregarlo por encima |

## Consecuencias

### A favor

- **El peor caso pasó de 91 s a 4,1 s**, y de 33 pedidos al proveedor a once. El
  pedido ahora entra con margen en el apagado elegante de 25 s y muy por debajo
  del corte de un proxy.
- **El presupuesto no se puede desactualizar en silencio**: cambiar un timeout
  sin rehacer la cuenta deja `LatencyBudgetTest` en rojo.
- **Un itinerario grande ya no es proporcionalmente peor**: once ciudades cuestan
  lo mismo que dos en tiempo de reloj.
- **Una transacción sin techo no compila el build.** La regla de ArchUnit cierra
  la clase entera de problema, no el caso que la auditoría encontró.

### En contra, y asumido

- **El `POST` cierra en 4,1 s contra un objetivo de 4 s, y el `PUT` en 7,1 s
  contra 4,5 s.** No se cumple, está medido y está dicho. Los 100 ms del `POST`
  son la escritura de caché, que todavía se hace en línea; los 2,6 s del `PUT`
  son la lectura previa, que pide conexión al pool una segunda vez. Los dos
  techos están declarados como constantes en `LatencyBudgetTest`, así que
  recortarlos es cambiar un número y ver qué falla.
- **Por eso no hay alerta de latencia para el `PUT`.** Una alerta contra un techo
  que el sistema no cumple suena siempre, y una alerta que suena siempre deja de
  leerse. El `PUT` entra al panel; la alerta se escribe el día que los 2,6 s se
  recorten ([0016](0016-metricas-con-cardinalidad-acotada-y-alertas.md)).
- **Un presupuesto agotado rechaza pedidos que habrían terminado bien.** Es la
  definición de un techo duro: se elige fallar rápido antes que tarde.
- **El fan-out paralelo multiplica la carga instantánea sobre el proveedor.**
  Once llamadas simultáneas en lugar de once consecutivas; por eso el bulkhead
  existe y por eso el `429` cuenta para el circuito
  ([0012](0012-circuit-breakers-y-clasificacion-de-fallos.md)).
- **La concurrencia hace el diagnóstico más difícil**, y ésa es exactamente la
  razón por la que después se agregaron trazas: con ocho resoluciones en paralelo
  contra un reloj compartido, saber cuál consumió el presupuesto no se contesta
  leyendo logs ([0017](0017-trazas-distribuidas-con-alcance-acotado.md)).
