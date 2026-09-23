# 28 — Diagramas C4 de Contexto y Contenedores en Mermaid

**Etapa:** Documentación

**Salida esperada:** Dos bloques de código Mermaid, uno por diagrama, listos para pegar en un visor

---

## Rol

Actúa como arquitecto de software generando diagramas C4 en sintaxis Mermaid.

## Contexto

El **modelo C4** de Simon Brown describe un sistema en niveles jerárquicos de abstracción —Contexto, Contenedores, Componentes y Código—, pensados para comunicar sin ambigüedad. Su estructura estandarizada se escribe bien en un lenguaje de marcado como Mermaid, de modo que el diagrama viva versionado junto al código en lugar de en una herramienta aparte.

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

**Los actores y sistemas que rodean al de reservas:**

- **Usuarios finales** (pasajeros) a través de varios **frontends**, con muchos usuarios concurrentes, que consumen la API REST `/v1/reservations` autenticada con **JWT** (altas, lecturas, listado, modificación, confirmación y cancelación, con locking optimista expuesto como `ETag`/`If-Match`).
- **Proveedor de identidad (IdP)**: emite los tokens y publica el JWKS que este servicio consulta para validarlos (`JWT_JWK_SET_URI`, `JWT_ISSUER`, `JWT_AUDIENCE`). En local se reemplaza por tokens HMAC de desarrollo.
- **Catálogo de ciudades** (`api-catalog`): sistema externo consultado por REST en cada `POST` y `PUT` para validar los códigos del itinerario, con timeouts propios, reintentos sobre el `GET` y *stale-while-error*.
- **Sistema de notificaciones**: consumidor de los hechos de negocio que este sistema publica. No lo llama nadie de forma síncrona.
- **Operaciones**: consume Actuator en un puerto propio (`9090`, no publicado hacia afuera) para sondas, métricas y los endpoints `outbox` y `messaging-dlq`.

**Los contenedores del sistema, tal como están hoy:**

- La **aplicación de reservas** (Spring Boot, puerto `8080`): un único proceso que contiene la API REST, los casos de uso, el relay del outbox por `@Scheduled` y el consumidor de referencia de la mensajería.
- **PostgreSQL 17**: reservas, usuarios, auditoría, la tabla `outbox_message` y el inbox de deduplicación. El esquema lo gobierna **Flyway**.
- **Redis 7**: caché distribuida **opcional**, con fallback en memoria dentro del proceso. Su caída degrada al origen.
- **RabbitMQ 4**: topic exchange `reservations.events` al que el relay publica los cuatro hechos de negocio (`reservation.created`, `confirmed`, `modified`, `cancelled`), con cola de espera y dos dead letters.
- El `compose.yaml` levanta esos tres más el `api-catalog` con su propio **MySQL**.

**Las decisiones que el diagrama tiene que reflejar** están registradas en [`docs/adr/`](../adr/README.md) —[0003](../adr/0003-autenticacion-autorizacion-y-datos-sensibles.md), [0004](../adr/0004-mensajeria-asincronica-y-broker.md), [0005](../adr/0005-garantias-de-entrega-y-remediacion-de-la-mensajeria.md) y los redactados en [26](26-adrs-de-las-decisiones.md)— y en la topología de [`docs/messaging/topology.md`](../messaging/topology.md).

**Hoy no hay ningún diagrama en el repositorio**: no existe ni un bloque Mermaid ni una imagen de arquitectura en `docs/`.

## Tarea

1. **Listar primero, dibujar después**: armar la tabla de elementos —personas, sistemas externos y contenedores— y la tabla de relaciones, con su descripción y su tecnología. Esa tabla es la fuente del diagrama y la que después permite verificarlo.
2. **Diagrama de Contexto (C1)**: el sistema de reservas como una caja, con las personas y los sistemas externos que interactúan con él, y qué intercambia con cada uno. Nada de detalle interno.
3. **Diagrama de Contenedores (C2)**: las unidades desplegables del sistema —la aplicación, PostgreSQL, Redis, RabbitMQ— con la tecnología de cada una y los protocolos de cada relación. Los sistemas externos del C1 se mantienen, con los mismos nombres.
4. **Etiquetar cada relación** con qué viaja y sobre qué protocolo: `HTTPS/JSON`, `JDBC`, `RESP`, `AMQP`. Una flecha sin etiqueta no comunica nada.
5. **Marcar lo que es opcional y lo que degrada**: Redis y el broker son dependencias de las que el sistema no depende para responder, y eso tiene que verse o estar dicho en el diagrama, porque es una de las decisiones centrales del sistema.
6. **Mostrar la dirección real de cada relación**: el relay del outbox **publica** hacia el broker y el consumidor **recibe** de él; el catálogo lo llama este sistema y no al revés; el IdP no llama a nadie, se le consulta el JWKS.
7. **Verificar la sintaxis**: los dos bloques tienen que renderizar sin errores en un visor de Mermaid antes de darlos por válidos.
8. **Dejarlos versionados** en `docs/` como Markdown con los bloques Mermaid embebidos, enlazados desde el `README.md` del repositorio y desde los ADR que documentan las decisiones que el diagrama muestra.

## Restricciones

- **Sintaxis válida de Mermaid**, usando `C4Context` para el primero y `C4Container` para el segundo. Tiene que renderizar tal cual, sin retoques.
- **Nombres consistentes entre los dos diagramas y con el resto de la documentación**: un elemento que en el C1 se llama de una forma no puede llamarse distinto en el C2, ni distinto de como lo nombran los ADR, la topología de mensajería y el código.
- **El C2 no puede contradecir al C1**: todo sistema externo del contexto aparece en contenedores con el mismo nombre y el mismo rol, y no aparecen relaciones nuevas hacia afuera que el C1 no muestre.
- **Sólo lo que existe.** Nada de contenedores planeados, servicios que se van a separar en el futuro ni piezas que el código no tiene. Si algo es una intención, va fuera del diagrama.
- **Un nivel por diagrama**: el de contexto no muestra bases de datos ni brokers, y el de contenedores no baja a clases ni a paquetes.
- **Legible sin contexto extra**: alguien que no conoce el proyecto tiene que poder entender qué hace el sistema y con quién habla leyendo sólo el diagrama y sus etiquetas.
- **Sin datos sensibles**: ninguna URL real de producción, ningún nombre de host interno, ninguna credencial.
- **En español**, con el mismo vocabulario que los ADR y la documentación existente.

## Formato de salida

1. **Tabla de elementos**: nombre | tipo (persona / sistema externo / contenedor) | tecnología | responsabilidad | nivel donde aparece (C1, C2 o ambos).
2. **Tabla de relaciones**: origen | destino | qué viaja | protocolo | ¿es opcional o degrada? | nivel donde aparece.
3. **Bloque Mermaid del diagrama de Contexto (C1)**, listo para pegar en un visor.
4. **Bloque Mermaid del diagrama de Contenedores (C2)**, listo para pegar en un visor.
5. **Notas del diagrama**: lo que el dibujo no alcanza a decir y hace falta para leerlo (qué degrada, qué es opcional, qué garantía tiene la mensajería).
6. **Dónde se guardan**: ruta de los archivos en `docs/` y desde dónde quedan enlazados.
