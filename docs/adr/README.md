# Architecture Decision Records (ADR)

Decisiones de arquitectura del sistema de reservas de vuelos.

Cada ADR documenta una decisión significativa, su contexto y sus consecuencias. El formato usado es el de Michael Nygard:

```markdown
# NNNN — Título de la decisión

- **Estado:** Propuesto | Aceptado | Rechazado | Reemplazado por [NNNN](...)
- **Fecha:** YYYY-MM-DD

## Contexto

## Decisión

## Consecuencias
```

## Convenciones

- **Nombre de archivo:** `NNNN-slug-descriptivo.md` (numeración de 4 dígitos, correlativa).
- Un ADR nunca se borra ni se reescribe: si una decisión cambia, se crea uno nuevo que la reemplaza y se actualiza el estado del anterior.
- Cuando un ADR nació de un prompt, enlazar el archivo correspondiente en [`docs/prompts/`](../prompts/README.md).

## Índice

| # | Decisión | Estado | Prompt origen |
|---|----------|--------|---------------|
| [0001](0001-arquitectura-hexagonal-en-un-modulo.md) | Arquitectura hexagonal en un solo módulo, con separación por paquetes | Aceptado | [02 — Layers vs. Hexagonal](../prompts/02-comparacion-layers-vs-hexagonal.md) |
| [0002](0002-modelo-relacional-3fn-en-postgresql.md) | Modelo relacional normalizado hasta 3FN sobre PostgreSQL | Aceptado | [04 — Modelo de datos 3FN](../prompts/04-modelo-de-datos-3fn.md) |
| [0003](0003-autenticacion-autorizacion-y-datos-sensibles.md) | Autenticación, autorización por recurso y datos sensibles | Aceptado | [12 — Remediación de seguridad](../prompts/12-remediacion-de-seguridad.md) |
| [0004](0004-mensajeria-asincronica-y-broker.md) | Mensajería asincrónica: eventos sobre RabbitMQ con outbox en PostgreSQL | Aceptado | [13 — Topología de eventos y colas](../prompts/13-topologia-de-eventos-y-colas.md) |
| [0005](0005-garantias-de-entrega-y-remediacion-de-la-mensajeria.md) | Garantías de entrega de la mensajería: idempotencia, dos dead letters y reintentos clasificados | Aceptado | [15 — Implementación de mensajería](../prompts/15-implementacion-de-mensajeria.md) |
| [0006](0006-el-esquema-lo-gobierna-flyway.md) | El esquema lo gobierna Flyway y Hibernate sólo lo valida | Aceptado | [05 — Persistencia y dominio](../prompts/05-persistencia-y-dominio.md) |
| [0007](0007-contrato-openapi-generado-desde-el-codigo.md) | El contrato OpenAPI se genera desde el código y se versiona en el repositorio | Aceptado | [06 — Contrato de la API](../prompts/06-contrato-openapi.md) |
| [0008](0008-concurrencia-optimista-e-idempotencia-en-el-protocolo.md) | La concurrencia optimista y la idempotencia se expresan en el protocolo HTTP | Aceptado | [07 — Adaptador REST de entrada](../prompts/07-adaptador-rest-entrada.md) |
| [0009](0009-integracion-con-el-catalogo-externo.md) | El catálogo de ciudades se integra detrás de un puerto, con clasificación explícita de fallos | Aceptado * | [08 — Adaptador REST hacia servicio externo](../prompts/08-adaptador-rest-servicio-externo.md) |
| [0010](0010-cache-distribuida-opcional-con-fallback-en-memoria.md) | La caché es distribuida, opcional y vive en decoradores | Aceptado * | [10 — Implementación de la caché](../prompts/10-implementacion-de-cache.md) |
| [0011](0011-alcance-de-la-cache.md) | Qué se cachea: sólo datos sin PII, con TTL positivo y negativo | Aceptado * | [09 — Cuellos de botella para caché](../prompts/09-cuellos-de-botella-cache.md) |
| [0012](0012-circuit-breakers-y-clasificacion-de-fallos.md) | Circuit breakers en tres dependencias, con una clasificación de fallos compartida | Aceptado | [16 — Diseño de la resiliencia](../prompts/16-diseno-de-resiliencia.md) |
| [0013](0013-degradacion-explicita-y-visible.md) | Cuando una dependencia no está, el sistema degrada y lo dice | Aceptado | [16 — Diseño de la resiliencia](../prompts/16-diseno-de-resiliencia.md) |
| [0014](0014-presupuesto-de-latencia-del-pedido.md) | El pedido tiene un presupuesto de tiempo, y cada techo está declarado | Aceptado | [16 — Diseño de la resiliencia](../prompts/16-diseno-de-resiliencia.md) |
| [0015](0015-logging-estructurado-en-json.md) | Logs en JSON, con el mismo formato en todos los entornos | Aceptado | [19 — Diseño de la observabilidad](../prompts/19-diseno-de-observabilidad.md) |
| [0016](0016-metricas-con-cardinalidad-acotada-y-alertas.md) | Métricas con cardinalidad acotada y seis alertas versionadas en el repositorio | Aceptado | [19 — Diseño de la observabilidad](../prompts/19-diseno-de-observabilidad.md) |
| [0017](0017-trazas-distribuidas-con-alcance-acotado.md) | Trazas distribuidas, con un alcance chico y explícito | Aceptado | [19 — Diseño de la observabilidad](../prompts/19-diseno-de-observabilidad.md) |
| [0018](0018-imagen-de-contenedor-multietapa.md) | Imagen de contenedor multietapa, por capas y sin privilegios | Aceptado | [22 — Dockerfile multietapa](../prompts/22-dockerfile-multietapa.md) |
| [0019](0019-pipeline-de-ci-con-publicacion-de-imagen-versionada.md) | Pipeline de CI que prueba, mide y publica una imagen identificable — y no despliega | Aceptado | [24 — Pipeline de CI](../prompts/24-pipeline-ci-cd.md) |

