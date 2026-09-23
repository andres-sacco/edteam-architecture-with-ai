# 23 — Auditoría de la imagen: tamaño, privilegios, secretos y capas

**Etapa:** Revisión

**Salida esperada:** Tabla de hallazgos con riesgo, evidencia, forma de detectarlo y mitigación

---

## Rol

Actúa como revisor de seguridad de contenedores, con foco en lo que una imagen arrastra sin que nadie lo note hasta que ya está publicada.

## Contexto

El sistema de reservas de vuelos (Java 21 + Spring Boot 3.5, arquitectura hexagonal, PostgreSQL + Redis + RabbitMQ) tiene un `Dockerfile` multietapa y un `.dockerignore` recién escritos, que son la entrada de esta auditoría junto con la imagen que producen.

**Lo que hay en el repositorio y condiciona la imagen:**

- Compilación con el **Maven wrapper** (`./mvnw`) y **Java 21**; artefacto `flight-reservations-0.0.1-SNAPSHOT.jar`.
- `mvn test` corre unitarios sin Docker; `mvn verify` agrega los `*IT`, que levantan PostgreSQL y RabbitMQ con **Testcontainers**.
- **`.env` en la raíz, no versionado**, con credenciales locales reales de la máquina de desarrollo: `DB_PASSWORD`, `REDIS_PASSWORD`, `RABBIT_PASSWORD`, `PII_ENCRYPTION_KEY`, `CATALOG_API_KEY`. El `application.yml` lo importa con `spring.config.import: optional:file:./.env[.properties]`, así que **si el archivo entra en la imagen, la aplicación lo lee dentro del contenedor**.
- `target/` con el jar y las clases compiladas, `.git/` con todo el historial, `.idea/`, `docs/` y `.sdkmanrc` viven en el mismo directorio que el `Dockerfile`.
- `application.yml` trae **placeholders de desarrollo reconocibles** que no son secretos pero sí son peligrosos si quedan activos: `JWT_DEV_SECRET` (`dev-only-hmac-key-...`), `PII_ENCRYPTION_KEY` por defecto y `SECURITY_DEV_TOKENS=true`, que acepta tokens HMAC firmados con una clave publicada en el repositorio.
- `API_DOCS_ENABLED` y `SWAGGER_UI_ENABLED` vienen en `true` por defecto, y `MANAGEMENT_PORT=9090` no debe publicarse hacia afuera.
- La aplicación necesita `server.shutdown: graceful` con 25 s y sondas en `/actuator/health/{readiness,liveness}`.

**Lo que aporta el paso anterior:** el `Dockerfile` y el `.dockerignore` de [22 — Dockerfile multietapa y `.dockerignore`](22-dockerfile-multietapa.md).

La entrada de este prompt son **las dos cosas**: los archivos escritos y la imagen construida a partir de ellos.

## Tarea

Auditar el `Dockerfile`, el `.dockerignore` y la imagen resultante buscando las fallas que pasan inadvertidas en una revisión normal. Como mínimo:

1. **Contexto de build sin filtrar**: ¿el `.dockerignore` excluye efectivamente `target/`, `.git/`, `.idea/`, `docs/` y **el `.env`**? ¿Qué pesa y qué entra que no debería? Un `COPY . .` sin exclusiones mete el historial completo del repositorio dentro de la imagen.
2. **Herramientas de compilación en la imagen final**: ¿sobrevive el JDK completo, Maven, el wrapper, el repositorio `~/.m2` o el código fuente? Cada uno agrega peso y superficie de ataque, y ninguno hace falta para ejecutar.
3. **Privilegios**: ¿el proceso corre como `root`? ¿Hay un `USER` declarado y está **después** de todo lo que necesitaba privilegios? ¿El filesystem necesita escritura en algún lado que no se justifique?
4. **Secretos en la imagen**: buscar credenciales en instrucciones `ENV`, en argumentos de build, en archivos copiados y **en capas intermedias**. Un secreto copiado y borrado en un `RUN` posterior sigue estando en la capa donde se copió, y `docker history` lo muestra.
5. **Placeholders de desarrollo activos**: verificar qué valores toma la imagen si el entorno no define nada. Una imagen que arranca con `SECURITY_DEV_TOKENS=true` acepta tokens firmados con una clave que está publicada en el repositorio; una que arranca con la `PII_ENCRYPTION_KEY` por defecto cifra con una clave conocida; una que arranca con Swagger UI encendida publica la superficie completa de la API.
6. **Versión de la imagen base**: ¿el `FROM` está fijado a una versión exacta o usa una etiqueta móvil? ¿La base tiene vulnerabilidades conocidas y hay forma de enterarse cuando aparezcan nuevas?
7. **Señales y apagado**: verificar que la forma del `ENTRYPOINT` haga llegar la señal de terminación a la JVM y no a un shell intermedio. Si no llega, el apagado elegante de 25 s no ocurre y cada deploy corta pedidos en curso.
8. **Arranque y salud**: ¿el contenedor expone lo que tiene que exponer y nada más? ¿Publica el puerto `9090` de Actuator, que no debería salir? ¿La sonda apunta al endpoint correcto y tolera el tiempo que tarda Flyway en migrar al primer arranque?
9. **Reproducibilidad**: ¿dos builds del mismo commit producen la misma imagen funcional, o el resultado depende de algo de la máquina que la construyó?

Para **cada hallazgo**, además del problema, definir **cómo detectarlo de forma concreta** —un comando que se pueda ejecutar, no una lectura del archivo—. Por ejemplo, y sin limitarse a esto:

- medir el tamaño final de la imagen y compararlo contra el objetivo declarado, y ver el peso capa por capa;
- listar el contenido de la imagen buscando el código fuente, el `.git`, el `.env` y los binarios del build;
- revisar el historial de capas en busca de credenciales que ya no están en el filesystem final;
- confirmar con qué usuario corre el proceso dentro del contenedor, desde adentro del contenedor;
- levantar la imagen **sin ninguna variable de entorno** y ver con qué configuración arranca, o si se niega a arrancar;
- mandarle la señal de terminación y medir si respeta el apagado elegante o si muere de golpe.

## Restricciones

- **Sólo hallazgos con evidencia**: cada uno tiene que apuntar a una línea del `Dockerfile` o del `.dockerignore`, a una capa concreta de la imagen o a la salida de un comando. Nada de riesgos genéricos de manual.
- Distinguir lo que es **una falla real** de lo que es **una limitación asumida y documentada** (que los tests de integración no corran dentro del build, por ejemplo): lo segundo se lista aparte, no como hallazgo.
- **Los hallazgos de secretos y de privilegios son severidad máxima por defecto.** Una imagen se publica en un registro y deja de estar bajo control.
- Priorizar por **impacto real**: lo que expone una credencial o permite escalar privilegios pesa más que lo que sólo agrega megabytes.
- No proponer todavía el código de la solución: este paso identifica y ordena.
- Las verificaciones tienen que poder ejecutarse con Docker y el `compose.yaml` del repositorio, sin herramientas pagas. Si se sugiere un escáner de vulnerabilidades, tiene que ser de uso libre.

## Formato de salida

1. **Tabla de hallazgos**: # | hallazgo | categoría (contexto / peso / privilegios / secretos / configuración por defecto / imagen base / señales / reproducibilidad) | evidencia (línea, capa o salida de comando) | impacto | severidad.
2. **Tabla de detección**: hallazgo | comando concreto que lo expone | resultado esperado si está bien | resultado esperado si está mal.
3. **Medición de la imagen**: tamaño total, desglose por capa y comparación contra el objetivo declarado en el paso anterior.
4. **Inventario de lo que la imagen contiene y no debería**: archivo o directorio | por qué llegó ahí | cómo se excluye.
5. **Mitigación propuesta** por hallazgo, en una o dos líneas, sin escribir el archivo corregido.
6. **Limitaciones asumidas**, listadas aparte con el motivo por el que no son hallazgos.
7. **Orden sugerido de remediación**, con el criterio usado para ordenarlo.
