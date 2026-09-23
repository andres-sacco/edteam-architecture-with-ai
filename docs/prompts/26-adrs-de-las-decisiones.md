# 26 — ADRs de las decisiones del proyecto

**Etapa:** Documentación

**Salida esperada:** Un documento ADR por decisión, en Markdown, con el índice actualizado

---

## Rol

Actúa como arquitecto de software documentando decisiones técnicas.

## Contexto

Un ADR (*Architecture Decision Record*) es un documento corto e inmutable que registra una decisión arquitectónica clave: por qué se adoptó o se descartó una tecnología, qué beneficios trae y qué deudas técnicas y riesgos se aceptaron a cambio. Vive en el repositorio Git, junto al código, para que el "por qué" no se pierda cuando la gente que lo decidió ya no esté.

El sistema de reservas de vuelos es un proyecto Maven con **Java 21 + Spring Boot 3.5** y arquitectura hexagonal (un solo módulo, separación por paquetes):

```
com.edteam.reservations
├── domain          # model, event, access, exception — sin Spring, sin JPA, sin HTTP
├── application     # port/in, port/out, service, query, outbox, audit, notification, exception
└── infrastructure  # adapter/in/rest, adapter/in/messaging, adapter/in/scheduling, adapter/in/ops,
                    # adapter/out/persistence, adapter/out/airport, adapter/out/messaging,
                    # adapter/out/outbox, adapter/out/inbox, adapter/out/audit,
                    # cache, config, jdbc, logging, security
```

**El formato ya está fijado** en [`docs/adr/README.md`](../adr/README.md): el de Michael Nygard, con nombre de archivo `NNNN-slug-descriptivo.md`, numeración correlativa de cuatro dígitos, y las secciones **Título**, **Contexto**, **Decisión** y **Consecuencias**, más los metadatos de estado y fecha. La convención dice además que un ADR **nunca se borra ni se reescribe**: si una decisión cambia, se crea uno nuevo que la reemplaza y se actualiza el estado del anterior, y que cuando un ADR nació de un prompt se enlaza el archivo de [`docs/prompts/`](README.md).

**Lo que ya está escrito:**

| ADR | Decisión | Prompt origen |
|---|---|---|
| [0003](../adr/0003-autenticacion-autorizacion-y-datos-sensibles.md) | Autenticación, autorización por recurso y datos sensibles | [12](12-remediacion-de-seguridad.md) |
| [0004](../adr/0004-mensajeria-asincronica-y-broker.md) | Mensajería asincrónica: eventos sobre RabbitMQ con outbox en PostgreSQL | [13](13-topologia-de-eventos-y-colas.md) |
| [0005](../adr/0005-garantias-de-entrega-y-remediacion-de-la-mensajeria.md) | Garantías de entrega: idempotencia, dos dead letters y reintentos clasificados | [15](15-implementacion-de-mensajeria.md) |

**Lo que está decidido en el código pero no tiene ADR.** El índice arranca en 0003: **los números 0001 y 0002 están libres y las decisiones que les corresponden no están documentadas**. El recorrido completo del proyecto, prompt por prompt, es el material de entrada:

- **Arquitectura**: hexagonal en vez de capas, en un solo módulo con separación por paquetes ([02](02-comparacion-layers-vs-hexagonal.md), [03](03-esqueleto-proyecto-hexagonal.md)). La regla la custodia `HexagonalArchitectureTest` con ArchUnit.
- **Modelo de datos**: 3FN sobre PostgreSQL con Flyway, `ddl-auto: validate`, locking optimista expuesto como `ETag`/`If-Match` ([04](04-modelo-de-datos-3fn.md), [05](05-persistencia-y-dominio.md)).
- **Contrato de la API**: OpenAPI 3.0 generado desde el código con springdoc y versionado en `docs/api/openapi.yaml`, custodiado por `OpenApiContractTest`; Swagger UI y el documento apagados por defecto ([06](06-contrato-openapi.md), [07](07-adaptador-rest-entrada.md)).
- **Integración con el catálogo externo**: timeouts por proveedor, reintentos sólo sobre el `GET` idempotente, clasificación explícita de fallos transitorios y permanentes ([08](08-adaptador-rest-servicio-externo.md)).
- **Caché**: Redis como caché distribuida opcional con fallback en memoria, dos TTL (positivo y negativo) y *stale-while-error* ([09](09-cuellos-de-botella-cache.md), [10](10-implementacion-de-cache.md)).
- **Resiliencia**: circuit breaker, reintentos acotados y fallback por dependencia ([16](16-diseno-de-resiliencia.md), [18](18-implementacion-de-resiliencia.md)).
- **Observabilidad**: logging estructurado, catálogo de métricas, trazabilidad por `correlationId` y alertas ([19](19-diseno-de-observabilidad.md), [21](21-implementacion-de-observabilidad.md)).
- **Despliegue**: imagen multietapa con usuario sin privilegios y pipeline de CI/CD con despliegue versionado ([22](22-dockerfile-multietapa.md), [24](24-pipeline-ci-cd.md)).

