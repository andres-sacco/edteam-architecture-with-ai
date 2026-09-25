# 0012 — Circuit breakers en tres dependencias, con una clasificación de fallos compartida

- **Estado:** Aceptado
- **Fecha:** 2026-09-25
- **Prompt origen:** [16 — Diseño de la resiliencia](../prompts/16-diseno-de-resiliencia.md)
- **Llevado al código en:** [18 — Implementación de la resiliencia](../prompts/18-implementacion-de-resiliencia.md)
- **Diseño completo:** [`docs/resilience/design.md`](../resilience/design.md) · **Auditoría:** [`docs/resilience/audit.md`](../resilience/audit.md) · **Implementación:** [`docs/resilience/implementation.md`](../resilience/implementation.md)
- **Custodiado por:** `CircuitTest`, `CircuitBreakingCityCatalogClientTest`, `CircuitBreakingCacheStoreTest`, `FailuresTest`, `CatalogResilienceIT`, `HexagonalArchitectureTest#resilienceStaysInInfrastructure`

## Contexto

El sistema tenía timeouts, reintentos y *stale-while-error*, y **ningún circuit
breaker**: `resilience4j` no estaba en el `pom.xml`. Con el catálogo caído, cada
pedido nuevo volvía a pagar el camino completo antes de caer al dato viejo —hasta
7,8 s por ciudad, y un ida y vuelta con escala consulta ocho ciudades en serie—.
Con Redis caído, un `POST` pagaba el timeout de 200 ms hasta veintidós veces: más
de 3 s de espera pura por un componente cuyo único aporte es ahorrar tiempo. Y
con el broker caído, cada tick del relay gastaba un intento de cada mensaje
contra una conexión que no iba a abrir: diez ticks y mensajes recuperables
terminaban muertos.

Los tres son el mismo problema: **seguir llamando a algo que ya sabemos que no
contesta**. La clasificación de fallos del catálogo
([0009](0009-integracion-con-el-catalogo-externo.md)) existía, pero la usaba sólo
el retry, y no había ninguna para Redis ni para el broker.

## Decisión

**Circuit breaker en tres dependencias —catálogo, Redis y broker— y en ninguna
más. Un único lugar decide si una excepción es transitoria o permanente, y lo
usan el circuito y el retry.**

### Dónde sí y dónde no

Un circuito sirve cuando se cumplen tres condiciones a la vez: llamar cuesta
caro, hay algo mejor que hacer que esperar, y la dependencia se recupera sola.

| Dependencia | ¿Circuito? | Por qué |
|---|---|---|
| **api-catalog** | Sí | Las tres condiciones. Es el caso de libro |
| **Redis** | Sí | Cuesta 200 ms, lo mejor es ir al origen (que es lo que se hace en un miss) y un failover dura segundos |
| **RabbitMQ** | Sí, **en el relay**, no en el camino del pedido | No protege latencia —nadie espera— sino el **presupuesto de reintentos del outbox**: es lo que separa "el mail llegó tarde" de "hay que reenviarlo a mano" |
| **PostgreSQL** | **No** | Falla la condición 2: sin base no hay nada que degradar. Abrir un circuito sólo cambia fallar lento por fallar rápido, y eso ya lo da `connection-timeout`. Además dispararía en falso: el relay compite por el mismo pool, y un pico de contención se leería como una base caída. La caída total **sí** tiene que sacar la instancia de rotación, y para eso el health indicator de `db` queda **encendido** |
| **Notificaciones** (consumidor) | **No** | Falla la condición 1: no lo llamamos. En el medio hay una cola, que es un buffer con su propia política |
| **IdP / JWKS** | **No** | Falla la condición 2: aceptar un token sin validarlo no es degradar, es un agujero. Lo correcto es el cache de claves de Nimbus y alertar sobre `401` masivos |

### Los umbrales, y por qué son así

| Parámetro | `catalog` | `redis` | `broker` |
|---|---|---|---|
| Ventana | 50 llamadas | 100 llamadas | 20 llamadas |
| Llamadas mínimas | 20 | 30 | 5 |
| Umbral de fallo | 50 % | 50 % | 60 % |
| Llamada lenta | 60 % por encima de 900 ms | 60 % > 150 ms | 60 % > 2 s |
| Tiempo abierto | 5 s | 10 s | 60 s |
| Semiabierto | 4 llamadas | 5 | 2 |
| Unidad contada | una **resolución de ciudad**, no un intento HTTP | una operación del almacén | una publicación con su confirm |

Tres propiedades comunes, y las tres son decisiones:

- **`COUNT_BASED` y no `TIME_BASED`.** El tráfico es irregular —picos de día, casi
  nada de madrugada— y con una ventana temporal el umbral significa cosas
  distintas según la hora: a las 4 AM dos fallos serían el 100 % de la ventana.
- **`automaticTransitionFromOpenToHalfOpen: true`.** Sin eso, la transición
  depende de que llegue una llamada, y un circuito que se abrió justo cuando cayó
  el tráfico se quedaría abierto hasta el próximo pedido. Ningún circuito puede
  quedar abierto para siempre, y la recuperación no necesita ni tráfico ni una
  persona.
- **Cuentan las llamadas lentas, no sólo los errores.** Es lo que hace que el
  circuito abra **antes** de que la dependencia empiece a devolver errores, que
  es el modo de falla que más duele.

### La clasificación es una sola y la comparten todos

`infrastructure/resilience/Failures` y `FailureClassification` son el único lugar
donde se decide si algo es transitorio o permanente. Antes esa lógica estaba
duplicada entre el retry y la nada; ahora el circuito y el retry preguntan a la
misma función, así que no pueden discrepar. Lo que la decisión agrega sobre
[0009](0009-integracion-con-el-catalogo-externo.md):

