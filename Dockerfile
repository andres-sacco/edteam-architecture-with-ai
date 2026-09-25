# =============================================================================
# flight-reservations — imagen de ejecución
# =============================================================================
#
#   docker build -t flight-reservations:0.0.1-SNAPSHOT .
#
# Dos etapas. La primera tiene el JDK, el wrapper de Maven y el código fuente;
# la segunda tiene un JRE y las clases ya compiladas, y nada más. Nada de la
# primera etapa llega a la imagen final salvo lo que se copia explícitamente:
# no hay Maven, no hay ~/.m2, no hay .java y no hay compilador.
#
# Ninguna credencial entra acá. Toda la configuración llega por variables de
# entorno en el arranque del contenedor (ver la tabla del README / §6 de la
# consigna). Un secreto escrito en un ENV o copiado y borrado después queda en
# la capa donde estuvo, y `docker history` lo muestra: por eso no se escribe
# nunca, ni siquiera temporalmente.

# -----------------------------------------------------------------------------
# Etapa 1: compilación
# -----------------------------------------------------------------------------
# JDK completo —hace falta javac— sobre la MISMA base Alpine 3.22 que el
# runtime, para que las dos etapas compartan la capa del sistema operativo en
# el caché local y para que el bytecode se produzca con el mismo proveedor y la
# misma actualización de Java (Temurin 21.0.12+8) con la que se va a ejecutar.
#
# Etiqueta EXACTA, nunca ':latest' ni ':21': fija la versión del JDK (21.0.12_8)
# y la del sistema base (alpine-3.22). Sin eso, dos builds del mismo commit con
# un mes de diferencia compilan con JDKs distintos y "funciona en mi máquina"
# vuelve a ser una explicación válida.
FROM eclipse-temurin:21.0.12_8-jdk-alpine-3.22 AS builder

WORKDIR /build

# Repositorio Maven DENTRO del árbol del build y no en ~/.m2.
# Esto es lo que hace que el resultado de la resolución quede en una capa de
# imagen —cacheable y compartible— en lugar de en el home del usuario del
# contenedor, y lo que garantiza que el build no toque ni lea el ~/.m2 de la
# máquina de desarrollo: no se monta nada de afuera, así que dos máquinas
# distintas resuelven exactamente lo mismo.
ENV MAVEN_REPO=/build/.m2 \
    MAVEN_ARGS="-B -ntp"

# --- Capa de dependencias -----------------------------------------------------
# Sólo el wrapper y el POM, ANTES del código fuente. Es el orden el que compra
# el caché: mientras el pom.xml no cambie, Docker reutiliza la capa siguiente
# —la descarga entera del repositorio Maven, ~400 MB— aunque hayan cambiado
# todas las clases del proyecto. Al revés (código primero) cada edición de una
# línea volvería a bajar Spring Boot completo.
#
# El wrapper se copia versionado del repositorio: la versión de Maven la fija
# .mvn/wrapper/maven-wrapper.properties (3.9.9, con URL absoluta a
# repo.maven.apache.org), no lo que tenga instalado quien construya. El build
# no depende de ningún Maven de la máquina.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# go-offline resuelve dependencias y plugins de una sola vez.
# '|| true' NO se usa a propósito: si la resolución falla, el build tiene que
# fallar acá y no doce minutos después en mitad de la compilación.
RUN ./mvnw ${MAVEN_ARGS} -Dmaven.repo.local=${MAVEN_REPO} \
        dependency:go-offline

# --- Capa de compilación ------------------------------------------------------
# Recién ahora el código. Un cambio en una clase invalida de acá para abajo y
# nada más.
COPY src/ src/

