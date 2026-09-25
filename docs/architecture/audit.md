# Auditoría de los diagramas C4

**Fecha:** 2026-09-25 · **Prompt origen:** [29 — Auditoría de los diagramas C4](../prompts/29-auditoria-de-los-diagramas-c4.md)
**Alcance:** los dos bloques Mermaid de [`c4.md`](c4.md) —el C1 de contexto (`c4.md:86-116`)
y el C2 de contenedores (`c4.md:121-157`)— con su tabla de elementos (§1) y su tabla
de relaciones (§2), contrastados contra el código de `infrastructure/adapter/in` y `out`,
`application.yml`, `compose.yaml`, los 19 ADR de [`docs/adr/`](../adr/README.md), la
[topología de mensajería](../messaging/topology.md) y el contrato de [`openapi.yaml`](../api/openapi.yaml).

- §1 [Resultado del renderizado](#1-resultado-del-renderizado)
- §2 [Tabla de hallazgos](#2-tabla-de-hallazgos)
- §3 [Lo que el diagrama efectivamente dibuja](#3-lo-que-el-diagrama-efectivamente-dibuja)
- §4 [Cómo detectar cada uno](#4-cómo-detectar-cada-uno)
- §5 [Mitigación propuesta](#5-mitigación-propuesta)
- §6 [Simplificaciones deliberadas](#6-simplificaciones-deliberadas--no-son-hallazgos)
- §7 [Orden sugerido de corrección](#7-orden-sugerido-de-corrección)

> **Cómo leer las severidades.** El criterio es *cuánto daño hace creerle al diagrama*, no
> cuánto cuesta corregirlo. Una discrepancia entre el diagrama y el sistema es severidad
> máxima por defecto: un diagrama se usa para decidir y es el primer documento que alguien
> nuevo lee. Un diagrama que no renderiza es bloqueante aunque su contenido sea correcto
> —no es el caso acá—. Lo que es una simplificación deliberada del nivel —que el C1 no
> muestre la base, que el MySQL del catálogo no se dibuje— está en §6 y **no es un hallazgo**.

---

## 1. Resultado del renderizado

Verificado con Mermaid 11 instalado desde npm, que es el mismo motor que usan GitHub y
mermaid.live: primero `mermaid.parse()` y después `mermaid.render()` completo, sobre los dos
bloques extraídos tal cual del archivo. Todo con herramientas gratuitas.

| Bloque | ¿Parsea? | ¿Renderiza? | Qué sale | Error / línea |
|---|---|---|---|---|
| **C1** — `C4Context`, `c4.md:86-116` | Sí, `diagramType=c4` | **Sí** | SVG de 40.816 bytes: 6 formas, 6 relaciones, cada una con etiqueta y tecnología | — |
| **C2** — `C4Container`, `c4.md:121-157` | Sí, `diagramType=c4` | **Sí** | SVG de 51.827 bytes: 9 formas más el `System_Boundary`, 10 relaciones, cada una con etiqueta y tecnología | — |

**No hay hallazgo de sintaxis.** Dos cosas más que el render deja demostradas y conviene
registrar, porque son las que un parseo solo no prueba:

- El renderer C4 de Mermaid corta con `C4 rel "X" -> "Y" references an unknown shape` ante un
  alias de relación que no existe. Ninguno de los dos bloques lo dispara: **todos los alias de
  `Rel(...)` resuelven contra un `Person`, `System`, `System_Ext` o `Container` declarado**.
- Los 6 + 10 pares `label` + `[techn]` aparecen en el SVG. **No hay una sola flecha sin etiqueta
  ni sin protocolo.** La parte del criterio 5 que sí tiene hallazgos es la otra: etiquetas que
  dicen algo distinto de lo que hace el código.

---

## 2. Tabla de hallazgos

| # | Hallazgo | Categoría | Dónde | Evidencia contraria | Impacto | Sev. |
|---|---|---|---|---|---|---|
| **H-01** | El sistema se describe y se dibuja **confirmando reservas**. El pasajero no puede confirmar: el caso de uso existe y no está expuesto | Elemento sobrante | C1 `c4.md:93` (descripción del `System`) y `c4.md:103` (`Rel` pasajero→reservas); tabla de elementos `c4.md:29` y `c4.md:31`; relación 2, `c4.md:59` | [`ReservationController.java:115`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/in/rest/ReservationController.java#L115) expone sólo `POST` (`:217`), `GET /{id}` (`:389`), `GET` (`:497`), `PUT /{id}` (`:549`) y `DELETE /{id}` (`:639`). `ConfirmReservationUseCase` sólo aparece fuera de `application/` en [`ReservationsApplicationIT.java:58`](../../src/test/java/com/edteam/reservations/ReservationsApplicationIT.java#L58). [`openapi.yaml`](../api/openapi.yaml) no tiene la operación. `README.md:668`, en **Fuera de alcance**: «existe y está testeado, pero **no está expuesto**» | El primer documento que lee alguien nuevo le atribuye al sistema una capacidad de negocio que no tiene. El propio `c4.md:14-17` se prohíbe dibujar intenciones | **Crítica** |
| **H-02** | «Publica **los cuatro** hechos de negocio», y uno de los cuatro —`reservation.confirmed`— es inalcanzable en runtime | Elemento sobrante | C1 `c4.md:93` y `c4.md:108`; C2 `c4.md:136`; tabla de elementos `c4.md:31` y `c4.md:38`; relación 6, `c4.md:63` | [`ConfirmReservationService.java:71`](../../src/main/java/com/edteam/reservations/application/service/ConfirmReservationService.java#L71) es el único emisor de `ReservationConfirmed`, y su caso de uso no tiene adaptador de entrada (misma evidencia que H-01). Los otros tres sí: [`CreateReservationTransaction.java:121`](../../src/main/java/com/edteam/reservations/application/service/CreateReservationTransaction.java#L121), [`ModifyReservationTransaction.java:72`](../../src/main/java/com/edteam/reservations/application/service/ModifyReservationTransaction.java#L72), [`CancelReservationService.java:80`](../../src/main/java/com/edteam/reservations/application/service/CancelReservationService.java#L80) | Un consumidor construido contra este diagrama se suscribe a `reservation.confirmed` y espera para siempre un hecho que nadie emite. Es el error que se paga en el repositorio de otro equipo | **Crítica** |
| **H-03** | La opcionalidad del broker **desaparece en el dibujo**: las dos flechas AMQP no llevan la marca que sus hermanas sí llevan | Opcionalidad | C2 `c4.md:146` («El relay publica los hechos») y `c4.md:147` («Entrega al consumidor de referencia») | La relación 9, `c4.md:66`, declara «**Degrada**: sin broker los hechos se acumulan en `outbox_message`»; la 10, `c4.md:67`, «**Opcional**: apagable». Código: `application.yml:466` (`MESSAGING_ENABLED`), `:483` (`consumer-enabled`), `:733` (`management.health.rabbit.enabled: false`), `compose.yaml:443` (`rabbitmq: service_started`, no `service_healthy`). Comparar con C2 `c4.md:143` «Degrada» y `c4.md:145` «Degrada a memoria», que sí la llevan | Es el criterio 8 en su forma exacta. La flecha `app → rabbit` se lee igual que `app → postgres`, y la decisión central de degradación ([ADR 0013](../adr/0013-degradacion-explicita-y-visible.md)) queda invisible justo donde más se mira | **Alta** |
| **H-04** | Faltan los dos actores automáticos que llaman al puerto de gestión, falta la única señal **saliente** de observabilidad, y la nota que los excluye invierte la dirección | Elemento faltante / dirección | C1 `c4.md:105` y C2 `c4.md:141` —`operaciones → app` es la única flecha de operación—; nota de §5, `c4.md:223-227` | `docker/prometheus/prometheus.yml:24,34`: Prometheus **raspa** `:9090/actuator/prometheus`; es un sistema que llama al sistema, no la persona «Operaciones». `compose.yaml:456`: el healthcheck del orquestador pega a `/actuator/health/readiness`, y `application.yml:676` dice que las sondas son «para **el orquestador**». `application.yml:729-731` (`management.otlp.tracing.endpoint`) es un **push saliente**, decidido en [ADR 0017](../adr/0017-trazas-distribuidas-con-alcance-acotado.md). La nota afirma que la pila es «el destino de las señales que ya salen por la relación de Operaciones»: esa relación apunta **hacia adentro** y la exportación OTLP apunta **hacia afuera** | Quien dimensione la exposición del 9090 o el blast radius del colector lee un mapa donde no existen ni el scraper, ni el orquestador, ni el colector | **Alta** |
| **H-05** | El C2 dibuja **dos consumidores coexistentes de la misma cola**: el de referencia y el sistema de notificaciones | Opcionalidad / legibilidad | C2 `c4.md:147` junto a `c4.md:148`, sin marca de exclusión mutua | [`MessagingTopology.java:24`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/out/messaging/MessagingTopology.java#L24): hay **una** cola, `notifications.reservation-events`, con binding `reservation.*` (`:57`). [`topology.md`](../messaging/topology.md) §3 le asigna como consumidor al servicio de notificaciones, y su nota sobre el consumidor de referencia dice que está «apagado fuera de local». El propio `c4.md:210-211` admite que encendido «**compite por los mensajes** con el consumidor legítimo». Y el default es encendido: `application.yml:483` y `compose.yaml:402` | El dibujo muestra un fan-out que la topología prohíbe. Las dos flechas son dos configuraciones alternativas, no dos flujos simultáneos: un consumidor nuevo se conecta creyendo que el circuito ya reparte | **Alta** |
| **H-06** | En el C2 las cuatro flechas que terminan en el **proceso** dicen `HTTPS`, y el proceso no habla TLS. Falta el borde que sí lo termina | Etiqueta / elemento faltante | C2 `c4.md:139`, `c4.md:141`, `c4.md:142`, `c4.md:143` | No hay `server.ssl` ni `management.server.ssl` en `application.yml` —el grep es vacío—. [`SecurityConfiguration.java:157`](../../src/main/java/com/edteam/reservations/infrastructure/security/SecurityConfiguration.java#L157): «El transporte lo termina el borde». `README.md:675`: «el TLS lo termina el ingress y el rate limiting real va en el gateway». `compose.yaml:378` publica `8080:8080` plano y `:456` usa `http://127.0.0.1:9090`. En el **C1** la etiqueta es correcta: ahí el destino es el sistema, no el proceso | Se concluye que la aplicación termina TLS y que la cuota de rate limiting configurada es la efectiva. Las dos cosas son falsas y las dos están en el modelo de amenazas | **Media-alta** |
| **H-07** | La relación de Operaciones dice «con token» y las sondas son **anónimas**; y no distingue que el 9090 no se alcanza desde afuera | Etiqueta | Relación 3, `c4.md:60`, reflejada en C1 `c4.md:105` y C2 `c4.md:141` | [`SecurityConfiguration.java:74`](../../src/main/java/com/edteam/reservations/infrastructure/security/SecurityConfiguration.java#L74): `PROBES = {"/actuator/health", "/actuator/health/**"}`, con `.permitAll()` en `:167`. `/actuator/prometheus` (`:104`) es anónimo cuando `METRICS_SCRAPE_OPEN=true` (`application.yml:396`). `compose.yaml:372-378`: el 9090 **no se publica**, a propósito | Sobreestima la protección del puerto de gestión y subestima su restricción de alcance. Son las dos mitades de la misma decisión de seguridad, y el diagrama se equivoca en las dos | **Media** |
| **H-08** | El broker está **dentro** del `System_Boundary`, y sus colas son de otro sistema | Nivel de abstracción / dirección | C2 `c4.md:136`, dentro del boundary abierto en `c4.md:132` | [`topology.md`](../messaging/topology.md) §3: «Estas colas **son del servicio de notificaciones**, no nuestras». [`MessagingTopology.java:8`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/out/messaging/MessagingTopology.java#L8): «lo único que el productor necesita conocer es el exchange». `application.yml:479` (`declare-consumer-topology`) está encendido sólo en local | La propiedad se lee al revés: el diagrama dice que este sistema es dueño del broker y de la topología del consumidor; el diseño dice que es dueño sólo del exchange. Es el desacoplamiento que [ADR 0004](../adr/0004-mensajeria-asincronica-y-broker.md) compró, dibujado como si no se hubiera comprado | **Media** |
| **H-09** | Deriva de etiquetas entre el C1 y el C2 para la misma relación | Contradicción entre niveles | pasajero→sistema: C1 `c4.md:103` con cinco verbos contra C2 `c4.md:139` con cuatro. pasajero→idp: C1 `c4.md:104` «y obtiene el token» contra C2 `c4.md:140` «Se autentica». operaciones→sistema: C1 `c4.md:105` «las **dos** dead letters» contra C2 `c4.md:141` «dead letters». Descripción de notificaciones: C1 `c4.md:101` incluye «de las plantillas», C2 `c4.md:129` no | En el primer par el correcto es el C2 (ver H-01); en los otros dos, el C1: el token es el objeto de la flecha al IdP, y «dos» es la palabra que carga [ADR 0005](../adr/0005-garantias-de-entrega-y-remediacion-de-la-mensajeria.md) | Individualmente menor. En conjunto es lo que hace que los dos niveles dejen de ser un modelo y pasen a ser dos dibujos. Y el primer par **esconde** H-01 en vez de exponerlo | **Media-baja** |
| **H-10** | El C2 no permite saber **dónde viven** las dos dead letters, que están en dos contenedores distintos | Etiqueta / legibilidad | C2 `c4.md:141`, «Sondas, métricas y dead letters», apuntando sólo a `app` | El propio `c4.md:191-195`: `outbox_message` con `status = 'FAILED'` vive en **postgres**; `notifications.reservation-events.dlq` ([`MessagingTopology.java:37`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/out/messaging/MessagingTopology.java#L37)) vive en **rabbit**. [ADR 0005](../adr/0005-garantias-de-entrega-y-remediacion-de-la-mensajeria.md) | La razón de ser del diseño —que la dead letter del productor esté fuera del broker para alcanzarla cuando el broker es justamente lo que está caído— no se lee en el único diagrama donde los dos contenedores están dibujados | **Media-baja** |
| **H-11** | §6 no declara fuera de alcance el **nivel de despliegue**, que 0018 y 0019 volvieron real, y la tecnología del contenedor no menciona la imagen | Legibilidad | §6, `c4.md:238-254` —declara C3, frontends, colas y «nada planeado»—; tabla de elementos `c4.md:35` y C2 `c4.md:133` | [ADR 0018](../adr/0018-imagen-de-contenedor-multietapa.md) y [ADR 0019](../adr/0019-pipeline-de-ci-con-publicacion-de-imagen-versionada.md) están **Aceptados**; `compose.yaml:371` trae `image: flight-reservations:0.0.1-SNAPSHOT`; existe el [`Dockerfile`](../../Dockerfile) | Bajo, pero es el criterio 9: hay una decisión reciente que cambió el mapa y el documento ni la refleja ni la excluye, que es exactamente lo que hace el resto de §6 con todo lo demás | **Baja** |

---

## 3. Lo que el diagrama efectivamente dibuja

Las dos tablas de abajo no salen de leer el código Mermaid: salen de **renderizarlo y extraer
las formas y las relaciones del SVG**. Es lo que permite contrastar tres cosas a la vez: lo
dibujado, lo declarado en las tablas de `c4.md` y lo que hay en el sistema.

### Elementos

| Elemento (como sale del render) | ¿Está en el diagrama? | ¿Está en el sistema? | ¿Coinciden nombre, rol y nivel? |
|---|---|---|---|
| Pasajero (`Person`) — C1 y C2 | Sí | Sí: `/v1/reservations` con `Bearer` JWT | Sí |
| Operaciones (`Person`) — C1 y C2 | Sí | Parcial: la persona sí; **faltan el orquestador y el scraper**, que son sistemas | Rol incompleto — **H-04** |
| Sistema de reservas de vuelos (`System` en C1, `System_Boundary` en C2) | Sí | Sí | Nombre idéntico entre niveles. **Responsabilidad sobrante: «confirma»** — **H-01** |
| Proveedor de identidad (IdP) (`System_Ext`) — C1 y C2 | Sí | Sí: `JWT_JWK_SET_URI`, `application.yml:405` | Sí. Dirección correcta: sólo se le consulta |
| Catálogo de ciudades (`api-catalog`) (`System_Ext`) — C1 y C2 | Sí | Sí: [`RestCityCatalogClient.java:171`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/out/airport/catalog/RestCityCatalogClient.java#L171), `GET /city/{code}` | Sí, y con el mismo nombre en los dos niveles |
| Sistema de notificaciones (`System_Ext`) — C1 y C2 | Sí | Como sistema externo del diseño, sí; **coexistiendo con el consumidor de referencia, no** | Nombre sí; la descripción pierde «plantillas» en C2 — **H-05**, **H-09** |
| Aplicación de reservas (`Container`) — C2 | Sí | Sí: puertos 8080 y 9090 verificados (`application.yml:2` y `:655`) | Sí |
| Base de datos de reservas (`ContainerDb`) — C2 | Sí | Sí: PostgreSQL 17, `compose.yaml:53` | Sí |
| Caché distribuida (`ContainerDb`) — C2 | Sí | Sí: Redis 7, `compose.yaml:79` | Sí, con «OPCIONAL» en la tecnología |
| Broker de eventos (`ContainerQueue`) — C2 | Sí | Sí: RabbitMQ 4, `compose.yaml:135` | Nombre y tecnología sí; **la frontera lo pone del lado equivocado** — **H-08** |
| Plataforma de observabilidad / colector OTLP | **No** | **Sí**: `application.yml:731`, [ADR 0017](../adr/0017-trazas-distribuidas-con-alcance-acotado.md) | **Falta** — **H-04** |
| Orquestador (sondas de readiness/liveness) | **No** | **Sí**: `compose.yaml:456` | **Falta** — **H-04** |
| Ingress / gateway del borde | **No** | **Sí** como decisión: `README.md:675`, [`SecurityConfiguration.java:157`](../../src/main/java/com/edteam/reservations/infrastructure/security/SecurityConfiguration.java#L157) | **Falta**, y su ausencia hace mentir a cuatro etiquetas — **H-06** |

**Contra la tabla declarada:** los 10 elementos de §1 están los 10 dibujados y los niveles
coinciden; ninguno sobra respecto de la tabla. El criterio 4 está limpio en esa dirección. Lo
que sobra, sobra respecto del **sistema**.

### Relaciones

| Relación dibujada | ¿Está en el diagrama? | ¿Está en el sistema? | ¿Coinciden nombre, protocolo y dirección? |
|---|---|---|---|
| C1 pasajero → reservas `c4.md:103` | Sí | Parcial | **Nombre no**: «confirma» no existe. Dirección y protocolo, sí — **H-01** |
| C1 pasajero → idp `c4.md:104` | Sí | Sí | Sí |
| C1 operaciones → reservas `c4.md:105` | Sí | Sí | Dirección sí. **Alcance no**: «con token» no aplica a las sondas — **H-07** |
| C1 reservas → catalogo `c4.md:106` | Sí | Sí, `CityCatalogClient` | Sí. Dirección correcta (criterio 6): lo llama este sistema |
| C1 reservas → idp `c4.md:107` | Sí | Sí, JWKS | Sí. Dirección correcta: se le consulta, no llama |
| C1 reservas → notificaciones `c4.md:108` | Sí | Sí, como abstracción del par 9 + 11 | Dirección sí. **Contenido**: incluye un hecho inalcanzable — **H-02** |
| C2 pasajero → app `c4.md:139` | Sí | Sí | Dirección sí. **Protocolo**: HTTPS es del borde, no del proceso — **H-06**, **H-09** |
| C2 pasajero → idp `c4.md:140` | Sí | Sí | Dirección sí. La etiqueta pierde «el token» — **H-09** |
| C2 operaciones → app `c4.md:141` | Sí | Parcial | **Faltan** el orquestador y el scraper como origen; protocolo; y no dice dónde están las dos dead letters — **H-04**, **H-06**, **H-10** |
| C2 app → idp `c4.md:142` | Sí | Sí | Sí, salvo el protocolo — **H-06** |
| C2 app → catalogo `c4.md:143` | Sí | Sí | Sí, salvo el protocolo. Es la única saliente síncrona que sí lleva «Degrada» |
| C2 app → postgres `c4.md:144` | Sí | Sí: `ReservationPersistenceAdapter`, `JdbcEventOutbox`, `JdbcAuditTrailAdapter`, `JdbcProcessedMessageStore` | Sí: nombre, protocolo (JDBC) y dirección |
| C2 app → redis `c4.md:145` | Sí | Sí: `RedisCacheStore` | Sí, incluida la marca de degradación |
| C2 app → rabbit `c4.md:146` | Sí | Sí: `RabbitEventPublisher` vía `OutboxDispatchScheduler` | Dirección correcta —**publica**—. **Falta la marca de degradación** — **H-03** |
| C2 rabbit → app `c4.md:147` | Sí | Sí: `ReservationEventListener` | Dirección correcta —**recibe**—. **Falta la marca de opcional**, y la coexistencia con la siguiente es imposible — **H-03**, **H-05** |
| C2 rabbit → notificaciones `c4.md:148` | Sí | Sí, en el diseño | Dirección sí — **H-05** |
| app → colector OTLP | **No** | **Sí** | **Falta** — **H-04** |
| Prometheus → app (pull) y orquestador → app (sondas) | **No** | **Sí** | **Faltan** — **H-04** |

**Contra la tabla declarada:** las 11 filas de §2 se reparten en 6 relaciones de C1 y 10 de C2,
y el render devuelve exactamente 6 y 10. **No falta ni sobra ninguna relación respecto de la
tabla.** Todo el desajuste es contra el sistema, que es la parte que mirar un dibujo no expone.

---

## 4. Cómo detectar cada uno

| Hallazgo | Verificación concreta | Si está bien | Si está mal |
|---|---|---|---|
| Sintaxis de los dos bloques | `npm i mermaid@11` y correr `mermaid.parse()` **y** `mermaid.render()` sobre el bloque extraído; o pegar cada uno en mermaid.live y abrir el `.md` en GitHub. Todo gratis | Dibujo completo, sin banda roja | `Parse error on line N`: anotar N y el token exacto |
| Alias colgados en `Rel(...)` | El mismo render: el renderer C4 lanza `C4 rel "X" -> "Y" references an unknown shape` | Render OK | Excepción nombrando el par exacto |
| **H-01** y **H-02** | `grep -n '@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping\|@PatchMapping' ReservationController.java` y las rutas de `openapi.yaml`; después, por cada verbo de la etiqueta del C1, buscar su endpoint | Cada verbo de la etiqueta tiene su método y su operación en el contrato | Sobra un verbo sin endpoint. Segundo paso: `grep -rn "ConfirmReservationUseCase" src/main/java` — si sólo aparece bajo `application/`, no hay adaptador de entrada y el hecho es inalcanzable |
| **H-03** | Recorrer la columna «¿Opcional o degrada?» de §2 y, por cada fila marcada, buscar esa palabra en la etiqueta dibujada de la misma relación | Toda fila marcada tiene la marca también en el dibujo | Las relaciones 9 y 10 están marcadas en la tabla y no en el dibujo. Control cruzado: `grep -n "ENABLED\|enabled:" application.yml` y `grep -n "condition: service_" compose.yaml` |
| **H-04** | Listar todo lo que abre o recibe conexión: `grep -n "ports:\|healthcheck:\|targets:" compose.yaml docker/prometheus/prometheus.yml` y `grep -n "otlp\|endpoint:" application.yml`. Cada uno necesita su flecha | Cada entrada y cada salida tiene flecha, con el origen del tipo correcto: persona o sistema | Aparecen tres orígenes o destinos sin flecha. Chequeo de dirección: si la config del otro lado dice `targets:`, la flecha va **hacia** la app, no desde ella |
| **H-05** | Contar consumidores por cola: `grep -rn "CONSUMER_QUEUE\|@RabbitListener" src/main/java`, y contrastar con las flechas que salen del broker | Una flecha por consumidor realmente concurrente | Dos flechas saliendo del broker hacia dos destinos que se disputan la misma cola |
| **H-06** y **H-07** | `grep -rn "ssl:" src/main/resources/application.yml`, y leer los matchers de `SecurityConfiguration` con `grep -n "permitAll\|authenticated()" -B2` | Hay `server.ssl` y todos los `/actuator/**` exigen token | Grep vacío: ninguna flecha que termine en el contenedor puede decir HTTPS. `PROBES` con `permitAll`: «con token» es falso para las sondas |
| **H-08** | Por cada contenedor de adentro del boundary, preguntar quién declara y quién gobierna su configuración: `grep -n "declare-consumer-topology" application.yml` y `grep -n "son del" docs/messaging/topology.md` | Todo lo de adentro lo gobierna este repositorio | La topología dice explícitamente «no son nuestras» sobre algo dibujado adentro |
| **H-09** | Diff elemento por elemento y relación por relación entre los dos bloques: extraer los `Rel(a, b, "...")` de cada uno y comparar los pares que aparecen en ambos niveles | Misma etiqueta, o una que es recorte estricto de la otra | Un verbo, un sustantivo o un numeral que existe en un nivel y no en el otro |
| **H-10** | Mostrarle **sólo el C2** a alguien que no trabajó en el proyecto y preguntarle dónde están las dos dead letters | Señala `postgres` y `rabbit` | No puede responder: cada pregunta que necesita hacer es una etiqueta que falta |
| **H-11** | Recorrer [`docs/adr/README.md`](../adr/README.md) y, por cada ADR **Aceptado**, preguntar si cambia el C1, el C2 o ninguno; los que cambian el mapa y no están dibujados tienen que estar en §6 | Cada ADR aceptado está reflejado o excluido explícitamente | 0018 y 0019 no están ni en el dibujo ni en la lista de exclusiones |

---

## 5. Mitigación propuesta

Una o dos líneas por hallazgo. Este paso identifica y ordena: **los diagramas corregidos no se
redactan acá**.

| # | Mitigación |
|---|---|
| **H-01** | Sacar «confirma» y «confirmación» de la descripción del sistema, de la etiqueta de la relación y de las dos tablas. El C2 ya está bien y es el que marca el estándar; si se quiere dejar constancia, va en §6 con enlace a `README.md:668`, no en el dibujo |
| **H-02** | Reemplazar «los cuatro hechos» por los tres que el sistema puede emitir hoy, y anotar `reservation.confirmed` como definido en el contrato y no alcanzable hasta que se exponga la confirmación |
| **H-03** | Agregar a las dos etiquetas AMQP la misma marca corta que ya usan las de Redis y el catálogo, para que las tres dependencias degradables se lean igual y sólo `postgres` quede sin marca |
| **H-04** | Agregar un `System_Ext` de plataforma de observabilidad con dos flechas —trazas OTLP saliendo, scrape de Prometheus entrando— y un origen para las sondas del orquestador; y corregir la nota de §5, que hoy afirma una dirección que el código contradice |
| **H-05** | Dejar una sola flecha de consumo y marcar la otra como alternativa de entorno local, o anotar en las dos que son excluyentes. El texto de §5 ya lo dice: hay que subirlo a la etiqueta |
| **H-06** | Cambiar `HTTPS` por `HTTP` en las flechas del C2 que terminan en el proceso, y agregar el borde donde termina el TLS y vive el rate limiting real, o excluirlo explícitamente en §6 citando `README.md:675` |
| **H-07** | Reescribir la relación 3 como «sondas anónimas, métricas y endpoints de operación con token, sobre el puerto de gestión que no se publica», que es lo que dicen `PROBES`, `METRICS_SCRAPE` y el `compose.yaml` |
| **H-08** | Sacar el broker del `System_Boundary`, o partirlo —exchange adentro, colas del consumidor afuera—, que es lo que dice la topología, y dejar que la frontera exprese la propiedad real |
| **H-09** | Fijar la regla de que la etiqueta del C2 es la del C1 palabra por palabra o su recorte estricto, y correr el diff C1/C2 como parte de la revisión del diff del archivo |
| **H-10** | Partir la etiqueta de operación en dos flechas —una a `postgres` por la dead letter del productor y otra a `rabbit` a través de `app` por la del consumidor—, o nombrar los dos contenedores en la etiqueta |
| **H-11** | Agregar a §6 una línea que excluya el nivel de despliegue y remita a 0018 y 0019, y sumar la imagen `flight-reservations` a la tecnología del contenedor |

---

## 6. Simplificaciones deliberadas — no son hallazgos

| Simplificación | Por qué es correcta |
|---|---|
| El C1 no muestra PostgreSQL, Redis ni RabbitMQ | Es el nivel, y es la regla del modelo C4. Declarado en `c4.md:238-243` |
| El C2 no baja a paquetes ni a clases | Mismo motivo. El C3 no existe y el documento lo dice |
| Los frontends web y móvil no son contenedores | No están en este repositorio; se dicen en la descripción del `Person` |
| El MySQL del `api-catalog` no se dibuja | Es interno a un sistema externo. Dibujarlo sería violar la frontera |
| Prometheus, Grafana, Loki, Tempo y Alloy del `compose.yaml` no son contenedores del sistema | Correcto: son el entorno de desarrollo, detrás del profile `observability`. **Lo que sí es hallazgo es el argumento con que se los excluye y la ausencia de su contraparte desplegada** (H-04) |
| Las colas del consumidor no se dibujan una por una | Son detalle interno del contenedor broker y propiedad del consumidor. Están en [`topology.md`](../messaging/topology.md) §3 |
| Las etiquetas del dibujo son más cortas que las filas de §2: falta «usuarios» y el inbox, falta el `0-9-1` de AMQP, faltan el pool y los timeouts | Regla declarada en `c4.md:76-79`: el dibujo dice qué viaja y sobre qué protocolo, el detalle vive en la tabla. Lo verificable es que no digan cosas **distintas**, y no las dicen |
| «Base de datos de reservas» en vez de «PostgreSQL» | El nombre es el rol y el producto va en la tecnología. La tabla de traducción hacia los ADR y el código está en `c4.md:40-51` |
| La etiqueta `app → rabbit` nombra el relay del outbox, que es un componente interno | Es fuga de C3 al C2, pero es lo único que distingue dos flechas entre el mismo par de cajas. Sin eso, `app ↔ rabbit` es ambiguo |
| `OutboxDispatchScheduler` y `MessagingPurgeScheduler` no tienen actor de origen | Son adaptadores de entrada disparados por reloj, y un reloj no es un actor externo |
| No hay imagen exportada del diagrama | Declarado en `c4.md:265-267`, y es la decisión correcta: así se revisa en el diff |
| El stub en memoria del catálogo y el `InMemoryCacheStore` no son elementos | Son caminos de código dentro del contenedor, no contenedores. El fallback está en la etiqueta |
| Que el C1 dibuje al IdP sin marca de opcionalidad aunque el default del repositorio use tokens HMAC de desarrollo | El diagrama describe el sistema desplegado, y la desviación local está dicha en `c4.md:216-219`. Es la misma convención que ya se aplica al catálogo |

---

## 7. Orden sugerido de corrección

**Criterio:** cuánto daño hace creerle al diagrama, no cuánto cuesta arreglarlo. Primero lo que
hace tomar una decisión equivocada a alguien externo al equipo; después lo que hace leer mal el
acoplamiento; al final lo que sólo hace leer más lento. **Ninguno es bloqueante por render: los
dos bloques dibujan.**

| Orden | Hallazgo | Por qué va acá |
|---|---|---|
| **1** | **H-01** | Una capacidad de negocio inexistente, en la caja principal del C1. Es lo primero que lee alguien nuevo y lo primero que se cita en una reunión de alcance |
| **2** | **H-02** | Un evento del contrato que nadie puede emitir. Es la clase de error que se paga en el repositorio de otro equipo, no en éste |
| **3** | **H-05** | Dos consumidores dibujados sobre una cola que admite uno. Un consumidor nuevo se conecta creyendo que el fan-out ya funciona, y roba mensajes |
| **4** | **H-03** | La degradación del broker, invisible. Es la decisión central del sistema y el criterio explícito de esta auditoría |
| **5** | **H-04** | Faltan los dos actores automáticos que llaman al puerto de gestión y la única señal saliente. Cambia cómo se dimensiona la exposición del 9090 |
| **6** | **H-06** | TLS atribuido al proceso, y el borde ausente. Mueve el límite de confianza del modelo de amenazas |
| **7** | **H-08** | El broker del lado equivocado de la frontera. Invierte quién es dueño de qué, que es lo que el diagrama existe para responder |
| **8** | **H-07** | «Con token» sobre sondas anónimas. Error puntual de etiqueta, pero sobre una superficie de seguridad |
| **9** | **H-10** | Dónde viven las dos dead letters. Cuesta un turno de preguntas a quien opera, no una decisión equivocada |
| **10** | **H-09** | Deriva de etiquetas entre niveles. Barato de arreglar, y arreglarlo es lo que evita que vuelva H-01 |
| **11** | **H-11** | El nivel de despliegue, ni dibujado ni excluido. Deuda documental, sin lector engañado |