Todas las alternativas evaluadas y descartadas, con la condición que volvería a
abrir cada una, están indexadas en
[`alternativas-descartadas.md`](alternativas-descartadas.md).

\* **Aceptado, con un punto acotado por una decisión posterior.** Ninguno está
reemplazado: la decisión sigue en pie y lo que cambió está nombrado en el
encabezado del propio ADR y en el que lo acotó.

| ADR | Qué se acotó | Quién lo acotó |
|---|---|---|
| [0009](0009-integracion-con-el-catalogo-externo.md) | Los valores de timeout y de reintentos (la clasificación de fallos no cambió) | [0012](0012-circuit-breakers-y-clasificacion-de-fallos.md), [0014](0014-presupuesto-de-latencia-del-pedido.md) |
| [0010](0010-cache-distribuida-opcional-con-fallback-en-memoria.md) | Qué claves usan el fallback en memoria en caliente | [0012](0012-circuit-breakers-y-clasificacion-de-fallos.md) |
| [0011](0011-alcance-de-la-cache.md) | El *stale-while-error* sirve sólo entradas positivas | [0013](0013-degradacion-explicita-y-visible.md) |

## Sobre la numeración

Los ADR **0001, 0002 y 0006 a 0019** se redactaron retroactivamente el
2026-09-25, a partir del recorrido de [`docs/prompts/`](../prompts/README.md), de
los documentos de diseño y del código. **Su número refleja el orden cronológico
en que se tomó la decisión, no el orden en que se escribió el documento**: por
eso 0001 y 0002 —arquitectura y modelo de datos, las dos decisiones más
tempranas— ocupan los números que habían quedado libres, y por eso 0006 (que
sigue a 0002 en el tiempo) tiene un número posterior a 0005.

Los ADR 0003, 0004 y 0005 ya existían y **no se tocaron**: ninguno quedó
reemplazado por una decisión posterior. Donde una decisión nueva recalibró un
valor que uno de ellos cita —el `max-attempts` del outbox, que pasó de 10 a 80—
lo dice el ADR nuevo, no se reescribe el viejo.
