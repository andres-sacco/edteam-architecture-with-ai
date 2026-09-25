# 0001 — Arquitectura hexagonal en un solo módulo, con separación por paquetes

- **Estado:** Aceptado
- **Fecha:** 2026-09-25
- **Prompt origen:** [02 — Comparación: Layers vs. Hexagonal](../prompts/02-comparacion-layers-vs-hexagonal.md)
- **Llevado al código en:** [03 — Esqueleto del proyecto](../prompts/03-esqueleto-proyecto-hexagonal.md)
- **Custodiado por:** `HexagonalArchitectureTest` (ArchUnit, en surefire)

> Redactado retroactivamente el 2026-09-25. El número refleja el orden en que se
> tomó la decisión, no el orden en que se escribió el documento.

## Contexto

El sistema lo iban a consumir varios frontends —web, mobile y potencialmente
partners por API—, así que la lógica de negocio tenía que estar en un solo lugar
y no en la punta HTTP. Dos de las piezas del flujo no las controlamos: el
catálogo de aeropuertos, que valida origen y destino antes de crear o modificar
una reserva, y el sistema externo de notificaciones, **del que explícitamente no
queríamos depender de forma sincrónica** para que su caída no se llevara puesta
la disponibilidad de las reservas.

Al momento de decidir había tres cosas abiertas que la arquitectura tenía que
poder absorber sin una reescritura: si el maestro de aeropuertos iba a ser propio
o de un proveedor externo, cómo se iban a mandar las notificaciones, y qué motor
de persistencia se iba a usar. Ninguna estaba cerrada, y las tres eran decisiones
de borde.

Las restricciones eran presupuesto acotado y Java. Los criterios de comparación,
fijados de antemano: testabilidad, acoplamiento, costo de mantenimiento y curva
de aprendizaje.

## Decisión

**Arquitectura hexagonal, en un único módulo Maven, con la separación hecha por
paquetes.**

```
com.edteam.reservations
├── domain          # model, event, access, exception — sin Spring, sin JPA, sin HTTP
├── application     # port/in, port/out, service, query, …
└── infrastructure  # adapter/in/*, adapter/out/*, cache, config, logging, security
```

Tres reglas, y las tres son verificables:

1. **Las dependencias van hacia adentro.** `infrastructure` conoce a
   `application`, `application` conoce a `domain`, y nadie va al revés.
2. **El dominio no importa ningún framework.** Ni Spring, ni `jakarta.persistence`,
   ni tipos de HTTP, ni un logger.
3. **Todo lo que cruza el borde pasa por un puerto**, y los puertos son interfaces
   que declara `application`, no el adaptador.

La regla no es un acuerdo verbal: `HexagonalArchitectureTest` tiene hoy dieciocho
reglas de ArchUnit que la sostienen, corre en `./mvnw test` y rompe el build. Ese
test es lo que hace que la decisión siga viva después de que la escribimos; sin
él, la separación dura hasta el primer apuro.

**Un módulo y no cinco.** La separación física por módulos Maven daría la misma
garantía que da ArchUnit, a cambio de cinco `pom.xml`, un ciclo de build más largo
y un `mvn install` para probar un cambio de una línea. La garantía la compra el
test; los módulos sólo se justifican el día que dos equipos distintos publiquen
artefactos con ciclos de vida distintos.

### Alternativas descartadas

| Alternativa | Por qué se descartó | Qué la volvería a poner sobre la mesa |
|---|---|---|
| **Arquitectura por capas (n-tier)** | Es más barata de aprender y más rápida de arrancar, pero la capa de negocio queda debajo de la de persistencia: el servicio importa el repositorio concreto y el modelo termina teniendo la forma de las tablas. Con tres bordes todavía sin decidir (catálogo, notificaciones, motor de base), cada uno de esos cambios habría sido una reescritura del núcleo | Un sistema sin dependencias externas relevantes, o uno de vida corta donde el costo de reescribirlo sea menor que el de la indirección |
| **Hexagonal con módulos Maven separados** | La garantía que compra —que `domain` no *pueda* compilar contra Spring— ya la da un test que corre en 13 s. El costo es multiplicar el build y el tiempo de cada cambio | Dos equipos publicando artefactos con ciclos de release independientes, o la necesidad de publicar el dominio como librería |
| **Microservicios desde el día uno** | Presupuesto acotado y un solo dominio con un solo agregado. Serían límites de red sobre fronteras que todavía no sabíamos dónde estaban | Que un borde del hexágono empiece a tener su propio ritmo de cambio y su propia carga; los puertos ya marcan por dónde cortar |
| **`@Cacheable`, `@PreAuthorize`, `@Retryable` sobre los servicios de aplicación** | Cada anotación mete al framework adentro del núcleo y ata la lógica al camino HTTP. Se descartó ya con la decisión de arriba; se volvió a descartar en concreto tres veces (cache, seguridad, resiliencia) | Nada previsible: es la regla que ArchUnit custodia |

## Consecuencias

### A favor

- **Cambiar un borde cuesta un adaptador, no una reescritura.** Está medido: al
  reemplazar el outbox en memoria por PostgreSQL + RabbitMQ
  ([0004](0004-mensajeria-asincronica-y-broker.md)), los casos de uso, los eventos
  de dominio y `OutboxDispatcherService` no se tocaron.
- **La lógica de negocio se prueba sin levantar Spring.** `ReservationTest`,
  `ItineraryTest`, `ReservationAccessPolicyTest` y el resto del dominio corren con
  objetos planos; la suite unitaria completa son ~650 tests en ~13 s **sin Docker
  y sin red**, que es lo que permite que el pipeline tenga un ciclo corto real.
- **La autorización vale para cualquier adaptador de entrada.** Como la regla vive
  en `domain.access` y el solicitante viaja en el comando, el consumidor de
  mensajería y las tareas programadas la heredan; con `@PreAuthorize` sólo la
  tendría el REST.
- **Cada dependencia opcional puede tener un doble.** El catálogo cae a
  `StaticAirportCatalog`, el broker a `LoggingEventPublisher`, Redis a
  `InMemoryCacheStore`: la aplicación arranca y la suite corre sin ninguno de los
  tres levantado, porque los tres son adaptadores intercambiables detrás del mismo
  puerto.

### En contra, y asumido

- **Tres representaciones del mismo dato y dos mapeos a mano.** Una reserva es
  `Reservation` (dominio), `ReservationJpaEntity` (persistencia) y
  `ReservationResponse` (API), con `ReservationMapper` y `ReservationRestMapper` en
  el medio. Agregar un campo se toca en cinco archivos, y hay dos tests
  (`PersistenceMapperTest`, `ReservationRestMapperTest`) que existen sólo para
  cuidar esa traducción.
- **La curva de aprendizaje es real y el equipo la paga.** Alguien que llega del
  patrón MVC de Spring pregunta por qué el controller no llama al repositorio.
  Se mitiga con los `package-info.java` de cada paquete, que documentan qué va y
  qué no va adentro, pero sigue siendo tiempo de onboarding.
- **ArchUnit frena entregas.** Dieciocho reglas rompen el build por razones que a
  veces parecen formales —una clase en el paquete equivocado—. Es el precio de que
  la arquitectura no se erosione, y está aceptado: el día que se apague una regla
  para pasar un build, la decisión de este ADR deja de existir aunque el documento
  siga acá.
- **Un módulo significa que la regla es sólo el test.** Nada impide físicamente un
  `import` de Spring en `domain`: lo impide un test que alguien podría marcar como
  `@Disabled`.
