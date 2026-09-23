# 27 — Auditoría de consistencia de los ADRs

**Etapa:** Revisión

**Salida esperada:** Tabla de hallazgos con riesgo, evidencia, forma de detectarlo y mitigación

---

## Rol

Actúa como revisor de documentación de arquitectura, con foco en los ADR que dicen algo distinto de lo que el sistema hace.

## Contexto

El sistema de reservas de vuelos (Java 21 + Spring Boot 3.5, arquitectura hexagonal, PostgreSQL + Redis + RabbitMQ) tiene su conjunto de ADR recién completado, y esa es la entrada de esta auditoría junto con el código que los ADR dicen describir.

**El material a auditar:**

- Los ADR **ya existentes** cuando empezó este trabajo: [0003](../adr/0003-autenticacion-autorizacion-y-datos-sensibles.md) (autenticación, autorización por recurso y datos sensibles), [0004](../adr/0004-mensajeria-asincronica-y-broker.md) (eventos sobre RabbitMQ con outbox en PostgreSQL) y [0005](../adr/0005-garantias-de-entrega-y-remediacion-de-la-mensajeria.md) (idempotencia, dos dead letters y reintentos clasificados).
- Los ADR **redactados en el paso anterior**, [26 — ADRs de las decisiones del proyecto](26-adrs-de-las-decisiones.md): arquitectura hexagonal, modelo de datos, contrato de la API, integración con el catálogo externo, caché, resiliencia, observabilidad y despliegue.
- El **formato y las convenciones** de [`docs/adr/README.md`](../adr/README.md): formato Nygard, numeración correlativa de cuatro dígitos, inmutabilidad, y el enlace al prompt de origen.

**Contra qué se contrasta cada ADR, que es lo que hace a esta auditoría distinta de una lectura:**

- El **código**: `HexagonalArchitectureTest` custodia la regla de dependencias, `OpenApiContractTest` custodia el contrato, las clases de `infrastructure/adapter/out` custodian los timeouts y reintentos declarados, y `application.yml` lleva escritos los valores concretos de caché, outbox, mensajería y seguridad.
- Los **documentos de diseño y auditoría** que alimentaron las decisiones: [`docs/security/threat-model.md`](../security/threat-model.md), [`docs/performance/cache-bottlenecks.md`](../performance/cache-bottlenecks.md), [`docs/messaging/topology.md`](../messaging/topology.md) y [`docs/messaging/audit.md`](../messaging/audit.md).
- Los **prompts** de `docs/prompts/`, que registran qué se pidió y con qué restricciones.

## Tarea

Auditar el conjunto completo de ADR buscando las fallas que pasan inadvertidas en una lectura normal. Como mínimo:

1. **Decisiones contradictorias entre ADR**: dos documentos que afirman cosas incompatibles sobre el mismo tema, sin que ninguno de los dos diga que reemplaza al otro. Revisar especialmente los temas que atraviesan varios ADR —reintentos, idempotencia, degradación ante dependencia caída, manejo de datos sensibles, qué se expone hacia afuera— porque son los que se deciden dos veces.
2. **ADR que no refleja lo que se decidió en realidad**: contrastar cada decisión escrita contra el código y contra la configuración. Un ADR que declara un valor, un mecanismo o una garantía que el código no implementa es peor que no tenerlo, porque se le cree.
3. **Consecuencias vagas o sin relación con la decisión**: marcar cada consecuencia que no se pueda verificar. "Mejora la mantenibilidad", "reduce el acoplamiento" o "facilita el testing" no dicen nada si no señalan qué se puede hacer ahora que antes no, o qué cuesta más caro a partir de ahora.
4. **Consecuencias negativas ausentes**: un ADR que sólo lista beneficios no documentó la decisión, la vendió. Identificar, por cada decisión, la deuda técnica o el riesgo que se aceptó y verificar que esté escrito con nombre.
5. **Alternativas descartadas que no quedan registradas**: qué se evaluó y se dejó de lado, por qué, y bajo qué condición volvería a estar sobre la mesa. Sin eso, la decisión no se puede revisar y alguien la va a volver a discutir desde cero.
6. **Contexto contaminado con la solución**: secciones de contexto que ya narran lo que se decidió, en lugar de las fuerzas que había antes de decidir.
7. **Decisiones tomadas sin ADR**: recorrer el código y la configuración buscando elecciones significativas que nadie documentó. Todo valor que alguien fue a elegir a mano —un TTL, un umbral, un límite de reintentos, un puerto que no se publica, un interruptor apagado por defecto— es candidato.
8. **ADR sobrantes**: documentos que registran detalles de implementación en lugar de decisiones arquitectónicas, y que sólo agregan ruido al conjunto.
9. **Convenciones rotas**: numeración con saltos o duplicados, estados desactualizados, enlaces rotos, ADR reemplazados que siguen figurando como vigentes, o entradas faltantes en el índice.