# -DskipTests: la imagen NO corre tests.
#   · Los unitarios (*Test, surefire) no necesitan Docker y ya corrieron en el
#     pipeline antes de llegar acá; repetirlos en el build sólo alarga el
#     tiempo de la imagen y rompe el caché por un motivo que no es el código.
#   · Los de integración (*IT, failsafe) levantan PostgreSQL y RabbitMQ con
#     Testcontainers, o sea que necesitan un demonio Docker. Correrlos en el
#     build obligaría a Docker-in-Docker o a montar el socket del host: dos
#     agujeros de seguridad y una fuente de intermitencias, a cambio de nada
#     que el pipeline no pueda hacer mejor.
#
#   Dónde corren: en el pipeline, en un paso ANTERIOR al build de la imagen —
#   './mvnw verify' en un runner con Docker, que además dispara el gate de PII
#   (fase verify). La imagen se construye recién si ese paso pasó, y del MISMO
#   commit. El artefacto que se despliega nunca es un artefacto no probado: es
#   un artefacto probado en otro paso.
#
# 'offline' NO se fuerza (-o) porque go-offline no resuelve el 100% de los
# plugins; lo que quede pendiente se baja acá y es marginal.
RUN ./mvnw ${MAVEN_ARGS} -Dmaven.repo.local=${MAVEN_REPO} \
        -DskipTests package

# --- Descomposición en capas --------------------------------------------------
# El jar ejecutable pesa ~86 MB y el 95% son dependencias de terceros que
# cambian una vez por trimestre. Copiado entero, cada despliegue —aunque toque
# una sola línea— sube y baja 86 MB.
#
# 'jarmode=tools extract --layers' lo parte según el orden de cambio declarado
# por Spring Boot:
#
#     dependencies/           ~82 MB   cambia al tocar el pom
#     spring-boot-loader/     ~0,6 MB  cambia al subir de versión Boot
#     snapshot-dependencies/   0 MB    (vacío acá)
#     application/            ~1,7 MB  cambia en cada commit
#
# Copiadas en ese orden como cuatro COPY distintas, un cambio de código
# invalida sólo la última: el redeploy sube 1,7 MB en lugar de 86.
#
# '--launcher' extrae también el JarLauncher, que es lo que permite arrancar
# con 'java <clase>' en vez de 'java -jar': sin el jar de por medio, el
# classpath se arma del filesystem y el arranque es algo más rápido.
#
# Se usa 'jarmode=tools' y no el viejo 'jarmode=layertools': en Spring Boot
# 3.3+ layertools está deprecado y 'tools' es su reemplazo.
WORKDIR /layers
RUN java -Djarmode=tools -jar /build/target/flight-reservations-*.jar \
        extract --layers --launcher --destination /layers


# -----------------------------------------------------------------------------
# Etapa 2: ejecución
# -----------------------------------------------------------------------------
# JRE, no JDK: la imagen no compila nada. El JDK de Temurin 21 pesa ~330 MB y
# el JRE ~190 MB; los ~140 MB de diferencia son javac, jlink, jcmd, jstack,
# jmap y el resto del toolkit, que además de peso son superficie de ataque y
# CVEs que hay que responder en cada escaneo sin usar ninguno.
#
# Alpine y no Ubuntu (jammy/noble) por el mismo motivo: la variante '-jre-jammy'
# equivalente ronda los 280 MB. Musl no es gratis —hay librerías nativas que no
# corren— pero este servicio no usa ninguna: todo el stack (Tomcat, Hibernate,
# Lettuce, el cliente AMQP) es Java puro.
#
# Objetivo de tamaño declarado: < 300 MB. Medido: 293 MB, repartidos en
# 206 MB de base (Alpine + JRE de Temurin) y 87 MB de aplicación, de los
# cuales 85 MB son las dependencias de terceros. El motivo del objetivo no es
# estético: es el pull en frío. Las capas gratuitas de las plataformas por
# contenedor cobran el arranque en tiempo, y 300 MB se bajan en decenas de
# segundos donde 1 GB se baja en minutos — con el agravante de que un
# autoescalado a cero paga ese pull en CADA arranque.
#
# De dónde saldría el próximo recorte, si hiciera falta: un runtime a medida
# con 'jlink' en la etapa 1 en lugar del JRE completo. Ahorra ~100 MB, pero
# hay que declarar los módulos —Spring Boot resuelve mucho por reflexión— y un
# módulo que falte no se nota hasta que un camino poco frecuente lo pide en
# producción. No se hace acá porque 293 MB ya entra cómodo en el objetivo y
# el riesgo no se paga.
FROM eclipse-temurin:21.0.12_8-jre-alpine-3.22

