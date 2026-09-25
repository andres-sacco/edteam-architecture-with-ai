# 0009 — El catálogo de ciudades se integra detrás de un puerto, con clasificación explícita de fallos

- **Estado:** Aceptado — sus valores numéricos los recalibró [0012](0012-circuit-breakers-y-clasificacion-de-fallos.md)
- **Fecha:** 2026-09-25
- **Prompt origen:** [08 — Adaptador REST hacia un servicio externo](../prompts/08-adaptador-rest-servicio-externo.md)
- **Se apoya en:** [0001 — Arquitectura hexagonal](0001-arquitectura-hexagonal-en-un-modulo.md)
- **Custodiado por:** `RestCityCatalogClientTest`, `CityCatalogValidationTest`, `CatalogResilienceIT`, `HexagonalArchitectureTest#noTransactionalClassReachesTheAirportCatalog`
- **Se ve en:** [Diagramas C4 de contexto y contenedores](../architecture/c4.md)

> Redactado retroactivamente el 2026-09-25. El número refleja el orden en que se
> tomó la decisión, no el orden en que se escribió el documento.

## Contexto

Validar que existen el origen y el destino de cada tramo es un requisito del alta
y de la modificación. La pregunta abierta desde el primer prompt era contra qué:
un maestro propio o un proveedor externo. Se resolvió por el segundo — hay un
`api-catalog` con su propia base, autenticado con API key en un header.

Eso pone **una dependencia de red sincrónica en el camino del `POST`**, que es
exactamente lo que el sistema venía evitando con el resto de sus integraciones.
Y no es una llamada: un itinerario de ida y vuelta con escala son ocho códigos de
ciudad, y hasta once en el peor caso observado.

El proveedor tiene además una respuesta ambigua que hay que decidir cómo leer:
devuelve `200` con el cuerpo vacío para una ciudad que no existe, en lugar de un
`404`.

La restricción del momento era explícita y contraintuitiva: **sin timeouts y sin
reintentos**, distinguiendo 4xx de 5xx. Eso fijó el orden del trabajo — primero
saber *qué clase* de fallo es cada respuesta, después qué hacer con cada clase.

## Decisión

**El catálogo vive detrás de `AirportCatalogPort`, y lo que cruza ese puerto no
es una respuesta HTTP: es una de tres cosas, decididas explícitamente por el
cliente.**

| Lo que llega del proveedor | Cómo se clasifica | Qué es |
|---|---|---|
| `200` con cuerpo válido | la ciudad | respuesta |
| `404`, o `200` con cuerpo vacío | `Optional.empty()` | **respuesta de negocio**: la ciudad no existe. El proveedor está sano |
| `5xx`, `429`, timeout, error de conexión | `AirportCatalogUnavailableException` | **fallo transitorio**: el pedido está bien, el problema es del otro lado |
| `4xx` que no es 404, credencial vencida, cuerpo fuera de contrato | `AirportCatalogIntegrationException` | **fallo permanente**: el mismo pedido da lo mismo hasta que una persona intervenga |

Esa tabla es la decisión. Todo lo demás se deriva de ella: qué se reintenta, qué
cuenta para un circuito, qué se alerta y qué código HTTP ve el usuario. Sin ella,
"el catálogo falló" es una sola cosa y no hay política posible.

Alrededor, cuatro decisiones más:

1. **Un `200` vacío es "no existe", no un error.** Lo define el proveedor y lo
   absorbemos en el borde: ningún otro componente se entera de la ambigüedad.
2. **Los timeouts se declaran por proveedor, no globalmente.** Están en
   `AirportCatalogProperties`, no en una configuración global de HTTP: lo que
   tolera este catálogo no tiene por qué ser lo que tolere el próximo servicio.
3. **Sólo se reintenta el `GET /city/{code}`**, que es idempotente. No hay
   reintento sobre ninguna escritura.
4. **La llamada al catálogo está fuera de toda transacción**, y hay una regla de
   ArchUnit que lo sostiene. Sin eso, la lentitud del proveedor sería agotamiento
   del pool de Hikari: un catálogo degradado tumbaría la API entera, incluidos los
   `GET` que ni lo tocan.
5. **Sin `base-url` configurada, el puerto lo sirve un stub en memoria**
   (`StaticAirportCatalog`). El build no puede depender de que haya un proveedor
   levantado. Con `base-url` configurada y sin TLS, la aplicación **no arranca**:
   la API key viaja en un header y sin TLS se lee en el camino.

