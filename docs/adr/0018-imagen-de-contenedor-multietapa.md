# 0018 — Imagen de contenedor multietapa, por capas y sin privilegios

- **Estado:** Aceptado
- **Fecha:** 2026-09-25
- **Prompt origen:** [22 — Dockerfile multietapa y `.dockerignore`](../prompts/22-dockerfile-multietapa.md)
- **Auditada en:** [23 — Auditoría de la imagen](../prompts/23-auditoria-de-la-imagen.md)
- **Custodiado por:** `Dockerfile`, `.dockerignore`, y el trabajo `image` del workflow, que la construye en cada pull request

## Contexto

La aplicación corría con `./mvnw spring-boot:run`: **no había `Dockerfile` ni
`.dockerignore`**, y el `compose.yaml` levantaba las dependencias pero no el
servicio. Para poder desplegarla en cualquier lado hacía falta un artefacto, y
el contexto de build tenía tres cosas que lo volvían peligroso escribirlo mal:

- **Un `.env` en la raíz, no versionado, con credenciales reales de la máquina de
  desarrollo.** Y `application.yml` hace
  `spring.config.import: optional:file:./.env[.properties]`, o sea que **si el
  archivo entra en la imagen, la aplicación lo lee adentro del contenedor**.
- `target/`, `.git/` con todo el historial, `.idea/` y `docs/` en el mismo
  directorio.
- Placeholders de desarrollo reconocibles en `application.yml` —tokens HMAC con
  una clave publicada en el repositorio, una clave de cifrado por defecto— que no
  son secretos pero son peligrosos si quedan activos.

Además la aplicación tiene comportamientos que la imagen no puede romper:
apagado elegante de 25 s, dos puertos donde uno no se publica, Flyway corriendo
al arrancar, y arranque posible sin Redis, sin broker y sin catálogo.

## Decisión

**Dos etapas: una que compila con el JDK y el wrapper, y una final con sólo el
JRE, el jar extraído por capas y un usuario sin privilegios.**

| Decisión | Qué se hizo | Por qué |
|---|---|---|
| Etapas | Compilación con `eclipse-temurin:21…-jdk-alpine`, ejecución con el `-jre-alpine` de la **misma** versión exacta | Ninguna herramienta de compilación sobrevive: ni Maven, ni el wrapper, ni el `~/.m2`, ni el código |
| Caché de capas | `COPY .mvn/ mvnw pom.xml` → `dependency:go-offline` → recién ahí `COPY src/` | Un cambio en una clase no vuelve a descargar el repositorio Maven entero |
| Jar por capas | `java -Djarmode=tools … extract --layers --launcher`, y un `COPY` por capa en orden de volatilidad | Un redeploy sube sólo la capa de la aplicación, no las dependencias |
| Usuario | `app` (uid/gid 10001), sin home y sin shell; `USER 10001:10001` **antes** del `ENTRYPOINT` | El proceso no corre como `root`. El uid numérico permite que un orquestador lo verifique con `runAsNonRoot` |
| Señales | `ENTRYPOINT` en **forma exec**, sin shell intermedio | La JVM es el PID 1 y recibe el `SIGTERM`. Con un shell en el medio, el apagado elegante de 25 s no ocurre y cada despliegue corta pedidos en curso |
| JVM | `-XX:MaxRAMPercentage=75.0` y `-XX:+ExitOnOutOfMemoryError`, nada más | El porcentaje hace que el heap siga al límite del contenedor sin fijar un número; morir ante un `OutOfMemoryError` es preferible a una JVM viva que no responde. Recolector y tamaños quedan al default de Java 21 |
| Versión base | Fijada a la versión exacta, nunca `:latest` | Dos builds del mismo commit producen la misma imagen |
| Salud | **Sin `HEALTHCHECK` propio**: la sonda la hace el orquestador contra `/actuator/health/readiness` | Un `HEALTHCHECK` en la imagen duplica una decisión que es del entorno, y el orquestador es el único que sabe cuánto esperar a que Flyway migre |
| Configuración | Ningún `ENV` con valor sensible, ningún `.env` copiado | Todo entra por variables de entorno |

