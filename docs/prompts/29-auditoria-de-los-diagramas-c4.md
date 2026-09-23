# 29 — Auditoría de los diagramas C4: sintaxis, consistencia y fidelidad al sistema

**Etapa:** Revisión

**Salida esperada:** Tabla de hallazgos con riesgo, evidencia, forma de detectarlo y mitigación

---

## Rol

Actúa como revisor de documentación de arquitectura, con foco en los diagramas que dibujan un sistema que no es el que está corriendo.

## Contexto

El sistema de reservas de vuelos (Java 21 + Spring Boot 3.5, arquitectura hexagonal, PostgreSQL + Redis + RabbitMQ) tiene sus diagramas C4 de Contexto y de Contenedores recién generados en Mermaid, y ésa es la entrada de esta auditoría junto con el sistema que dicen representar.

**El material a auditar:**

- Los **dos bloques Mermaid** del paso anterior, [28 — Diagramas C4 de Contexto y Contenedores](28-diagramas-c4-mermaid.md), con su tabla de elementos y su tabla de relaciones.

**Contra qué se contrasta, que es lo que hace a esta auditoría distinta de mirar el dibujo:**

- El **código y la configuración**: `compose.yaml` (PostgreSQL 17, Redis 7, RabbitMQ 4, `api-catalog` y su MySQL), `application.yml` (puertos `8080` y `9090`, `MESSAGING_ENABLED`, `CACHE_REDIS_ENABLED`, `CATALOG_BASE_URL`, `JWT_JWK_SET_URI`) y los adaptadores de `infrastructure/adapter/in` y `infrastructure/adapter/out`, que son la lista real de por dónde entra y sale información.
- Los **ADR** de [`docs/adr/`](../adr/README.md) y los redactados en [26](26-adrs-de-las-decisiones.md), auditados en [27](27-auditoria-de-los-adrs.md).
- La **topología de mensajería** de [`docs/messaging/topology.md`](../messaging/topology.md): exchange `reservations.events`, los cuatro hechos de negocio, la cola de espera y las dos dead letters.
- Los **prompts anteriores** de este repositorio, que registran qué se decidió en cada clase: caché ([09](09-cuellos-de-botella-cache.md), [10](10-implementacion-de-cache.md)), seguridad ([11](11-auditoria-de-seguridad-stride.md), [12](12-remediacion-de-seguridad.md)), mensajería ([13](13-topologia-de-eventos-y-colas.md)–[15](15-implementacion-de-mensajeria.md)), resiliencia ([16](16-diseno-de-resiliencia.md)–[18](18-implementacion-de-resiliencia.md)), observabilidad ([19](19-diseno-de-observabilidad.md)–[21](21-implementacion-de-observabilidad.md)) y despliegue ([22](22-dockerfile-multietapa.md)–[25](25-auditoria-del-pipeline.md)).

## Tarea

Auditar los dos diagramas buscando las fallas que pasan inadvertidas cuando uno mira un dibujo que "se entiende". Como mínimo:

1. **Sintaxis Mermaid inválida**: verificar que ambos bloques rendericen. Un diagrama que no llega a dibujarse es un archivo de texto que nadie va a arreglar, y el error suele estar en una sola línea.
2. **Elementos que no coinciden con lo decidido en clases anteriores**: contrastar cada contenedor, cada sistema externo y cada relación contra el código y los ADR. Marcar lo que está en el diagrama y no en el sistema, y lo que está en el sistema y no en el diagrama.
3. **El C1 y el C2 se contradicen**: un sistema externo que aparece en uno y no en el otro, un nombre que cambia entre niveles, una relación que en el contexto va en un sentido y en contenedores en el otro, o un actor que desaparece sin explicación.
4. **Componentes mencionados en el texto que faltan en el diagrama**: recorrer las tablas de elementos y de relaciones del paso anterior y verificar que todo lo que listan esté efectivamente dibujado, y al revés.
5. **Relaciones sin etiqueta o mal etiquetadas**: flechas sin decir qué viaja ni sobre qué protocolo, o con un protocolo que no es el que usa el código.
6. **Dirección equivocada**: verificar contra los adaptadores quién llama a quién. El relay publica hacia el broker y el consumidor recibe; el catálogo lo llama este sistema; al IdP se le consulta el JWKS. Una flecha invertida cambia por completo qué se entiende del acoplamiento.
7. **Nivel de abstracción mezclado**: bases de datos o brokers en el diagrama de contexto, o paquetes y clases en el de contenedores.
8. **Lo opcional dibujado como obligatorio**: Redis y el broker son dependencias de las que el sistema no depende para responder. Un diagrama que los muestra igual que a PostgreSQL le miente al lector sobre la decisión central de degradación del sistema.
9. **Sistema desactualizado respecto del diagrama**: revisar si alguna decisión reciente —el despliegue en contenedor, el consumidor de referencia, los endpoints de operaciones— cambió el mapa y no se reflejó.
10. **Legibilidad sin contexto**: verificar que alguien que no conoce el proyecto entienda qué hace el sistema y con quién habla leyendo sólo el diagrama.