**Los números en vigencia no son los de este ADR.** La restricción original —sin
timeouts, sin reintentos— se levantó después: hoy hay `connect-timeout: 300ms`,
`read-timeout: 700ms` y dos intentos con backoff y jitter, calibrados en
[0012](0012-circuit-breakers-y-clasificacion-de-fallos.md) y
[0014](0014-presupuesto-de-latencia-del-pedido.md). Lo que no cambió es la
clasificación: esos ADR la usan tal cual y le agregaron una sola distinción, el
`429`.

### Alternativas descartadas

| Alternativa | Por qué se descartó | Qué la volvería a poner sobre la mesa |
|---|---|---|
| **Maestro de aeropuertos propio** (tabla `aeropuerto` en nuestra base) | Cero latencia y cero dependencia, a cambio de ser dueños de un dato que cambia y que no generamos: habría que sincronizarlo con alguien igual, y esa sincronización es la misma integración corrida de lugar | Un catálogo que deje de cambiar, o un proveedor que ofrezca un volcado completo en vez de una consulta por código: ahí conviene replicarlo y consultarlo local |
| **Propagar la excepción HTTP tal cual** (`RestClientException` hacia arriba) | El caso de uso tendría que entender códigos de estado para decidir si rechazar la reserva o reintentar, y `application` pasaría a conocer HTTP | Nada: rompe [0001](0001-arquitectura-hexagonal-en-un-modulo.md) |
| **Tratar cualquier fallo del catálogo como "el aeropuerto no existe"** | Es el atajo tentador y es el peor de todos: convierte una caída del proveedor en un `400 UNKNOWN_AIRPORT` sobre un aeropuerto que sí existe, le miente al usuario y le hace corregir un formulario correcto | Nada |
| **Una sola clase de excepción para todos los fallos** | Sin la distinción transitorio/permanente no se puede decidir qué reintentar ni qué alertar: una credencial vencida y un `503` recibirían el mismo trato, y el primero se reintentaría para siempre | Nada |
| **Un cliente generado desde el OpenAPI del proveedor** | No hay contrato publicado por el proveedor, y el comportamiento que hay que modelar es justamente el que un contrato generado no captura: el `200` vacío | Que el proveedor publique un contrato versionado |

## Consecuencias

### A favor

- **Un `503` del catálogo nunca se convierte en un `400` al usuario.** Es el
  resultado concreto de la tabla, y lo verifica `CatalogResilienceIT`.
- **La política se puede cambiar sin tocar el cliente HTTP.** Reintentos,
  circuito, bulkhead y presupuesto se agregaron después como decoradores
  alrededor de `RestCityCatalogClient`, que no cambió una línea.
- **Un catálogo lento no toma conexiones de la base.** La regla de ArchUnit que
  lo garantiza (`noTransactionalClassReachesTheAirportCatalog`) es la primera
  defensa de resiliencia del repositorio y es anterior a que hubiera una.
- **El proyecto se levanta y la suite corre sin el proveedor**, gracias al stub.

### En contra, y asumido

- **Hay una dependencia de red en el camino de la escritura, y no se puede
  sacar.** Todo lo que vino después —caché, circuito, presupuesto, *stale*— existe
  para acotar ese hecho, no para eliminarlo.
- **El costo crece con el tamaño del itinerario.** Once ciudades son once
  consultas. El fan-out con presupuesto ([0014](0014-presupuesto-de-latencia-del-pedido.md))
  acota el reloj, no la cantidad de llamadas.
- **La clasificación es una interpretación del comportamiento del proveedor, no
  de un contrato.** Si el `api-catalog` empieza a devolver `200` vacío en una
  caída parcial, lo vamos a leer como "la ciudad no existe" y vamos a rechazar
  reservas válidas. Es el riesgo estructural de la decisión y no tiene mitigación
  dentro de nuestro código: lo que hay es la alerta de
  `reservations.catalog.errors{kind="integration"}` y la tasa de `400` por
  aeropuerto desconocido.
- **La API key es una credencial de larga duración en una variable de entorno.**
  Rotarla es un cambio de entorno, no un commit, pero no hay rotación automática.
- **El stub miente cómodamente.** Un entorno con `base-url` vacía valida contra
  una lista fija y parece sano. Se avisa en el arranque, con el mismo riesgo —y la
  misma mitigación— que el fallback del broker de [0004](0004-mensajeria-asincronica-y-broker.md).
