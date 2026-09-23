# 22 — Dockerfile multietapa y `.dockerignore`

**Etapa:** Implementación

**Salida esperada:** Dockerfile multietapa completo y comentado, más su `.dockerignore`

---

## Rol

Actúa como ingeniero DevOps especializado en contenedores.

## Contexto

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

**El stack y cómo se construye hoy:**

- **Java 21** (`<java.version>21</java.version>`, hay un `.sdkmanrc`), **Spring Boot 3.5.7**, artefacto `flight-reservations-0.0.1-SNAPSHOT`.
- Se compila con el **Maven wrapper** (`./mvnw`), que ya está versionado junto con `.mvn/`.
- `mvn test` corre sólo los unitarios (`*Test`): rápidos y **sin Docker**. `mvn verify` agrega los de integración (`*IT`), que levantan PostgreSQL y RabbitMQ con **Testcontainers** y **sí necesitan Docker**.
- Dependencias de runtime: `web`, `validation`, `actuator`, `security`, `oauth2-resource-server`, `springdoc`, `data-jpa` + `postgresql` + `flyway`, `data-redis`, `amqp`.
- Hoy la aplicación se ejecuta con `./mvnw spring-boot:run`. **No existe ningún `Dockerfile` ni `.dockerignore` en el repositorio.**

**Cómo se comporta la aplicación, que es lo que la imagen tiene que respetar:**

- **Toda la configuración entra por variables de entorno**: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_*`, `RABBIT_*`, `CATALOG_*`, `JWT_*`, `PII_ENCRYPTION_KEY`, `MANAGEMENT_PORT`, `CORS_ALLOWED_ORIGINS` y las de mensajería. Están documentadas en `.env.example`; el `.env` real **no se versiona**.
- El `application.yml` hace `spring.config.import: optional:file:./.env[.properties]`, o sea que **lee el `.env` del directorio de trabajo si existe**. Es cómodo en local y es exactamente lo que no debe pasar dentro de la imagen.
- **Threads virtuales encendidos** (`spring.threads.virtual.enabled: true`): el dimensionamiento del contenedor no se hace por tamaño de pool.
- **Apagado elegante** (`server.shutdown: graceful`, `timeout-per-shutdown-phase: 25s`): el contenedor necesita recibir la señal de terminación en el proceso correcto y que el orquestador le dé al menos ese tiempo.
- **Dos puertos**: `8080` es la API y `9090` es Actuator (`MANAGEMENT_PORT`), que **no se publica hacia afuera** por decisión de seguridad. Las sondas de readiness y liveness están habilitadas (`management.endpoint.health.probes.enabled: true`).
- **Flyway corre al arrancar** y `spring.jpa.hibernate.ddl-auto: validate` hace que la aplicación no levante si el esquema no coincide.
- La aplicación **arranca sin Redis, sin broker y sin el catálogo**: son dependencias opcionales que degradan.
- El `compose.yaml` levanta PostgreSQL 17, Redis 7, RabbitMQ 4 y el `api-catalog` con su MySQL, pero **no incluye al servicio de reservas**: hoy corre fuera, en la máquina de desarrollo.

## Tarea

1. **Escribir el `Dockerfile` multietapa**: una etapa de compilación con el JDK y el Maven wrapper, y una etapa final de ejecución que contenga **sólo** lo necesario para correr. Ninguna herramienta de compilación puede sobrevivir a la imagen final.
2. **Aprovechar el caché de capas**: resolver las dependencias en una capa propia, antes de copiar el código fuente, para que un cambio en una clase no vuelva a descargar el repositorio Maven entero.
3. **Usar el jar por capas de Spring Boot** (`layertools`) o justificar por qué no: separar dependencias, loader y clases de la aplicación hace que un redeploy suba sólo la capa que cambió.
4. **Usuario sin privilegios**: crear el usuario y el grupo, darles lo mínimo y dejar el `USER` fijado antes del `ENTRYPOINT`. El proceso no corre como `root`.
5. **`.dockerignore`**: excluir todo lo que no tiene que entrar en el contexto de build. Como mínimo `target/`, `.git/`, `.idea/`, `docs/`, `.env` y cualquier archivo de credenciales locales. Decir qué se excluye por peso y qué se excluye por seguridad.
6. **Configuración por entorno, nada adentro**: ninguna credencial, ningún `ENV` con un valor sensible, ningún `.env` copiado. Decir qué variables espera la imagen y cuáles son obligatorias para que arranque.
7. **Señales y apagado**: garantizar que la JVM reciba la señal de terminación —la forma del `ENTRYPOINT` importa— para que el apagado elegante de 25 s funcione de verdad dentro del contenedor.
8. **Arranque y salud**: exponer los puertos que corresponda, decidir si la imagen lleva `HEALTHCHECK` propio o si la sonda la hace el orquestador contra `/actuator/health/readiness`, y justificar la decisión.
9. **Ajustes de JVM para contenedores**: decidir qué se fija (porcentaje de memoria del contenedor, recolector, `-XX:+ExitOnOutOfMemoryError`) y qué se deja al default de Java 21, justificando cada uno.
10. **Integrar el servicio al `compose.yaml`** para poder levantar el sistema completo con un solo comando, sin romper el flujo actual de quien corre la aplicación desde el IDE.

## Restricciones

- **Imagen final liviana**: sin JDK completo si alcanza un runtime, sin Maven, sin el repositorio `~/.m2`, sin el código fuente y sin herramientas de diagnóstico que no se usen. Declarar el tamaño objetivo y el motivo.
- **Sin herramientas de compilación en el runtime.** Si algo del build tiene que sobrevivir, hay que justificarlo.
- **El proceso no corre como `root`**, y el filesystem de la aplicación no necesita escritura salvo donde se justifique.
- **Ningún secreto en el `Dockerfile`, en un `ENV` ni en una capa intermedia.** Una credencial copiada y borrada en un `RUN` posterior **sigue estando** en la capa anterior.
- **Versión base fijada**: nada de `:latest` en el `FROM`. La etiqueta tiene que identificar la versión exacta.
- **Reproducible**: dos builds del mismo commit tienen que producir la misma imagen funcional. El build no puede depender de nada de la máquina de desarrollo.
- **Free tier**: la imagen tiene que poder correr en una capa gratuita de una plataforma de despliegue por contenedor, y construirse sin servicios pagos.
- **El build de la imagen no corre los tests de integración**: necesitan Docker, y anidar Docker dentro del build es un problema que no hace falta tener. Decir en qué etapa del pipeline corren.
- Coherencia con lo ya decidido: configuración por variables de entorno, Actuator en un puerto que el despliegue no publica, y arranque posible sin Redis, sin broker y sin catálogo.

## Formato de salida

1. **`Dockerfile` completo y comentado**, con el motivo de cada instrucción escrito al lado.
2. **`.dockerignore` completo**, con el motivo de cada exclusión.
3. **Tabla de decisiones**: decisión (imagen base, etapas, usuario, jar por capas, flags de JVM, healthcheck) | alternativa descartada | por qué.
4. **Tabla de variables de entorno** que la imagen espera: variable | obligatoria/opcional | qué pasa si falta.
5. **Cambios en el `compose.yaml`** para levantar el sistema completo.
6. **Comandos de verificación**: cómo construir la imagen, cómo medir su tamaño, cómo confirmar el usuario con el que corre, cómo levantarla contra las dependencias del `compose.yaml` y cómo comprobar que responde la sonda de readiness.
