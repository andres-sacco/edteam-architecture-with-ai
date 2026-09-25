# 0015 — Logs en JSON, con el mismo formato en todos los entornos

- **Estado:** Aceptado
- **Fecha:** 2026-09-25
- **Prompt origen:** [19 — Diseño de la observabilidad](../prompts/19-diseno-de-observabilidad.md)
- **Llevado al código en:** [21 — Implementación de la observabilidad](../prompts/21-implementacion-de-observabilidad.md)
- **Diseño completo:** [`docs/observability/design.md`](../observability/design.md) · **Auditoría:** [`docs/observability/audit.md`](../observability/audit.md) · **Implementación:** [`docs/observability/implementation.md`](../observability/implementation.md)
- **Custodiado por:** `LogSchemaTest`, `MdcPropagationTest`, `CorrelationIdPropagationTest`, `scripts/pii-log-gate.sh` (gate de `./mvnw verify`)

## Contexto

No había `logback-spring.xml`: salía el texto por defecto de Spring Boot, con el
dato interpolado adentro del mensaje y el MDC fuera del renglón. El único control
era un nivel global. El javadoc de `LogSanitizer` —la clase que sanea saltos de
línea de los datos externos para que un `\n` no fabrique un registro falso— ya
decía que era un parche y que la solución de fondo era loguear en JSON.

El problema que eso genera no es estético. Con el dato adentro del mensaje, cada
consulta durante un incidente es una expresión regular sobre texto libre, y cada
cambio de redacción de un mensaje rompe la consulta que alguien escribió. No
había convención de niveles —cada clase escribía como le parecía—, no había
manera de saber qué campos lleva un registro, y no había ninguna verificación de
que no se estuviera escribiendo un documento de pasajero.

Lo que sí había, y era la base: el `correlationId` por pedido en el MDC, que
además viaja en el envelope del evento y se repone al consumir.

## Decisión

**Un solo formato, JSON a `stdout`, en todos los entornos, con un campo por
dato.**

1. **Sin variante «linda» para desarrollo.** Un formato distinto en local es un
   esquema que sólo se prueba en producción: el día que falte un campo, nadie lo
   nota hasta que hace falta. En la terminal se lee con `jq`; en el navegador,
   con el Grafana del stack local.
2. **El mensaje deja de transportar información.** `message` queda como texto
   humano fijo, sin interpolar, y los datos van en campos propios.
3. **La pieza que lo hace compatible con la hexagonal es la API fluida de
   SLF4J 2.** `log.atInfo().addKeyValue("reservationId", …)` es `org.slf4j`, no
   la librería del encoder: `application` declara **qué dato acompaña al hecho**,
   y que ese par termine siendo un campo JSON lo decide el encoder, que vive en
   `infrastructure` y se configura en un XML. `domain` no loguea en absoluto.
   Dos reglas de ArchUnit lo sostienen.
4. **Una tabla de niveles con un criterio, no con una costumbre.** Una
   dependencia que degrada y se recupera no es lo mismo que un pedido que el
   usuario perdió; cada `ERROR` que era esperado y cada pérdida del usuario que
   no dejaba rastro se reclasificaron.
5. **El `correlationId` está en todos los registros, incluidos los que no nacen
   de un pedido HTTP**: se propaga a los hilos virtuales del fan-out, al header
   de las llamadas salientes al catálogo, a las tareas programadas y al consumidor
   de mensajería.
6. **La ausencia de PII es un gate del build, no una revisión.**
   `scripts/pii-log-gate.sh` corre dentro de `./mvnw verify` y grepea la salida
   capturada de **toda la suite** contra patrones de datos personales: si
   aparece un documento, un token o un email en claro, el build queda en rojo.
   `PiiMasker` y `LogSanitizer` siguen siendo el piso.
