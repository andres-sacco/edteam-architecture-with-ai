# 0010 — La caché es distribuida, opcional y vive en decoradores

- **Estado:** Aceptado — el alcance del fallback en caliente lo acotó [0012](0012-circuit-breakers-y-clasificacion-de-fallos.md)
- **Fecha:** 2026-09-25
- **Prompt origen:** [10 — Implementación de la caché](../prompts/10-implementacion-de-cache.md)
- **Entrada:** [`docs/performance/cache-bottlenecks.md`](../performance/cache-bottlenecks.md) ([09](../prompts/09-cuellos-de-botella-cache.md))
- **Custodiado por:** `HexagonalArchitectureTest#cacheStaysInInfrastructure`, `CacheIT`, `RedisCacheStoreTest`, `InMemoryCacheStoreTest`
- **Se ve en:** [Diagramas C4 de contexto y contenedores](../architecture/c4.md)

> Redactado retroactivamente el 2026-09-25. El número refleja el orden en que se
> tomó la decisión, no el orden en que se escribió el documento.

## Contexto

Ya había un precedente: `CachingAirportCatalog` decoraba al catálogo con un
`ConcurrentHashMap` de TTL configurable. Funcionaba, y tenía un problema que sólo
aparece con más de un proceso: **cada instancia tiene la suya**. Con N instancias
hay N cachés frías, y cada despliegue y cada *scale-out* dispara una estampida
contra el catálogo justo cuando el sistema está más frágil.

Las restricciones del momento eran duras y contradictorias entre sí. Había que
compartir la caché entre instancias, pero el presupuesto es el **free tier de un
Redis administrado, con memoria limitada** —del orden de 30 MB—. Y la caché no
podía volverse una dependencia: la aplicación tenía que seguir arrancando y la
suite corriendo sin Redis levantado, igual que ya arrancaba sin el catálogo.

## Decisión

**Redis como caché distribuida, encendida por configuración, detrás de
decoradores sobre los puertos de salida. Sin Redis, el mismo código usa un
almacén en memoria acotado.**

1. **Decorador y no `@Cacheable`.** La decisión queda explícita en el grafo de
   dependencias (`AdapterConfiguration`, `CacheConfiguration`), se prueba sin
   levantar Spring, y el puerto no cambia de firma por tener una caché detrás. Es
   la misma razón por la que la seguridad, la resiliencia y la observabilidad
   tampoco entran por anotación.
2. **Un `CacheStore` por uso, no uno compartido.** Hay tres —catálogo de ciudades,
   total del listado, versión de la reserva—. No es por aislamiento (las claves ya
   están prefijadas) sino por observabilidad: con un almacén por uso las métricas
   salen etiquetadas y se puede ver que el catálogo acierta el 99 % mientras el
   total del listado acierta el 40 %. Con un almacén único las dos series se suman
   y no se diagnostica ninguna.
3. **El interruptor es `reservations.cache.redis.enabled`.** Apagado, cada uso
   recibe un `InMemoryCacheStore` acotado. Encendido, el mismo código comparte la
   caché entre instancias sin que ningún decorador ni ningún caso de uso se entere.
   Si alguien lo enciende donde Spring no autoconfiguró Redis, se registra el
   problema y se cae al fallback: no se rompe el arranque por una caché.
4. **Un error de Redis degrada al origen, nunca al cliente.** Un `get` que falla
   es un miss; un `put` que falla es un no-op.
5. **El dominio y la aplicación no saben que existe.** Regla de ArchUnit propia
   (`cacheStaysInInfrastructure`).

**El fallback en caliente se acotó después.** Este ADR decidió que hubiera un
almacén en memoria detrás del distribuido; [0012](0012-circuit-breakers-y-clasificacion-de-fallos.md)
limitó cuáles claves lo usan: las de ciudades sí, las de versión de reserva no,
porque se invalidan activamente y una copia por instancia no recibe esa
invalidación.

### Alternativas descartadas

| Alternativa | Por qué se descartó | Qué la volvería a poner sobre la mesa |
|---|---|---|
| **`@Cacheable` de Spring Cache** | Es menos código, pero mete la decisión adentro de una anotación sobre el método cacheado: no se ve en el cableado, no se prueba sin contexto de Spring, y el `CacheManager` termina siendo un único almacén compartido, que es lo contrario del punto 2 | Nada previsible |
| **Seguir con la caché local por proceso** | Es lo que había. No sobrevive a un reinicio y multiplica la carga sobre el catálogo por la cantidad de instancias, justo durante un despliegue | Volver a una sola instancia |
| **Redis como dependencia obligatoria** | Rompería la regla de que el proyecto se levanta sin servicios externos, y convertiría un componente cuyo único aporte es ahorrar tiempo en un punto de falla | Nada: una caché que puede tumbar el servicio no es una caché |
| **Caché HTTP en un proxy o CDN delante de la API** | Ahorraría más que cualquier caché de servidor, pero las respuestas llevan datos de pasajeros: guardarlas en un intermediario compartido es exactamente lo que `Cache-Control: private, no-store` prohíbe | Endpoints públicos sin datos personales, que hoy no existen |
| **Caffeine como caché local de segundo nivel para todo** | Mejor que un `ConcurrentHashMap`, y no resuelve el problema que motivó el cambio: sigue siendo por proceso. Se usa la idea, acotada, sólo donde la coherencia lo permite ([0012](0012-circuit-breakers-y-clasificacion-de-fallos.md)) | Que aparezca un dato compartible, inmutable y caliente donde el salto de red a Redis sea el cuello |

## Consecuencias

### A favor

- **Un despliegue o un *scale-out* deja de ser una estampida contra el catálogo**:
  la instancia nueva encuentra la caché ya poblada.
- **Cambiar dónde vive la caché es cambiar un bean.** El decorador fue diseñado
  para eso y se comprobó: pasar de memoria a Redis no tocó `RestCityCatalogClient`
  ni ningún caso de uso.
- **Se ve si sirve.** `reservations.cache.{gets,puts,evictions,errors,size}` salen
  etiquetadas por cache, así que "la caché anda" es un número y no una impresión.
- **Redis caído no se nota más allá de la latencia**, y eso está probado en
  `CacheIT` con un almacén que falla a propósito (`FailingCacheStore`).

### En contra, y asumido

- **Un componente más de infraestructura**, con su credencial, su TLS y su modo
  de falla propio — que es justamente el que motivó ponerle un circuito
  ([0012](0012-circuit-breakers-y-clasificacion-de-fallos.md)).
- **Dos comportamientos que mantener.** Con Redis y sin Redis el sistema no se
  comporta igual: la caché local no se comparte y no recibe invalidaciones de
  otras instancias. Los tests cubren los dos caminos, y eso es el doble de
  superficie.
- **La memoria es un presupuesto, no un detalle.** `max-entries: 50000` y los TTL
  no se eligieron por costumbre: están dimensionados para entrar en un free tier
  de ~30 MB, y crecer el alcance de la caché obliga a rehacer esa cuenta.
- **El fallback en memoria puede quedar encendido donde no corresponde.** Igual
  que el stub del catálogo y el del broker: el mismo riesgo y la misma mitigación,
  un aviso en cada arranque.
- **Tres almacenes son tres cosas que configurar y tres series de métricas.** Se
  paga a cambio de poder diagnosticar cada uso por separado.
