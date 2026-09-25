# 25 — Auditoría del pipeline: tests que no corren, gates que no frenan y métricas que mienten

**Etapa:** Revisión

**Salida esperada:** Tabla de hallazgos con riesgo, evidencia, forma de detectarlo y mitigación

---

## Rol

Actúa como revisor de integración y entrega continuas, con foco en los pipelines que dan verde sin haber probado nada.

## Contexto

El sistema de reservas de vuelos (Java 21 + Spring Boot 3.5, arquitectura hexagonal, PostgreSQL + Redis + RabbitMQ) tiene un workflow de GitHub Actions recién escrito, que es la entrada de esta auditoría junto con el `Dockerfile` que construye y los cambios que el pipeline introdujo en el `pom.xml`.

**Lo que hay en el repositorio y condiciona el pipeline:**

- Rama principal `main`, ramas de trabajo `moduloN/claseM`, repositorio `andres-sacco/edteam-architecture-with-ai`.
- **`./mvnw test`** corre 650 unitarios sin Docker. **`./mvnw verify`** agrega 148 `*IT`, que levantan PostgreSQL y RabbitMQ con **Testcontainers** y necesitan Docker: `ReservationApiIT`, `MessagingFlowIT`, `JdbcEventOutboxIT`, `CacheIT`, `ReservationSecurityIT`, `ConsumerResilienceIT`, `OutboxOpsIT`. `verify` corre además el **gate de datos sensibles** (`scripts/pii-log-gate.sh`).
- **`HexagonalArchitectureTest`** (ArchUnit) y **`OpenApiContractTest`** custodian invariantes: si el pipeline ignora su resultado, esos invariantes dejan de estar custodiados sin que nada lo indique.
- **`OpenApiContractTest` no compara contra `docs/api/openapi.yaml`**: compara el documento que genera springdoc contra el *código*. La deriva del archivo versionado la tiene que cubrir el pipeline, no ese test.
- El `Dockerfile` multietapa de [22](22-dockerfile-multietapa.md), auditado en [23](23-auditoria-de-la-imagen.md), que se construye con `-DskipTests` a propósito.
- El pipeline agregó al `pom.xml` **medición de cobertura** y **escaneo de dependencias**, y una **plataforma de calidad** externa. Esas tres cosas producen números, y un número mal medido es peor que ninguno: parece información.

**Lo que aporta el paso anterior:** el workflow de [24 — Pipeline de CI](24-pipeline-ci-cd.md), con su tabla de etapas, sus secretos, su estrategia de etiquetado y su topología de gates declarada.

**Alcance: este pipeline no despliega.** Termina en la imagen publicada en un registro. La auditoría termina ahí también: el objeto a verificar es **el artefacto y la cadena que lo produjo**, no un entorno en ejecución. Lo que hay que poder afirmar al final es que la imagen publicada salió de un commit cuyos tests pasaron, y que se puede demostrar de qué commit salió.

La entrada de este prompt son **las dos cosas**: el workflow escrito y el comportamiento real que tiene al ejecutarse.

## Tarea

Auditar el workflow buscando las fallas que pasan inadvertidas en una revisión normal. Como mínimo:

1. **Un pipeline verde que no prueba nada**: ¿los tests corren **antes** de construir y publicar la imagen, o en una rama paralela del grafo que no la bloquea? ¿Hay algún `continue-on-error`, algún `if: always()` mal puesto o algún paso cuyo código de salida se descarta? ¿Corre `verify` o sólo `test`? Si sólo corre `test`, **ningún test de integración se está ejecutando** y nadie se entera.
2. **Gates decorativos**: para cada control de calidad y de seguridad, verificar que **pueda dejar la corrida en rojo**. Una herramienta que sube métricas y termina siempre en verde no es un gate: ¿el análisis espera el veredicto del *quality gate* o termina apenas subió los datos? ¿El escaneo de dependencias tiene un umbral que falla, o sólo genera un informe? ¿Y si el gate no bloquea la publicación de la imagen, está declarado como *required status check* en algún lado, o no frena absolutamente nada?
3. **Métricas que mienten**: el hallazgo más difícil de ver, porque el pipeline está verde y hay un número en pantalla.
   - ¿La **cobertura** incluye lo que ejercitan los tests de integración, o sólo los unitarios? Medir una sola fase deja a los adaptadores de salida —los que hablan con PostgreSQL y RabbitMQ— pareciendo sin pruebas, y el número resultante es varias decenas de puntos más bajo que la realidad.
   - ¿El **escaneo de dependencias** ve el árbol resuelto con sus transitivas, o escanea un directorio de trabajo donde puede no haber un solo `.jar`? Un escaneo sin nada que escanear reporta cero vulnerabilidades.
   - ¿La **base de vulnerabilidades** se actualiza? Una caché cuya clave nunca cambia congela la base en el día en que se creó, y a partir de ahí el verde sólo dice que no había CVE publicadas entonces.
   - ¿El **conteo de tests** que reporta la plataforma coincide con los que la suite corre de verdad?