Además hay documentos de trabajo que son **insumo** de los ADR y no los reemplazan: [`docs/security/threat-model.md`](../security/threat-model.md), [`docs/performance/cache-bottlenecks.md`](../performance/cache-bottlenecks.md), [`docs/messaging/topology.md`](../messaging/topology.md) y [`docs/messaging/audit.md`](../messaging/audit.md).

## Tarea

Redactar un ADR por cada decisión relevante del proyecto que todavía no lo tenga.

1. **Inventariar las decisiones** recorriendo los prompts, los documentos de diseño y el código. Separar las que son **decisiones arquitectónicas** —cambian la forma del sistema, son caras de revertir, alguien va a preguntar por qué— de las que son **detalles de implementación**, que no llevan ADR.
2. **Asignar la numeración**: usar los números libres `0001` y `0002` para las decisiones más tempranas (la arquitectura y el modelo de datos) y seguir correlativamente desde `0006` para las demás, respetando el orden cronológico real en el que se tomaron.
3. **Escribir cada ADR** con las cuatro secciones del formato. En el **Contexto**, el problema y las fuerzas que había en ese momento, no la solución. En la **Decisión**, qué se hizo, en presente y sin condicionales.
4. **Registrar las alternativas descartadas** en cada ADR: cuáles se evaluaron, por qué se dejaron de lado y bajo qué condición volverían a estar sobre la mesa. Una decisión sin alternativas descartadas no se puede revisar después.
5. **Escribir las consecuencias en los dos sentidos**: qué mejoró y qué empeoró. Las deudas técnicas y los riesgos aceptados van explícitos, con nombre. Cada consecuencia tiene que ser **concreta y verificable**: decir en qué se nota, en qué archivo, en qué métrica o en qué test.
6. **Enlazar la trazabilidad** de cada ADR: el prompt de origen, los documentos de diseño o auditoría que lo alimentaron, los ADR con los que se relaciona y el test o el archivo que custodia la decisión, cuando exista.
7. **Revisar los ADR ya escritos** (0003, 0004 y 0005) para verificar que los nuevos no los contradigan, y actualizar el estado de cualquiera que haya quedado reemplazado por una decisión posterior.
8. **Actualizar el índice** de [`docs/adr/README.md`](../adr/README.md) con todas las filas nuevas.

## Restricciones

- **Formato estándar, sin excepciones**: Título, Contexto, Decisión y Consecuencias, más los metadatos de estado, fecha y prompt de origen que ya usan los ADR existentes.
- **Un ADR, una decisión.** Si un documento necesita la palabra "además" para enganchar un tema nuevo, son dos ADR.
- **Corto**: un ADR se lee en unos minutos. El detalle largo vive en los documentos de diseño y se enlaza desde acá.
- **Documentar lo que se decidió de verdad**, no lo que habría sido correcto decidir. Si una decisión se tomó por una restricción del contexto —el free tier, el tiempo, una limitación del proveedor—, eso es parte del contexto y va escrito.
- **Nada de consecuencias vagas.** "Mejora la mantenibilidad" no es una consecuencia: hay que decir qué se puede hacer ahora que antes no, o qué cuesta más caro a partir de ahora.
- **Sin contradicciones entre ADR.** Si una decisión nueva cambia una anterior, se dice explícitamente cuál y se actualiza el estado del reemplazado; no se reescribe el viejo.
- **Inmutabilidad**: los ADR ya publicados no se editan retroactivamente salvo para corregir su estado o agregar el enlace al que los reemplaza.
- **Sin datos sensibles**: ningún ADR lleva credenciales, claves ni valores reales de secretos. Los placeholders de desarrollo se nombran como tales.
- **En español**, con el mismo tono que los ADR existentes.

## Formato de salida

1. **Tabla del inventario de decisiones**: decisión | ¿lleva ADR? | número asignado | prompt y documentos de origen | motivo si no lleva.
2. **Un documento ADR completo por decisión**, en Markdown, con su nombre de archivo, listo para guardar en `docs/adr/`.
3. **Tabla de alternativas descartadas** consolidada: ADR | alternativa | por qué se descartó | qué la volvería a poner sobre la mesa.
4. **Índice actualizado** de `docs/adr/README.md`.
5. **Relaciones entre ADR**: cuáles se complementan, cuáles reemplazan a otro y cuáles quedaron con el estado cambiado.
