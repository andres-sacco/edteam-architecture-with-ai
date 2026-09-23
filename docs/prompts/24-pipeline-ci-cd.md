# 24 — Pipeline de CI/CD: prueba, construcción y despliegue automático

**Etapa:** Implementación

**Salida esperada:** Workflow de GitHub Actions completo, en YAML

---

## Rol

Actúa como ingeniero DevOps configurando integración y despliegue continuos.

## Contexto

El sistema de reservas de vuelos es un proyecto Maven con **Java 21 + Spring Boot 3.5** y arquitectura hexagonal, alojado en GitHub (`andres-sacco/edteam-architecture-with-ai`).

**El repositorio y su forma de trabajo:**

- **Rama principal:** `main`. El trabajo llega por ramas `moduloN/claseM` que se integran a `main`.
- **No existe ningún workflow**: no hay directorio `.github/` en el repositorio. Hoy nadie corre los tests salvo a mano.
- El proyecto se construye con el **Maven wrapper** (`./mvnw`), versionado junto con `.mvn/`. Hay un `.sdkmanrc` que fija **JDK 21**.

**Cómo se prueba, que es lo que define la forma del pipeline:**

- **`./mvnw test`** corre sólo los unitarios (`*Test`): rápidos y **sin Docker**. Son la mayoría de los más de sesenta archivos de test del repositorio.
- **`./mvnw verify`** agrega los de integración (`*IT`), que levantan **PostgreSQL y RabbitMQ reales con Testcontainers** y **sí necesitan Docker**: `ReservationApiIT`, `MessagingFlowIT`, `JdbcEventOutboxIT`, `CacheIT`, `ReservationSecurityIT`, `ConsumerResilienceIT`, `OutboxOpsIT`, entre otros.
- Dos tests custodian invariantes de arquitectura y de contrato, y su rotura tiene que frenar el pipeline: **`HexagonalArchitectureTest`** (ArchUnit: que la infraestructura no se filtre hacia adentro) y **`OpenApiContractTest`** (que el contrato publicado en `docs/api/openapi.yaml` coincida con el que genera springdoc desde el código).
- La suite no necesita credenciales externas: los tokens son HMAC de desarrollo, el catálogo externo se reemplaza por un stub cuando `base-url` está vacío, y la mensajería se apaga por propiedad.

**Lo que aporta el paso anterior:** el `Dockerfile` multietapa y el `.dockerignore` de [22](22-dockerfile-multietapa.md), ya auditados en [23](23-auditoria-de-la-imagen.md). El pipeline construye **esa** imagen, no una propia.

**Lo que la aplicación necesita del entorno de despliegue:**

- Toda la configuración entra por **variables de entorno** (`DB_*`, `REDIS_*`, `RABBIT_*`, `CATALOG_*`, `JWT_*`, `PII_ENCRYPTION_KEY`, `MANAGEMENT_PORT`, `CORS_ALLOWED_ORIGINS`), documentadas en `.env.example`. El `.env` real no se versiona.
- **Flyway corre al arrancar** y `ddl-auto: validate` impide levantar si el esquema no coincide: un despliegue con una migración nueva cambia el esquema en el momento del arranque.
- Fuera de local hay valores que **tienen que cambiar**: `SECURITY_DEV_TOKENS=false` con `JWT_JWK_SET_URI` configurado, una `PII_ENCRYPTION_KEY` propia, `API_DOCS_ENABLED=false`, `SWAGGER_UI_ENABLED=false` y el puerto de Actuator sin publicar.

## Tarea