# --- Usuario sin privilegios --------------------------------------------------
# GID y UID numéricos y FIJOS (10001), no autoasignados. Dos razones:
#   · 'runAsNonRoot' de Kubernetes valida el USER del manifiesto de la imagen y
#     RECHAZA el pod si es un nombre, porque no puede resolverlo sin arrancar
#     el contenedor. Con un número, la validación pasa.
#   · Un UID estable hace predecibles los permisos de cualquier volumen montado.
#
# '-S' crea usuario y grupo de sistema (sin contraseña, sin envejecimiento).
# '-D' no crea home —no hace falta ninguno— y '-s /sbin/nologin' deja al usuario
# sin shell: si alguien consigue ejecución dentro del contenedor, no hay intérprete
# que le responda.
RUN addgroup -S -g 10001 app \
 && adduser  -S -u 10001 -G app -H -s /sbin/nologin app

WORKDIR /app

# --- Las cuatro capas, de la que menos cambia a la que más --------------------
# El orden importa: Docker invalida una capa y todas las que siguen. Con
# 'application' al final, un commit de código deja intactas las tres primeras.
#
# '--chown=app:app' en el COPY y no un 'chown -R' posterior: un chown recursivo
# sobre 82 MB de jars crea una capa NUEVA de 82 MB —los archivos se duplican con
# los metadatos cambiados— y duplica el tamaño de la imagen sin agregar nada.
COPY --from=builder --chown=app:app /layers/dependencies/          ./
COPY --from=builder --chown=app:app /layers/spring-boot-loader/    ./
COPY --from=builder --chown=app:app /layers/snapshot-dependencies/ ./
COPY --from=builder --chown=app:app /layers/application/           ./

# --- Superficie de red --------------------------------------------------------
# Declarativo: EXPOSE no publica nada, documenta qué escucha el proceso.
#   8080  la API. Es el único que el despliegue publica.
#   9090  Actuator (MANAGEMENT_PORT). Se declara para que el orquestador sepa
#         contra dónde sondear DENTRO de la red del clúster, y NO se publica
#         hacia afuera: '/actuator/metrics' revela volumetría de negocio y
#         'http.server.requests' revela la superficie real de la API, incluidos
#         los endpoints que el contrato no documenta.
EXPOSE 8080 9090

# --- El proceso no corre como root --------------------------------------------
# Fijado ANTES del ENTRYPOINT, que es lo único que garantiza que el proceso
# arranque con este usuario. Numérico por lo dicho arriba.
#
# A partir de acá el filesystem de la imagen es de sólo lectura de hecho: /app
# pertenece a app:app pero nada del runtime escribe ahí. El único directorio
# que la JVM necesita escribible es /tmp (el temp de Tomcat y los archivos de
# clase del JIT), así que el contenedor puede correr con '--read-only' y un
# 'tmpfs /tmp'. Está configurado así en compose.yaml.
USER 10001:10001

