# 25 — Auditoría del pipeline: tests que no corren, secretos expuestos y versiones que no existen

**Etapa:** Revisión

**Salida esperada:** Tabla de hallazgos con riesgo, evidencia, forma de detectarlo y mitigación

---

## Rol

Actúa como revisor de entrega continua, con foco en los pipelines que dan verde sin haber probado nada.

## Contexto

El sistema de reservas de vuelos (Java 21 + Spring Boot 3.5, arquitectura hexagonal, PostgreSQL + Redis + RabbitMQ) tiene un workflow de GitHub Actions recién escrito, que es la entrada de esta auditoría junto con el `Dockerfile` que construye.

**Lo que hay en el repositorio y condiciona el pipeline:**

- Rama principal `main`, ramas de trabajo `moduloN/claseM`, repositorio `andres-sacco/edteam-architecture-with-ai`.
- **`./mvnw test`** corre unitarios sin Docker. **`./mvnw verify`** agrega los `*IT`, que levantan PostgreSQL y RabbitMQ con **Testcontainers** y necesitan Docker: `ReservationApiIT`, `MessagingFlowIT`, `JdbcEventOutboxIT`, `CacheIT`, `ReservationSecurityIT`, `ConsumerResilienceIT`, `OutboxOpsIT`.
- **`HexagonalArchitectureTest`** (ArchUnit) y **`OpenApiContractTest`** custodian invariantes: si el pipeline corre `test` y no `verify`, o si ignora su resultado, esos invariantes dejan de estar custodiados sin que nada lo indique.
- El `Dockerfile` multietapa de [22](22-dockerfile-multietapa.md), auditado en [23](23-auditoria-de-la-imagen.md).
- Variables de entorno obligatorias fuera de local: `SECURITY_DEV_TOKENS=false` con `JWT_JWK_SET_URI`, `PII_ENCRYPTION_KEY` propia, `API_DOCS_ENABLED=false`, `SWAGGER_UI_ENABLED=false`, credenciales de base, Redis y broker.
- **Flyway corre al arrancar** y `ddl-auto: validate` impide levantar si el esquema no coincide.

**Lo que aporta el paso anterior:** el workflow de [24 — Pipeline de CI/CD](24-pipeline-ci-cd.md), con su tabla de etapas, sus secretos y su estrategia de versionado.

La entrada de este prompt son **las dos cosas**: el workflow escrito y el comportamiento real que tiene al ejecutarse.

## Tarea

Auditar el workflow buscando las fallas que pasan inadvertidas en una revisión normal. Como mínimo:

1. **Un pipeline verde que no prueba nada**: ¿los tests corren **antes** del despliegue, o en una rama paralela del grafo que no lo bloquea? ¿Hay algún `continue-on-error`, algún `if: always()` mal puesto o algún paso cuyo código de salida se descarta? ¿Corre `verify` o sólo `test`? Si sólo corre `test`, **ningún test de integración se está ejecutando** y nadie se entera.
2. **Secretos expuestos**: buscar credenciales en texto plano en el YAML, y también las formas indirectas de filtrarlas: un `echo` de una variable, un comando que falla e imprime su entorno, un paso con `set -x`, un artefacto subido que contiene el `.env`, un log de despliegue que vuelca la configuración.
3. **Secretos al alcance de quien no debería**: ¿un pull request desde un fork puede llegar a los secretos de despliegue? ¿El workflow usa un disparador que corre con permisos del repositorio sobre código de terceros?
4. **Permisos del token**: ¿están declarados explícitamente y acotados a lo que el workflow usa, o hereda los permisos por defecto sobre todo el repositorio?
5. **Versionado de la imagen**: ¿cada despliegue queda asociado a una versión identificable, o se despliega una etiqueta móvil? Con `latest` no hay forma de saber qué está corriendo ni de volver atrás; verificar además que el rollback descrito funcione con el esquema de etiquetas elegido.
6. **Acciones de terceros sin fijar**: ¿las acciones están referenciadas por una versión fija o por una etiqueta móvil que alguien más controla y puede cambiar debajo?
7. **Tiempo del pipeline**: medir cuánto tarda el camino completo de `push` a `main`. Un pipeline lento se termina esquivando —con `--no-verify`, con despliegues a mano, con tests marcados como opcionales— y eso es un hallazgo aunque técnicamente funcione. Revisar qué se cachea y qué se vuelve a descargar en cada corrida.
8. **Migraciones y compatibilidad**: ¿qué pasa si una migración de Flyway falla a mitad del despliegue? ¿Qué pasa con la versión anterior mientras convive con la nueva? ¿El rollback de la imagen alcanza si el esquema ya cambió?
9. **Configuración del entorno desplegado**: verificar que el despliegue no quede con los valores de desarrollo activos —tokens HMAC de la clave publicada, clave de PII por defecto, Swagger UI encendida, puerto de Actuator publicado—. Que el `Dockerfile` esté bien no garantiza que el entorno lo esté.
10. **Qué pasa cuando el pipeline falla**: ¿alguien se entera? ¿Un despliegue a medias deja el sistema en un estado conocido?

