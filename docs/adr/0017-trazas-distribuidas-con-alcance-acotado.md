# 0017 — Trazas distribuidas, con un alcance chico y explícito

- **Estado:** Aceptado
- **Fecha:** 2026-09-25
- **Prompt origen:** [19 — Diseño de la observabilidad](../prompts/19-diseno-de-observabilidad.md)
- **Llevado al código en:** [21 — Implementación de la observabilidad](../prompts/21-implementacion-de-observabilidad.md)
- **Diseño completo:** [`docs/observability/design.md`](../observability/design.md) §5
- **Se apoya en:** [0014 — Presupuesto de latencia del pedido](0014-presupuesto-de-latencia-del-pedido.md)
- **Custodiado por:** `ObservabilityIT`, `MdcPropagationTest`

## Contexto

La objeción obvia a las trazas en este sistema es correcta para casi todo:
**es un solo servicio, el `correlationId` alcanza**. Para el `GET` por id, para
el listado y para cualquier camino que sólo toque la base, un par de líneas de
log y los gauges del pool contestan todo.

Hay un caso donde no alcanza, y es el que más duele.
[0014](0014-presupuesto-de-latencia-del-pedido.md) hizo que un `POST` con escala
resuelva hasta once ciudades **en paralelo, en hilos virtuales, contra un
presupuesto de tiempo compartido**, y cada una atraviesa cuatro decoradores más
una caché con ventana de gracia. Cuando ese `POST` tarda 3,2 s, la pregunta
operativa es *cuál de las once consumió el presupuesto y en qué decorador se fue
el tiempo*. Con logs eso son once líneas sin orden garantizado entre sí y sin
duración individual: hay que reconstruir el paralelismo a mano leyendo
timestamps. Con una traza es un gráfico de barras.

El segundo caso, más chico: el salto `pedido → outbox → relay → broker →
consumidor` hoy se sigue por `correlationId`, que dice *que* pasó pero no
*cuánto tardó cada tramo*.

## Decisión

**Sí a las trazas, con Micrometer Tracing sobre OpenTelemetry y exportación
OTLP, y con un alcance acotado a los caminos donde una traza contesta algo que
las otras dos patas no contestan.**

1. **El alcance es chico y está escrito.** Hay span para el pedido HTTP, para el
   fan-out del itinerario y para cada resolución de ciudad, y para los tramos del
   camino asincrónico. **No** hay un span por sentencia JDBC: multiplicaría el
   volumen por diez para decir lo que el pool ya dice.
2. **El `correlationId` y el `traceId` conviven, y ninguno reemplaza al otro.**
   El `correlationId` cubre el **100 %** de los pedidos siempre, viaja en el
   header de respuesta, en el envelope del evento y en la columna
   `correlation_id` de la tabla de auditoría, que tiene retención de años. El
   `traceId` sólo existe para los pedidos muestreados y vive lo que vive la
   traza. Un identificador que a veces no resuelve no sirve para lo que la
   auditoría necesita. Los dos están en el MDC y en cada línea de log, así que el
   pivote es directo: del reclamo al log por `correlationId`, del log a Tempo por
   `traceId`.
3. **Muestreo `1.0` en local y `0.1` en producción.** El 10 % caracteriza
   latencia; para el caso puntual está el log, que es del 100 %.
4. **La exportación está apagada por defecto** (`TRACING_EXPORT_ENABLED`). Igual
   que Redis, el broker y el catálogo: la aplicación arranca y la suite corre sin
   backend de trazas.
5. **El backend es Tempo, en el profile de observabilidad del compose**, OSS y
   local, sin cuenta.

### Alternativas descartadas

| Alternativa | Por qué se descartó | Qué la volvería a poner sobre la mesa |
|---|---|---|
| **Posponer las trazas** | Es defendible: es la pata más cara de las tres. Se eligió no esperar porque el caso que las justifica —el fan-out con presupuesto— **ya existe**, no es hipotético. Los disparadores que habrían obligado a revisarlo quedaron escritos igual: que el catálogo pase a ser un servicio propio, que aparezca un segundo consumidor de `reservations.events` en el mismo flujo de negocio, o que el p95 del `POST` viole su SLO sin causa atribuible | — |
| **Reemplazar el `correlationId` por el `traceId`** | Con 10 % de muestreo, el 90 % de los `traceId` no existe en ningún backend, y la columna de auditoría —un artefacto de cumplimiento con retención de años— quedaría apuntando a trazas que nunca se guardaron | Muestreo del 100 % y retención equivalente a la de la auditoría, que nadie va a pagar |
| **Un span por sentencia JDBC** (instrumentación automática completa) | Diez veces el volumen para decir lo que `hikaricp.connections.*` y el `statement_timeout` ya dicen | Una consulta puntual sospechosa: se instrumenta esa, no todas |
| **Muestreo por error (*tail sampling*) en la aplicación** | Quedarse siempre con las trazas que fallaron exige decidir después de que el pedido terminó, y eso es una decisión del colector, no del proceso | Volumen que lo justifique; se agrega en el colector sin tocar la aplicación |
| **Zipkin / Jaeger en lugar de OTLP + Tempo** | Funcionan igual de bien; OTLP es el protocolo que no ata a un backend, y Tempo comparte Grafana con las métricas y los logs, que es lo que hace posible el pivote desde una línea de log | Un backend ya instalado en la organización que hable otro protocolo |

## Consecuencias

### A favor

- **El fan-out del itinerario se puede diagnosticar.** Cuál de las once ciudades
  consumió el presupuesto y en qué decorador se fue el tiempo es un gráfico, no
  una reconstrucción a mano.
- **El camino asincrónico deja de ser una caja negra en el tiempo**: se ve cuánto
  tardó cada tramo entre el pedido y el consumidor, y no sólo que ocurrieron.
- **Del reclamo de un usuario a la traza hay dos saltos**, los dos automáticos,
  porque los dos identificadores están en cada línea de log.
- **No ata a un proveedor**: OTLP es el protocolo, el backend se cambia sin tocar
  el código.

### En contra, y asumido

- **Tres dependencias más en el `pom.xml`** (bridge de tracing, exportador OTLP,
  propagación de contexto) y un contenedor más si se quiere ver algo.
- **El muestreo hace que el pedido que a alguien le importa muchas veces no
  esté.** Es la contracara del 10 %, y es por lo que el `correlationId` no se
  jubila.
- **El overhead existe.** La instrumentación cuesta CPU y memoria en el camino
  del pedido, justo en el camino que [0014](0014-presupuesto-de-latencia-del-pedido.md)
  está tratando de acortar. Se acota con el alcance chico y con el muestreo.
- **Dos identificadores para la misma cosa** es un concepto más que explicar a
  quien llega, y la pregunta «¿cuál uso?» se responde con la tabla del diseño,
  no con intuición.
- **Sin colector, las trazas no existen** y nada avisa. Es la misma decisión de
  siempre —la aplicación no depende de su observabilidad— con el mismo riesgo: un
  entorno mal configurado parece sano.