1. **Definir los disparadores**: qué corre en un `push` a una rama de trabajo, qué corre en un *pull request* hacia `main` y qué corre en un `push` a `main`. Justificar por qué cada cosa corre donde corre.
2. **Etapa de integración continua (CI)**: compilar con JDK 21, correr los unitarios y correr los de integración con Docker. Los tests tienen que correr **antes** de construir la imagen y **antes** de cualquier despliegue, y su fallo tiene que frenar todo lo que sigue.
3. **Publicar los resultados**: reporte de tests visible en el workflow, y el build marcado como fallido cuando `HexagonalArchitectureTest` o `OpenApiContractTest` se rompen, con el motivo legible sin abrir el log completo.
4. **Etapa de entrega continua (CD)**: construir la imagen del `Dockerfile` del repositorio y publicarla en un registro, **etiquetada con una versión identificable** —el SHA del commit y/o una versión semántica—, nunca sólo `latest`.
5. **Etapa de despliegue**: desplegar en la plataforma elegida en cada `push` a `main`, referenciando la versión exacta de la imagen que se acaba de publicar. Elegir la plataforma entre las que tienen capa gratuita para contenedores y justificar la elección.
6. **Gestión de secretos**: todas las credenciales —registro de imágenes, plataforma de despliegue, base de datos, clave de cifrado de PII, JWKS— vienen de los *secrets* de GitHub y de la configuración de la plataforma. Enumerar cuáles hacen falta y qué hace cada una.
7. **Migraciones de base**: decidir explícitamente cómo se aplican las de Flyway en el despliegue, qué pasa si una falla a mitad y si el despliegue es compatible con la versión anterior mientras convive con ella.
8. **Tiempo del pipeline**: cachear el repositorio Maven y las capas de la imagen, y paralelizar lo que se pueda. Declarar cuánto tarda cada etapa y cuál es el objetivo para el camino completo de `push` a `main`.
9. **Rollback**: dejar escrito cómo se vuelve a la versión anterior, y verificar que el esquema de etiquetado lo permita sin reconstruir nada.

## Restricciones

- **Nunca desplegar sin haber corrido los tests.** Un pipeline que despliega con los tests en otra rama del grafo, o con `continue-on-error`, es un pipeline verde que no prueba nada.
- **Ninguna credencial en texto plano en el workflow.** Ni en un `env`, ni en un `run`, ni en un comentario. Todo por `secrets`.
- **Los secretos no se imprimen**: cuidar que ningún paso los vuelque al log, ni siquiera dentro de un comando que falla.
- **Cada despliegue queda asociado a una versión de imagen específica.** Nada de desplegar `latest`: sin versión identificable no hay rollback posible ni forma de saber qué está corriendo.
- **El pipeline tiene que ser lo bastante rápido como para que nadie lo esquive.** Declarar el presupuesto de tiempo y decir qué se cachea para cumplirlo.
- **Free tier**: el registro, la plataforma de despliegue y los *runners* tienen que tener capa gratuita. Nada que exija un plan pago.
- **Permisos mínimos**: el token del workflow declara explícitamente los permisos que usa, y no más.
- **Versiones fijadas**: las acciones de terceros se referencian con una versión fija, no con una etiqueta móvil.
- **No se despliega desde una rama de trabajo ni desde un pull request de un fork**: un PR externo no puede llegar a los secretos de despliegue.
- Coherencia con lo ya decidido: configuración por variables de entorno, Actuator en un puerto que el despliegue no publica, y `SECURITY_DEV_TOKENS`, `API_DOCS_ENABLED` y `SWAGGER_UI_ENABLED` apagados fuera de local.

## Formato de salida

1. **Archivo YAML de GitHub Actions completo**, comentado, listo para guardar en `.github/workflows/`.
2. **Tabla de etapas**: etapa | cuándo corre | qué hace | qué la hace fallar | tiempo estimado.
3. **Tabla de secretos**: nombre | para qué se usa | dónde se configura | qué pasa si falta.
4. **Decisión de plataforma de despliegue**: la elegida, las alternativas descartadas y el motivo, incluyendo qué ofrece cada una en su capa gratuita.
5. **Estrategia de versionado de la imagen** y **procedimiento de rollback**, paso a paso.
6. **Cómo se aplican las migraciones** en el despliegue y qué pasa si una falla.
7. **Comandos y verificaciones**: cómo probar el workflow, cómo confirmar a propósito que el pipeline falla si un test falla, y cómo verificar qué versión de imagen está corriendo en la plataforma.