Para **cada hallazgo**, además del problema, definir **cómo detectarlo de forma concreta** —una prueba que se pueda ejecutar, no una lectura del YAML—. Por ejemplo, y sin limitarse a esto:

- romper un test a propósito, empujar, y verificar que el pipeline falle y que **no** despliegue;
- revisar el workflow y los logs de una corrida buscando credenciales en texto plano y valores de secretos impresos;
- confirmar, contra el registro y contra la plataforma, que el despliegue apunta a una versión de imagen específica y que esa versión existe;
- hacer un rollback real a la versión anterior siguiendo el procedimiento escrito, y medir cuánto tarda;
- medir el tiempo de cada etapa en una corrida en frío y en una con caché;
- consultar `/actuator/health` y el contrato publicado del entorno desplegado para verificar que los interruptores de desarrollo estén apagados.

## Restricciones

- **Sólo hallazgos con evidencia**: cada uno tiene que apuntar a una línea del workflow, a un paso de una corrida real o a la configuración de la plataforma. Nada de riesgos genéricos de manual.
- Distinguir lo que es **una falla real** de lo que es **una limitación asumida y documentada** (que los `*IT` no corran dentro del build de la imagen, por ejemplo): lo segundo se lista aparte, no como hallazgo.
- **Los hallazgos de secretos expuestos son severidad máxima por defecto**, y un secreto que estuvo en un log público se considera comprometido: la mitigación incluye rotarlo, no sólo borrar la línea.
- Priorizar por **impacto**: un despliegue que no prueba y un secreto filtrado pesan más que un pipeline lento; un pipeline tan lento que el equipo lo esquiva pesa más de lo que parece.
- No proponer todavía el código de la solución: este paso identifica y ordena.
- Las pruebas de detección tienen que poder ejecutarse con el repositorio, Docker y la capa gratuita de las plataformas elegidas, sin herramientas pagas.

## Formato de salida

1. **Tabla de hallazgos**: # | hallazgo | categoría (tests / secretos / permisos / versionado / dependencias del workflow / tiempo / migraciones / configuración del entorno / observabilidad del pipeline) | evidencia (línea del workflow, paso de la corrida o configuración) | impacto | severidad.
2. **Tabla de detección**: hallazgo | prueba concreta que lo expone | resultado esperado si está bien | resultado esperado si está mal.
3. **Medición de tiempos**: etapa | en frío | con caché | contra el presupuesto declarado.
4. **Verificación del camino de despliegue**: qué versión de imagen está corriendo, de qué commit salió y si el rollback descrito se pudo ejecutar.
5. **Mitigación propuesta** por hallazgo, en una o dos líneas, sin escribir el workflow corregido.
6. **Limitaciones asumidas**, listadas aparte con el motivo por el que no son hallazgos.
7. **Orden sugerido de remediación**, con el criterio usado para ordenarlo.
