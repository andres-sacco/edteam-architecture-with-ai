# 0006 — El esquema lo gobierna Flyway y Hibernate sólo lo valida

- **Estado:** Aceptado
- **Fecha:** 2026-09-25
- **Prompt origen:** [05 — Persistencia y dominio](../prompts/05-persistencia-y-dominio.md)
- **Se apoya en:** [0002 — Modelo relacional en 3FN](0002-modelo-relacional-3fn-en-postgresql.md)
- **Custodiado por:** `spring.jpa.hibernate.ddl-auto: validate` en `application.yml`; `AbstractPostgresIT` corre las migraciones reales

> Redactado retroactivamente el 2026-09-25. El número refleja el orden en que se
> tomó la decisión, no el orden en que se escribió el documento.

## Contexto

El modelo de [0002](0002-modelo-relacional-3fn-en-postgresql.md) existía como un
script DDL suelto y la aplicación persistía en un stub en memoria. Al conectar
Spring Data JPA aparecía la pregunta de siempre: quién es el dueño del esquema.

El default cómodo de Spring Boot es `ddl-auto: update`, que hace que el esquema
lo derive Hibernate de las entidades. Eso arranca rápido y tiene dos problemas
que no se ven hasta que duelen: `update` **nunca borra ni modifica** una columna
—sólo agrega—, así que el esquema real deriva del que alguien creería leyendo las
entidades; y el DDL de 0002 tiene cosas que una anotación JPA no expresa
(`CHECK (origen <> destino)`, los `COMMENT ON CONSTRAINT` que explican para qué
está cada restricción, el índice parcial que vendría después con el outbox).

El otro hecho del contexto: la base tenía que poder levantarse sin instalar nada,
por `compose.yaml`, y los tests de integración tenían que correr contra un
PostgreSQL real.

## Decisión

**Las migraciones versionadas de Flyway son la única fuente de verdad del
esquema. Hibernate lo valida al arrancar y no lo toca.**

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false
```

Tres consecuencias directas de esa frase:

1. **El esquema se cambia escribiendo un `VNN__descripcion.sql`**, nunca editando
   una entidad y confiando en que Hibernate se dé cuenta. Hoy son tres:
   `V1__esquema_inicial.sql`, `V2__seguridad_y_datos_sensibles.sql` y
   `V3__mensajeria_outbox_e_idempotencia.sql`.
2. **Una entidad que no coincide con la tabla impide arrancar.** `validate` es un
   gate, no un aviso: la aplicación no levanta, y eso es mejor que descubrirlo con
   un `column does not exist` en el primer pedido de producción.
3. **`baseline-on-migrate: false`**: si Flyway encuentra una base con tablas y sin
   su tabla de historial, falla. Es deliberado — lo contrario adopta en silencio
   una base cuyo estado nadie verificó.

Las migraciones **llevan su motivo escrito adentro**. `V2` no dice sólo
`DROP CONSTRAINT uq_pasajero_doc`: dice qué amenaza cerraba ese cambio. Es el
único lugar donde ese "por qué" está pegado al `ALTER`.

### Alternativas descartadas

| Alternativa | Por qué se descartó | Qué la volvería a poner sobre la mesa |
|---|---|---|
| **`ddl-auto: update`** | Deriva el esquema de las entidades, nunca borra nada, y no puede expresar los `CHECK`, los comentarios ni los índices parciales del DDL. El esquema real y el esperado se separan sin que nada falle | Nada: es exactamente el modo de falla que `validate` existe para evitar |
| **`ddl-auto: create-drop` en los tests, migraciones sólo en producción** | Los tests dejarían de probar las migraciones, que es justamente el artefacto que va a correr contra la base real. Un `CHECK` roto se descubriría en el despliegue | Nada. `AbstractPostgresIT` levanta el contenedor y corre Flyway, que es lo que hace que una migración mal escrita rompa el build |
| **Liquibase** | Equivalente en garantías y más portable entre motores gracias a su DSL, pero acá el motor está decidido (0002) y el SQL plano se lee y se revisa mejor en un diff. El XML/YAML intermedio sería una capa sin comprador | Tener que soportar dos motores de base a la vez |
| **Scripts DDL sueltos aplicados a mano** | Es lo que había. No hay forma de saber qué base tiene aplicado qué, y el orden depende de que alguien se acuerde | Nada |

## Consecuencias

### A favor

- **Se puede saber en qué estado está cualquier base** mirando
  `flyway_schema_history`, y el camino desde una base vacía hasta la actual es
  reproducible con un comando.
- **Una entidad que se desincroniza no llega a producción**: rompe el arranque en
  local y rompe los tests de integración, que corren las mismas migraciones contra
  `postgres:17-alpine` con Testcontainers.
- **Los cambios de esquema se revisan como código.** El `DROP CONSTRAINT` de V2
  llegó por pull request con su justificación al lado, no por una sesión de `psql`.
- **La migración puede hacer lo que la anotación no sabe hacer**: el trigger
  *append-only* de `auditoria` y el índice parcial de pendientes del outbox existen
  porque el esquema lo escribimos nosotros.

### En contra, y asumido

- **Agregar un campo son dos archivos, siempre.** La entidad y la migración, y en
  ese orden no importa: si divergen, no arranca. Es fricción real en cada cambio
  chico.
- **Las migraciones son inmutables y los errores también.** Un `VNN` ya aplicado
  no se edita: se corrige con otro. El historial acumula pasos que existen sólo
  para deshacer un paso anterior.
- **No hay rollback automático.** Flyway aplica hacia adelante; volver atrás es
  escribir la migración inversa a mano. Con una migración que perdió datos, eso no
  alcanza — la red es el backup, no el esquema.
- **`baseline-on-migrate: false` rompe el arranque contra una base preexistente**
  que no haya pasado por Flyway. Es el comportamiento buscado y es una piedra
  segura con la que alguien va a tropezar al conectar un entorno viejo.
- **Una migración larga bloquea el arranque de todas las instancias.** Con las
  tablas de hoy (100 k filas por entidad) es irrelevante; a un orden de magnitud
  más, un `ALTER TABLE` con reescritura va a necesitar ejecutarse fuera del ciclo
  de despliegue.