- **Un `404` no cuenta para el circuito.** Es una respuesta de negocio. Contarlo
  abriría el circuito ante una ráfaga de códigos mal tipeados, justo cuando todo
  funciona.
- **Un `429` cuenta y ya no se reintenta.** El proveedor está diciendo
  explícitamente que bajemos el ritmo: reintentar es desobedecerlo.
- **Un fallo permanente no cuenta.** Una credencial vencida escondida detrás de un
  `503` genérico retrasa el diagnóstico; para eso hay alerta, que es la respuesta
  correcta a un fallo que necesita una persona.
- **`CallNotPermittedException` se traduce** a `AirportCatalogUnavailableException`
  en el borde del decorador, para que la caché dispare el *stale* y el handler
  siga devolviendo `503` y no un `500` por una excepción que no sabe manejar.

### El orden de los decoradores está cableado, no es accidental

```
CachingAirportCatalog → BudgetedCityCatalogFanout → CatalogCityResolver
    → CircuitBreaking → Bulkhead → Retrying → RestCityCatalogClient
```

El retry va **adentro** del circuito: así una resolución de ciudad es **una**
llamada contada, y no tres. Al revés, un circuito con el retry por fuera cuenta
tres veces lo mismo y abre con un tercio de los fallos reales. La caché va afuera
de todo porque un hit no debe consumir ni circuito ni presupuesto.

### Alternativas descartadas

| Alternativa | Por qué se descartó | Qué la volvería a poner sobre la mesa |
|---|---|---|
| **`@CircuitBreaker` de `resilience4j-spring-boot`** | Trae el starter entero y pone la decisión en una anotación sobre el método protegido. El mismo argumento que con `@Cacheable`: no se ve en el cableado, no se prueba sin Spring, y no permite elegir en qué capa de los cinco decoradores va | Nada previsible |
| **Circuito también en PostgreSQL** | No hay fallback posible y dispararía en falso por contención del pool. Fallar rápido ya lo da `connection-timeout: 1s` | Réplicas de lectura: ahí sí hay algo mejor que hacer que esperar |
| **Ventana temporal (`TIME_BASED`)** | Con tráfico irregular el umbral cambia de significado según la hora | Tráfico parejo las 24 h |
| **Umbrales por defecto de la librería** | Son un número que alguien eligió para otro sistema. Cada valor de la tabla sale del comportamiento observado y está justificado en §10 del diseño | Nada |
| **Un circuito único para todas las dependencias** | Un problema de Redis abriría el camino al catálogo. Un circuito compartido propaga la falla en vez de contenerla | Nada |
| **Reintentos por fuera del circuito** | Multiplica por tres lo que el circuito cuenta y lo hace abrir con un tercio de los fallos reales | Nada |

## Consecuencias

### A favor

- **Con el catálogo caído, el costo por ciudad pasa de segundos a ~0** en cuanto
  el circuito abre, y la reserva se resuelve con el dato guardado. Se reproduce a
  mano bajando el `api-catalog` de `compose.yaml` y verificando que vuelve a
  cerrarse solo al levantarlo.
- **Con Redis caído se dejan de pagar hasta 3,4 s de espera pura por pedido.**
- **El outbox deja de quemar intentos contra un broker caído**: con el circuito
  abierto el scheduler saltea el tick entero sin consultar la base, y si el
  circuito abre en medio de un lote, los mensajes que quedaban se liberan sin
  marcarse como fallidos.
- **La clasificación no puede discrepar entre el retry y el circuito**, porque es
  la misma función y tiene su propio test (`FailuresTest`).
- **El estado de cada circuito y sus transiciones se ven por Actuator**, sin
  entrar al servidor.

### En contra, y asumido

- **Una librería más en el `pom.xml`**, fijada en 2.2.0 y no en un rango: el
  umbral de un circuito es una decisión de diseño y no puede cambiar de semántica
  en un build reproducible.
- **Ocho números por dependencia que alguien va a tener que recalibrar.** Salen
  del comportamiento observado hoy; el día que el proveedor cambie su perfil de
  latencia, un circuito mal calibrado es peor que ninguno: o no abre nunca, o
  corta tráfico sano.
- **Un circuito abierto corta llamadas que podrían haber funcionado.** Es la
  definición del patrón y el precio de no pagar el timeout; lo acota el estado
  semiabierto.
- **El fallback en caliente de la caché quedó acotado por prefijo.** Las claves
  `rsv:ver:*` **no** caen al almacén en memoria: se invalidan activamente y una
  copia por instancia no recibe esa invalidación, así que un `ETag` servido desde
  la memoria de A después de que B modificó la reserva sería incoherente. Un caché
  degradado puede permitirse ser lento, no incoherente. Esto acota la decisión de
  [0010](0010-cache-distribuida-opcional-con-fallback-en-memoria.md), que no
  distinguía entre claves.
- **`max-attempts` del outbox pasó de 10 a 80.** Con el circuito protegiendo el
  presupuesto, el corte por intentos volvió a ser la red y el corte real volvió a
  ser el tiempo (`retry-ceiling: 6h`), que es lo que
  [0005](0005-garantias-de-entrega-y-remediacion-de-la-mensajeria.md) §5 decidió.
  **La decisión de 0005 no cambia —dos cortes, manda el que llegue primero—;
  cambia el número**, y la aplicación avisa al arrancar si los dos vuelven a
  divergir.
- **El circuito de Redis puede abrir por lentitud de red y no del servidor.** El
  umbral de llamada lenta es 150 ms: en una red degradada se abre un circuito
  sobre una dependencia sana. El daño es acotado —se va al origen— y es
  preferible al contrario.