# --- Arranque -----------------------------------------------------------------
# Forma EXEC (array JSON), no shell. Es la diferencia entre que PID 1 sea 'java'
# y que sea '/bin/sh -c java ...':
#   · Con la forma shell, la señal se la come sh —que no la reenvía— y la JVM
#     nunca ve el SIGTERM. El shutdown hook no corre, el apagado elegante de 25 s
#     no ocurre, y a los 10 s Docker manda SIGKILL: los pedidos en vuelo se
#     cortan a la mitad y las transacciones abiertas quedan para el timeout de la
#     base.
#   · Con la forma exec, PID 1 es la JVM, recibe el SIGTERM directamente,
#     Spring Boot cierra el conector de Tomcat, deja drenar hasta
#     'timeout-per-shutdown-phase: 25s' y recién ahí termina.
#
# El orquestador tiene que darle ese tiempo. Con Docker, el default de
# 'docker stop' son 10 s y hay que subirlo (--time 35 / 'stop_grace_period' en
# compose); en Kubernetes, 'terminationGracePeriodSeconds: 35'. Sin eso, el
# graceful shutdown está configurado en la aplicación y no ocurre igual.
#
# No se usa 'java -jar': el jar se extrajo en capas y el JarLauncher arma el
# classpath desde el filesystem.
#
# Flags de JVM que se FIJAN acá y por qué:
#
#   -XX:MaxRAMPercentage=75.0
#     El default de Java 21 en un contenedor de más de 256 MB es 25%: en un
#     contenedor de 512 MB del free tier eso son 128 MB de heap y ~380 MB de
#     RAM pagada y sin usar. 75% deja el resto para metaspace, los stacks de
#     los carrier threads, los buffers directos de Netty/Lettuce y el propio
#     runtime. Es un PORCENTAJE y no un -Xmx fijo justamente para que la misma
#     imagen se comporte bien en 512 MB y en 4 GB sin reconstruirla.
#
#   -XX:+ExitOnOutOfMemoryError
#     Sin esto, un OutOfMemoryError mata el hilo que lo sufrió y deja el
#     proceso vivo: la sonda de liveness sigue respondiendo 200 y el
#     orquestador no reinicia nada, mientras la instancia atiende pedidos
#     que fallan de a uno. Un proceso muerto y reiniciado en 30 s es mejor
#     que un proceso vivo y roto por horas.
#
# Lo que NO se fija, y también es una decisión:
#
#   El recolector. La ergonomía de Java 21 elige SerialGC con <2 CPUs o
#   <1792 MB —que es exactamente el free tier, y donde SerialGC es la
#   respuesta correcta: G1 gasta hilos y memoria de más para nada— y G1 en
#   cuanto el contenedor crece. Fijar '-XX:+UseG1GC' sería imponerle a la
#   capa gratuita un recolector que la perjudica. El default acierta en los
#   dos casos.
#
#   -XX:+UseContainerSupport: encendido por defecto desde Java 10.
#   -XX:InitialRAMPercentage: sólo evita un par de redimensionamientos en el
#   arranque, a cambio de reservar la memoria antes de necesitarla; en un
#   contenedor chico, de más.
#   -Dfile.encoding=UTF-8: es el default desde Java 18.
#   -Djava.security.egd=...: sólo tenía sentido antes de Java 8u162.
#   El tamaño de los pools: con 'spring.threads.virtual.enabled: true' no hay
#   pool que dimensionar. Lo que se dimensiona es el pool de conexiones a la
#   base, que es configuración de la aplicación y no de la JVM.
#
# Para agregar flags en un entorno puntual (un -XX:+HeapDumpOnOutOfMemoryError,
# un agente) NO hace falta reconstruir ni romper la forma exec: la JVM lee la
# variable de entorno JAVA_TOOL_OPTIONS sola.
ENTRYPOINT ["java", \
            "-XX:MaxRAMPercentage=75.0", \
            "-XX:+ExitOnOutOfMemoryError", \
            "org.springframework.boot.loader.launch.JarLauncher"]

# --- Salud --------------------------------------------------------------------
# A propósito SIN HEALTHCHECK. La sonda la hace el orquestador contra
# '/actuator/health/readiness' en el puerto de gestión. Motivos:
#
#   · El puerto de gestión es configurable (MANAGEMENT_PORT). Un HEALTHCHECK
#     horneado tiene que escribir un puerto literal, y el día que alguien mueva
#     Actuator a otro puerto la imagen reporta 'unhealthy' sin que nada esté mal.
#     Una sonda que miente es peor que no tener sonda.
#   · Kubernetes, Cloud Run, Fly y Railway IGNORAN el HEALTHCHECK de la imagen:
#     usan sus propias readinessProbe/livenessProbe. En el único lugar donde sí
#     se usa —'depends_on: condition: service_healthy' de compose— la sonda está
#     declarada en compose.yaml, que es donde se conoce el puerto real.
#   · Readiness y liveness son dos preguntas distintas ("¿le mando tráfico?" vs
#     "¿lo reinicio?") y Docker sólo tiene una. Aplanarlas hace que un arranque
#     lento con Flyway migrando se lea como un proceso colgado y lo reinicien en
#     loop.
#
# Se declaran igual las etiquetas OCI, que son lo que un escáner y un registro
# leen para atribuir la imagen.
LABEL org.opencontainers.image.title="flight-reservations" \
      org.opencontainers.image.description="Sistema de reservas de vuelos (arquitectura hexagonal)" \
      org.opencontainers.image.source="https://github.com/edteam/flight-reservations" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:21.0.12_8-jre-alpine-3.22"