**El `.dockerignore` excluye por dos motivos distintos, y conviene distinguirlos:
por peso** (`target/`, `.git/`, `docs/`, `.idea/`) **y por seguridad**
(`.env` y `.env.*` —con `!.env.example`—, `*.pem`, `*.key`, `*.p12`, `*.jks`,
`**/id_rsa*`, `.npmrc`, `.netrc`, `settings.xml`). El segundo grupo es el que
importa: **una credencial copiada y borrada en un `RUN` posterior sigue estando
en la capa anterior**, y `docker history` la muestra.

**El build de la imagen no corre los tests de integración.** Necesitan Docker, y
anidar Docker adentro del build es un problema que no hace falta tener: los
tests corren antes, en el pipeline ([0019](0019-pipeline-de-ci-con-publicacion-de-imagen-versionada.md)).

### Alternativas descartadas

| Alternativa | Por qué se descartó | Qué la volvería a poner sobre la mesa |
|---|---|---|
| **Una sola etapa con el JDK** | Deja Maven, el `~/.m2` y el código fuente adentro: mucho más peso y mucha más superficie de ataque, sin ningún aporte al runtime | Nada |
| **Copiar el jar completo sin extraer por capas** | Una sola capa de ~60 MB que se reemplaza entera en cada despliegue, aunque sólo hayan cambiado las clases de la aplicación | Un despliegue donde el ancho de banda no importe |
| **Buildpacks / `spring-boot:build-image`** | Producen una imagen buena sin escribir un `Dockerfile`, a cambio de no poder auditar qué hay adentro ni fijar la base. Acá la auditoría de la imagen era parte del entregable | Que el equipo deje de querer controlar la base |
| **Imagen *distroless*** | Menos superficie todavía. Se descartó por diagnóstico: sin shell, entrar a un contenedor a mirar algo deja de ser posible, y Alpine con JRE ya es chica | Un entorno productivo con diagnóstico resuelto por sidecar |
| **`HEALTHCHECK` en la imagen** | Duplica en el artefacto una decisión del entorno, y el primer arranque tarda lo que tarde Flyway: un healthcheck con el margen equivocado reinicia el contenedor en loop | Un despliegue con `docker run` suelto, sin orquestador |
| **`ENTRYPOINT` en forma shell** | El shell se queda con el PID 1 y la JVM no recibe la señal: el apagado elegante deja de existir sin que nada lo avise | Nada |
| **Fijar `-Xmx`** | Un número absoluto se desactualiza en cuanto cambia el límite del contenedor. El porcentaje lo sigue solo | Un perfil de memoria muy medido |

## Consecuencias

### A favor

- **El `.env` con credenciales reales no puede entrar**, y tampoco el historial
  de git ni las claves locales. Es la exclusión que más valía la pena escribir.
- **El despliegue sube la capa que cambió.** Con el jar por capas, un cambio de
  código no reenvía las dependencias.
- **El apagado elegante funciona de verdad adentro del contenedor**, así que un
  despliegue no corta pedidos en curso.
- **Dos builds del mismo commit producen la misma imagen funcional**: nada del
  build depende de la máquina que lo corre.
- **El proceso corre como 10001 y el orquestador lo puede exigir.**

### En contra, y asumido

- **Los placeholders de desarrollo siguen activos si el entorno no define
  nada.** Una imagen que arranca sin variables acepta tokens HMAC firmados con
  una clave publicada en el repositorio y cifra con la clave por defecto. La
  imagen **no** es el control: el control es el entorno, más el aviso que la
  aplicación imprime en cada arranque cuando está usando un placeholder. Es el
  riesgo más grande de esta decisión y está aceptado a conciencia
  ([0003](0003-autenticacion-autorizacion-y-datos-sensibles.md) lo nombra
  también).
- **Alpine usa musl.** Es la libc que hace la imagen chica y la que puede dar
  sorpresas con dependencias nativas. Hoy no hay ninguna; el día que aparezca,
  el cambio de base es un `FROM`.
- **La versión base fijada hay que subirla a mano.** Una base clavada no recibe
  parches de seguridad sola: sin alguien que mire, la imagen envejece. El
  escaneo de dependencias del pipeline no cubre el sistema operativo de la base.
- **La imagen no se valida a sí misma.** Sin `HEALTHCHECK`, un `docker run`
  suelto no sabe si la aplicación quedó sana.
- **El `EXPOSE 9090` es documentación, no un control.** Que el puerto de gestión
  no salga depende del `compose.yaml` y del despliegue, no de la imagen.
