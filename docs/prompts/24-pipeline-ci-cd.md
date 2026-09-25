# 24 — Pipeline de CI: prueba, calidad, seguridad y publicación de la imagen

**Etapa:** Implementación

**Salida esperada:** Workflow de GitHub Actions completo, en YAML

---

## Rol

Actúa como ingeniero DevOps configurando integración continua y publicación de artefactos.

## Contexto

El sistema de reservas de vuelos es un proyecto Maven con **Java 21 + Spring Boot 3.5** y arquitectura hexagonal, alojado en GitHub (`andres-sacco/edteam-architecture-with-ai`).

**El repositorio y su forma de trabajo:**

- **Rama principal:** `main`. El trabajo llega por ramas `moduloN/claseM` que se integran a `main`.
- **No existe ningún workflow**: no hay directorio `.github/` en el repositorio. Hoy nadie corre los tests salvo a mano.
- El proyecto se construye con el **Maven wrapper** (`./mvnw`), versionado junto con `.mvn/`. El wrapper es `distributionType=only-script`: **no hay ningún `.jar` versionado en el repositorio**. Hay un `.sdkmanrc` que fija **JDK 21**.

**Cómo se prueba, que es lo que define la forma del pipeline:**

- **`./mvnw test`** corre sólo los unitarios (`*Test`): **650 tests, sin Docker y sin red**, en ~13 s sobre una máquina de escritorio.
- **`./mvnw verify`** agrega los de integración (`*IT`): **148 tests más** que levantan **PostgreSQL y RabbitMQ reales con Testcontainers** (`postgres:17-alpine`, `rabbitmq:4-management-alpine`) y **sí necesitan Docker**. El total son ~43 s. Entre ellos `ReservationApiIT`, `MessagingFlowIT`, `JdbcEventOutboxIT`, `CacheIT`, `ReservationSecurityIT`, `ConsumerResilienceIT`, `OutboxOpsIT`.
- `verify` corre además el **gate de datos sensibles** (`scripts/pii-log-gate.sh`, vía `exec-maven-plugin`): grepea la salida capturada de toda la suite contra patrones de datos personales y falla el build si encuentra uno.
- Dos tests custodian invariantes y su rotura tiene que frenar el pipeline: **`HexagonalArchitectureTest`** (ArchUnit: que la infraestructura no se filtre hacia adentro) y **`OpenApiContractTest`**. Los dos están en **surefire**, o sea que corren con `./mvnw test`.
- **Cuidado con qué verifica `OpenApiContractTest`**: compara el documento que genera springdoc contra el **código** (handler mappings, códigos de estado del `@RestControllerAdvice`, esquemas de seguridad). **No** lo compara contra `docs/api/openapi.yaml`. El archivo versionado que leen los partners puede quedar atrasado sin que ningún test falle. Quien lo regenera es `OpenApiDocumentDumpTest`, **apagado por defecto** (`@EnabledIfSystemProperty(named = "openapi.dump")`), porque un test que escribe en el repositorio no puede correr en cada build.
- La suite no necesita credenciales externas: los tokens son HMAC de desarrollo, el catálogo externo se reemplaza por un stub cuando `base-url` está vacío, y la mensajería se apaga por propiedad.

**Lo que NO hay hoy, y este paso tiene que agregar:**

- **Nada mide cobertura.** El `pom.xml` no tiene JaCoCo ni ningún otro agente: hoy no existe un número de cobertura, ni bueno ni malo.
- **Nada analiza el código estáticamente.** No hay integración con ninguna plataforma de calidad.
- **Nada mira las dependencias.** Ningún paso cruza el árbol de dependencias contra una base de vulnerabilidades conocidas.

**Lo que aporta el paso anterior:** el `Dockerfile` multietapa y el `.dockerignore` de [22](22-dockerfile-multietapa.md), ya auditados en [23](23-auditoria-de-la-imagen.md). El pipeline construye **esa** imagen, no una propia. La imagen se construye con `-DskipTests` a propósito: los tests corren en el pipeline, en un paso anterior, y los de integración necesitan un demonio Docker que dentro del build obligaría a Docker-in-Docker.

**Alcance: este pipeline NO despliega.** Termina en el artefacto publicado. Decidir dónde y cómo se ejecuta esa imagen es otro problema, con otro ciclo de vida y otros permisos; mezclarlo acá haría que cada cambio en la estrategia de ejecución tocara el archivo que prueba el código. Lo que este paso tiene que garantizar es que **la imagen publicada sea exactamente la de un commit cuyos tests pasaron**, y que se pueda saber de qué commit salió.

## Tarea