4. **Secretos expuestos**: buscar credenciales en texto plano en el YAML, y también las formas indirectas de filtrarlas: un `echo` de una variable, un secreto pasado por la línea de comandos de un proceso, un comando que falla e imprime su entorno, un paso con `set -x`, un artefacto subido que contiene el `.env` o un informe con configuración adentro.
5. **Secretos al alcance de quien no debería**: ¿un pull request desde un fork puede llegar a los secretos? ¿El workflow usa un disparador que corre con permisos del repositorio sobre código de terceros? ¿Los trabajos que sí necesitan un secreto se saltean limpiamente cuando no está, o fallan con un error que no dice nada?
6. **Permisos del token**: ¿están declarados explícitamente, por trabajo y acotados a lo que ese trabajo usa, o hereda los permisos por defecto sobre todo el repositorio?
7. **Versionado y trazabilidad de la imagen**: ¿cada imagen queda asociada a una versión identificable, o sólo se publica una etiqueta móvil? Con `latest` no hay forma de saber de qué commit salió un artefacto. Verificar que se pueda ir **de la imagen publicada al commit** y al revés, y que lo que se referencia para consumirla sea inmutable y no un puntero reapuntable.
8. **Acciones de terceros sin fijar**: ¿las acciones están referenciadas **por SHA de commit**, o por una etiqueta —o peor, una rama— que alguien más controla y puede cambiar debajo? La acción corre en el mismo runner que el código y, en los trabajos que publican, junto a las credenciales de publicación.
9. **Tiempo del pipeline**: medir cuánto tarda el camino completo de `push` a `main`. Un pipeline lento se termina esquivando —con `--no-verify`, con builds a mano, con tests marcados como opcionales— y eso es un hallazgo aunque técnicamente funcione. Revisar qué se cachea, qué se vuelve a descargar en cada corrida y si alguna caché está mal invalidada (una que nunca se renueva y otra que nunca acierta son dos problemas distintos).
10. **Qué pasa cuando el pipeline falla**: ¿alguien se entera? Cuando se rompe uno de los guardianes o un gate, ¿el motivo se lee **sin abrir el log completo**? Un fallo que obliga a leer cuatro mil líneas se responde con «rerun», que es la forma en que un pipeline deja de usarse sin que nadie lo apague.

Para **cada hallazgo**, además del problema, definir **cómo detectarlo de forma concreta** —una prueba que se pueda ejecutar, no una lectura del YAML—. Por ejemplo, y sin limitarse a esto:

- romper un test a propósito, empujar, y verificar que el pipeline falle y que **no publique** la imagen;
- romper uno de los dos guardianes y verificar que el motivo aparezca en el panel de la corrida, sin abrir el log;
- comparar la cobertura que reporta la plataforma contra la que se obtiene midiendo **sólo** los unitarios: si dan lo mismo, los tests de integración no se están contando;
- contrastar la cantidad de tests que reporta la plataforma contra los que imprime `./mvnw verify`;
- agregar a propósito una dependencia con una vulnerabilidad conocida y verificar que el escaneo pase a rojo;
- bajar a propósito la cobertura de un archivo nuevo y verificar que el *quality gate* pase a rojo, no sólo que el número baje;
- revisar el workflow y los logs de una corrida buscando credenciales en texto plano y valores de secretos impresos;
- abrir un pull request desde un fork y verificar qué trabajos corren, cuáles se saltean y a qué secretos llegan;
- resolver el digest de la imagen publicada y verificar que sus etiquetas OCI apunten al commit que la produjo;
- medir el tiempo de cada etapa en una corrida en frío y en una con caché, contra el presupuesto declarado.

## Restricciones

- **Sólo hallazgos con evidencia**: cada uno tiene que apuntar a una línea del workflow, a una línea del `pom.xml`, a un paso de una corrida real o a lo que devuelve el registro. Nada de riesgos genéricos de manual.
- Distinguir lo que es **una falla real** de lo que es **una limitación asumida y documentada**: que los `*IT` no corran dentro del build de la imagen, o que un gate no bloquee la publicación **si está declarado que bloquea el merge del pull request**, son decisiones, no hallazgos. Se listan aparte. Lo que sí es hallazgo es un gate que no frena en **ningún** lado.
- **Una métrica mal medida pesa igual que un test que no corre.** Las dos producen un verde que no significa lo que parece, y la segunda al menos no se cita en una reunión.
- **Los hallazgos de secretos expuestos son severidad máxima por defecto**, y un secreto que estuvo en un log público se considera comprometido: la mitigación incluye rotarlo, no sólo borrar la línea.
- Priorizar por **impacto**: una imagen publicada sin pruebas y un secreto filtrado pesan más que un pipeline lento; un pipeline tan lento que el equipo lo esquiva pesa más de lo que parece.
- No proponer todavía el código de la solución: este paso identifica y ordena.
- Las pruebas de detección tienen que poder ejecutarse con el repositorio, Docker y la capa gratuita del registro y de las herramientas elegidas, sin nada pago.

## Formato de salida

1. **Tabla de hallazgos**: # | hallazgo | categoría (tests / gates / métricas / secretos / permisos / versionado / dependencias del workflow / tiempo / observabilidad del pipeline) | evidencia (línea del workflow o del `pom.xml`, paso de la corrida, respuesta del registro) | impacto | severidad.
2. **Tabla de detección**: hallazgo | prueba concreta que lo expone | resultado esperado si está bien | resultado esperado si está mal.
3. **Medición de tiempos**: etapa | en frío | con caché | contra el presupuesto declarado.
4. **Verificación de la trazabilidad del artefacto**: qué imagen se publicó, cuál es su digest, de qué commit salió y con qué comando se comprueba cada cosa.
5. **Estado real de cada gate**: control | ¿puede dejar la corrida en rojo? | ¿qué frena exactamente? | cómo se comprobó.
6. **Mitigación propuesta** por hallazgo, en una o dos líneas, sin escribir el workflow corregido.
7. **Limitaciones asumidas**, listadas aparte con el motivo por el que no son hallazgos.
8. **Orden sugerido de remediación**, con el criterio usado para ordenarlo.