Para **cada hallazgo**, además del problema, definir **cómo detectarlo de forma concreta**. Por ejemplo, y sin limitarse a esto:

- contrastar cada ADR contra la decisión real tomada en el módulo correspondiente, prompt por prompt;
- pedirle a la IA que audite la consistencia entre todos los ADR a la vez, tema por tema, y liste los pares que se contradicen;
- verificar que cada consecuencia sea concreta y verificable, señalando el archivo, la métrica o el test donde se comprueba;
- confirmar que cada ADR liste explícitamente sus alternativas descartadas;
- tomar cada valor numérico declarado en un ADR y buscarlo en `application.yml` o en el código, comprobando que coincida;
- verificar que los enlaces internos resuelvan y que el índice del README no tenga filas de más ni de menos.

## Restricciones

- **Sólo hallazgos con evidencia**: cada uno tiene que citar el ADR y la sección, y —cuando el hallazgo es una discrepancia con el sistema— el archivo, la línea o la propiedad que la contradice. Nada de observaciones de estilo.
- Distinguir lo que es **una falla real** de lo que es **una decisión deliberada y ya documentada** (que un ADR describa un stub temporal, por ejemplo, o que una decisión se haya tomado por una restricción de free tier): lo segundo se lista aparte, no como hallazgo.
- **La discrepancia entre un ADR y el código es severidad máxima por defecto**: alguien va a tomar una decisión creyéndole al documento.
- Priorizar por **cuánto daño hace creerle al documento equivocado**, no por cuán fácil es corregirlo.
- **Respetar la inmutabilidad**: la mitigación de un ADR incorrecto puede ser un ADR nuevo que lo reemplace, o una corrección de estado, pero no reescribir el documento publicado como si siempre hubiera dicho otra cosa. Cuando la mitigación sea reescribir, hay que justificarlo.
- No redactar todavía los ADR corregidos: este paso identifica y ordena.
- No inventar decisiones: si algo del código no tiene una decisión detrás sino que simplemente quedó así, decirlo como tal.

## Formato de salida

1. **Tabla de hallazgos**: # | hallazgo | categoría (contradicción / discrepancia con el código / consecuencia vaga / consecuencia negativa ausente / alternativa no registrada / contexto contaminado / decisión sin ADR / ADR sobrante / convención) | ADR y sección | evidencia contraria (archivo, línea o propiedad) | impacto | severidad.
2. **Matriz de consistencia entre ADR**: tema transversal | qué dice cada ADR que lo toca | ¿coinciden? | cuál manda.
3. **Tabla de verificación contra el código**: afirmación del ADR | dónde se comprueba | ¿se cumple? | qué dice el código en realidad.
4. **Decisiones sin ADR** encontradas en el código y la configuración, con dónde están y por qué merecerían uno.
5. **Mitigación propuesta** por hallazgo, en una o dos líneas, respetando la inmutabilidad.
6. **Limitaciones asumidas**, listadas aparte con el motivo por el que no son hallazgos.
7. **Orden sugerido de corrección**, con el criterio usado para ordenarlo.