1. **Definir los disparadores**: qué corre en un `push` a una rama de trabajo, qué corre en un *pull request* hacia `main` y qué corre en un `push` a `main`. Justificar por qué cada cosa corre donde corre, incluyendo por qué un PR y un push a la misma rama **no** prueban el mismo commit.
2. **Compilar y probar**: JDK 21, los unitarios y los de integración con Docker. Los tests tienen que correr **antes** de construir la imagen, y su fallo tiene que frenar todo lo que sigue.
3. **Publicar los resultados**: reporte de tests visible en el workflow, y el build marcado como fallido cuando `HexagonalArchitectureTest` o `OpenApiContractTest` se rompen, **con el motivo legible sin abrir el log completo**. Resolver además la brecha del contrato: que `docs/api/openapi.yaml` no pueda quedar atrasado respecto del código sin que el pipeline lo diga.
4. **Cobertura y análisis estático**: instrumentar la suite y publicar las métricas en una plataforma de calidad con capa gratuita. La cobertura tiene que reflejar **lo que cubren también los tests de integración** —son los que ejercitan los adaptadores de salida contra PostgreSQL y RabbitMQ reales— y no sólo los unitarios. Definir qué hace fallar el análisis y sobre qué código se juzga.
5. **Seguridad de las dependencias**: cruzar el árbol de dependencias contra las vulnerabilidades conocidas y publicar el informe. El escaneo tiene que ver las **dependencias resueltas, transitivas incluidas** —que son las que terminan adentro de la imagen— y no el contenido del directorio de trabajo. Definir el umbral que rompe el build y por dónde sale un falso positivo.
6. **Publicar la imagen**: construir la del `Dockerfile` del repositorio y publicarla en un registro con capa gratuita, **etiquetada con una versión identificable** —el SHA del commit y/o una versión semántica—, nunca sólo `latest`. Elegir el registro y justificarlo.
7. **Gestión de secretos**: enumerar los que hacen falta, para qué sirve cada uno y qué pasa si falta. Preferir los mecanismos que **no requieren crear una credencial de larga duración**.
8. **Tiempo del pipeline**: cachear lo que se pueda cachear —repositorio Maven, capas de la imagen, lo que descarguen las herramientas de análisis— y paralelizar lo que se pueda paralelizar. Declarar cuánto tarda cada etapa y cuál es el objetivo para el camino completo de `push` a `main`.
9. **Topología de los gates**: dejar explícito qué trabajo bloquea a qué otro y por qué. Para cada gate de calidad o de seguridad, decir **dónde frena**: si impide publicar la imagen, si impide mergear el pull request, o si no frena nada. Un gate que no frena en ningún lado es un informe, y hay que decirlo así.

## Restricciones

- **Nunca publicar una imagen sin haber corrido los tests.** Un pipeline que publica con los tests en otra rama del grafo, o con `continue-on-error`, es un pipeline verde que no prueba nada.
- **Ningún gate decorativo.** Si un paso de calidad o de seguridad se declara como control, tiene que poder dejar la corrida en rojo. Una herramienta que sube métricas y termina siempre en verde se documenta como informe, no como gate.
- **Ninguna credencial en texto plano en el workflow.** Ni en un `env`, ni en un `run`, ni en un comentario. Todo por `secrets`.
- **Los secretos no se imprimen**: cuidar que ningún paso los vuelque al log, ni siquiera dentro de un comando que falla, y que ninguno viaje por la línea de comandos de un proceso.
- **Cada imagen queda asociada a una versión identificable.** Nada de publicar sólo `latest`: sin versión identificable no hay forma de saber de qué commit salió un artefacto.
- **El pipeline tiene que ser lo bastante rápido como para que nadie lo esquive.** Declarar el presupuesto de tiempo y decir qué se cachea para cumplirlo.
- **Free tier**: el registro, los *runners*, la plataforma de calidad y la base de vulnerabilidades tienen que tener capa gratuita. Nada que exija un plan pago.
- **Permisos mínimos**: el token del workflow declara explícitamente los permisos que usa, y no más, por trabajo y no globalmente.
- **Versiones fijadas**: las acciones de terceros se referencian **por SHA de commit**, no por una etiqueta móvil. Una etiqueta la puede reapuntar quien tenga push en ese repositorio, y la acción corre en el mismo runner que el código.
- **No se publica desde una rama de trabajo ni desde un pull request de un fork**: un PR externo no puede llegar a credenciales de publicación.
- Coherencia con lo ya decidido: la imagen es la del `Dockerfile` del repositorio, construida sin tests adentro, y el pipeline es quien garantiza que ese artefacto ya fue probado.

## Formato de salida

1. **Archivo YAML de GitHub Actions completo**, comentado, listo para guardar en `.github/workflows/`.
2. **Tabla de etapas**: etapa | cuándo corre | qué hace | qué la hace fallar | tiempo estimado.
3. **Tabla de secretos**: nombre | para qué se usa | dónde se configura | qué pasa si falta.
4. **Decisión de registro de imágenes**: el elegido, las alternativas descartadas y el motivo, incluyendo qué ofrece cada uno en su capa gratuita.
5. **Estrategia de etiquetado**: qué etiquetas se publican, cuál se usa para referenciar la imagen de forma inequívoca y por qué.
6. **Configuración de cobertura y de los gates**: cómo se mide la cobertura sobre las dos fases de test, qué umbral rompe el build en cada gate y **cuál es la salida correcta cuando uno falla** —incluyendo dónde se documenta un falso positivo—.
7. **Comandos y verificaciones**: cómo probar el workflow, cómo confirmar a propósito que el pipeline falla si un test falla, y cómo verificar de qué commit salió una imagen publicada.