Para **cada hallazgo**, además del problema, definir **cómo detectarlo de forma concreta**. Por ejemplo, y sin limitarse a esto:

- renderizar el código en un visor de Mermaid antes de darlo por válido, y anotar la línea exacta del error si no renderiza;
- pedirle a la IA que liste los componentes y las relaciones que **el diagrama efectivamente dibuja** en una tabla, y contrastar esa tabla contra la tabla de elementos declarada y contra el código;
- recorrer `compose.yaml` y los adaptadores de `infrastructure/adapter/in` y `out` y verificar que cada entrada y cada salida tenga su flecha;
- comparar elemento por elemento el C1 contra el C2 buscando nombres, roles y direcciones que no coincidan;
- revisar el diagrama con alguien que no trabajó en el proyecto y ver qué preguntas necesita hacer para entenderlo: cada pregunta es una etiqueta que falta.

## Restricciones

- **Sólo hallazgos con evidencia**: cada uno tiene que citar la línea del bloque Mermaid y —cuando el hallazgo es una discrepancia con el sistema— el archivo, la propiedad o el ADR que lo contradice. Nada de observaciones estéticas.
- Distinguir lo que es **una falla real** de lo que es **una simplificación deliberada del nivel** (que el C1 no muestre la base, por ejemplo, es correcto y no un hallazgo): lo segundo se lista aparte.
- **Una discrepancia entre el diagrama y el sistema es severidad máxima por defecto**: un diagrama se usa para decidir, y es el primer documento que alguien nuevo lee.
- **Un diagrama que no renderiza es bloqueante**, aunque su contenido sea correcto.
- Priorizar por **cuánto daño hace creerle al diagrama equivocado**, no por cuán fácil es corregirlo.
- No redactar todavía los diagramas corregidos: este paso identifica y ordena.
- La verificación de sintaxis tiene que poder hacerse con un visor de Mermaid gratuito o con el renderizado del propio repositorio, sin herramientas pagas.

## Formato de salida

1. **Resultado del renderizado** de cada bloque: ¿renderiza? | error exacto y línea si no.
2. **Tabla de hallazgos**: # | hallazgo | categoría (sintaxis / elemento faltante / elemento sobrante / contradicción entre niveles / etiqueta / dirección / nivel de abstracción / opcionalidad / legibilidad) | dónde (bloque y línea) | evidencia contraria (archivo, propiedad o ADR) | impacto | severidad.
3. **Tabla de componentes y relaciones extraída del diagrama**, contrastada contra el código y contra la tabla declarada: elemento o relación | ¿está en el diagrama? | ¿está en el sistema? | ¿coinciden nombre, protocolo y dirección?
4. **Tabla de detección**: hallazgo | verificación concreta que lo expone | resultado esperado si está bien | resultado esperado si está mal.
5. **Mitigación propuesta** por hallazgo, en una o dos líneas, sin reescribir el diagrama.
6. **Simplificaciones deliberadas**, listadas aparte con el motivo por el que no son hallazgos.
7. **Orden sugerido de corrección**, con el criterio usado para ordenarlo.