7. **El volumen es parte del diseño y está medido**: 535 bytes promedio por
   registro, ~130 500 líneas/día, ~70 MB/día. Para pagar la línea nueva más
   frecuente (`http.request`) se bajaron cinco registros de nivel, y las cuentas
   están en la implementación.

### Alternativas descartadas

| Alternativa | Por qué se descartó | Qué la volvería a poner sobre la mesa |
|---|---|---|
| **Texto legible en local, JSON en el resto** | Es la configuración más común y significa que el esquema sólo se ejerce donde no se lo puede corregir barato. Un campo que falta se descubre durante un incidente | Nada; `jq` resuelve la legibilidad sin partir el esquema |
| **Seguir con texto plano y mejorar `LogSanitizer`** | Sanear el texto evita que un dato externo fabrique registros falsos, y no arregla que cada consulta sea una expresión regular sobre una redacción que puede cambiar | Nada |
| **Un appender que mande los logs por TCP al backend** | Agrega un modo de falla nuevo —qué pasa cuando el backend no responde— adentro del proceso que se está tratando de observar. La aplicación escribe a `stdout` y el agente del nodo recolecta | Nada |
| **Usar la API de la librería del encoder en `application`** (`net.logstash.*`) | Ataría la capa de aplicación al formato de salida. La API fluida de SLF4J 2 da lo mismo sin acoplar | Nada: hay una regla de ArchUnit |
| **Revisión manual de PII en los logs** | No escala y no sobrevive a un cambio apurado. Un gate que corre en cada `verify` sí | Nada |
| **Rangos de versión para el encoder** | Los nombres de campo son un contrato: hay consultas, paneles y alertas escritos contra ellos, y los providers por defecto cambian entre versiones mayores. Un upgrade silencioso renombraría campos sin que falle nada hasta el día del incidente | Nada |

## Consecuencias

### A favor

- **Buscar por `correlationId` devuelve el pedido completo**, incluidos los
  tramos que corren en otro hilo, en una tarea programada o del otro lado del
  broker. Está probado punta a punta (`CorrelationIdPropagationTest`,
  `MdcPropagationTest`).
- **Las consultas dejan de depender de la redacción.** Cambiar el texto de un
  mensaje ya no rompe un panel.
- **Un registro que no cumple el esquema rompe el build** (`LogSchemaTest`), y un
  documento de pasajero en la salida de la suite también.
- **Los eventos que antes no dejaban huella ahora la dejan**: fallo de
  autenticación, rechazo por cuota, conflicto de versión, respuesta servida
  *stale*, mensaje que llega a dead letter.
- **El día malo está presupuestado.** Una caída de Redis con 100 000 pedidos
  producía ~1,6 M de líneas —doce veces el presupuesto diario completo— y hoy
  produce ~100 000: una por pedido.

### En contra, y asumido

- **Los logs no se leen sin herramienta.** Un `tail` crudo es ilegible; hace
  falta `jq` o el stack local. Es fricción diaria y es la contracara directa de
  no tener un formato de desarrollo.
- **Una dependencia más en el `pom.xml`**, con versión fija y con el
  acoplamiento implícito de que los nombres de campo son un contrato público.
- **Escribir un log ahora tiene una forma correcta y varias incorrectas.**
  `log.info("Reserva {} creada", id)` sigue compilando y no cumple el esquema:
  el test lo detecta, pero el autor tuvo que aprender la diferencia.
- **El volumen creció en el caso normal**: un `GET` pasó de 0 a 1 línea, y
  `http.request` agrega ~100 000/día. Se compensó bajando cinco registros de
  nivel, o sea que se compró legibilidad del camino feliz pagando con detalle en
  el camino degradado —lo que bajó a `DEBUG` deja de verse en producción salvo
  que alguien suba el nivel a propósito.
- **El gate de PII es una lista de patrones.** Detecta las formas conocidas; un
  dato personal con una forma que no está en la lista pasa. Es mucho mejor que
  una revisión manual y no es una garantía.
