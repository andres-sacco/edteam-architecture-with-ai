package com.edteam.reservations.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * Base de los tests de integración: PostgreSQL real en Docker.
 *
 * <p>Se usa una base de verdad y no H2 porque lo que hay que validar es
 * justamente lo específico de PostgreSQL: las migraciones de Flyway, los
 * {@code UNIQUE} que sostienen la idempotencia, el
 * {@code INSERT ... ON CONFLICT} y el comportamiento del {@code TIMESTAMP} sin
 * zona. Con una base en memoria, estos tests pasarían sin probar nada de eso.
 *
 * <p>El contenedor se levanta una sola vez para todos los tests de integración
 * (patrón <em>singleton container</em>): arrancar uno por clase multiplicaría el
 * tiempo del build sin agregar aislamiento, porque el estado se limpia en cada
 * test.
 *
 * <p>Estos tests corren en la fase {@code verify} (failsafe), no en
 * {@code test}: {@code mvn test} sigue siendo rápido y sin Docker.
 */
@SpringBootTest(properties = {
        // El despacho se dispara a mano en los tests, para que no compita con las aserciones.
        "reservations.outbox.dispatch-enabled=false",
        // Y la purga también: un cron que corra en medio de un test le borraría
        // las filas que está asertando.
        "reservations.outbox.purge-enabled=false",
        // Los gauges del outbox cachean su foto unos segundos para no
        // convertir el monitoreo en carga sobre la base. Acá el test escribe y
        // lee en la misma milésima, así que la ventana tiene que ser cero: es
        // el mismo motivo por el que el total paginado se cachea 1ms.
        "reservations.outbox.metrics-cache=0",
        // Mensajería apagada: el publicador es el que sólo loguea. El build no
        // puede depender de que haya un broker levantado, y con esto se
        // verifica en cada corrida que la aplicación arranca y funciona sin él
        // —que es una restricción explícita del diseño—. Los tests que sí
        // necesitan un broker real lo levantan con Testcontainers y lo
        // encienden ellos.
        "reservations.messaging.enabled=false",
        "reservations.messaging.declare-consumer-topology=false",
        "reservations.messaging.consumer-enabled=false",
        // El esquema lo crea Flyway; que Hibernate valide que el mapeo coincide.
        "spring.jpa.hibernate.ddl-auto=validate",
        // Maestro de ciudades: el stub en memoria. El build no puede depender de
        // que el servicio de catálogo esté levantado; lo que hay que probar acá
        // es el flujo contra PostgreSQL, y la traducción HTTP ya tiene sus tests.
        "reservations.airport-catalog.base-url=",
        // Cache en memoria, por el mismo motivo: el build no puede depender de
        // que haya un Redis levantado. Con esto además se verifica en cada
        // corrida que la aplicación arranca y funciona sin el cache
        // distribuido, que es una restricción explícita del diseño.
        "reservations.cache.redis.enabled=false",
        // El total de una paginación se cachea 45 segundos y no se invalida:
        // es una pista para la interfaz, no un invariante, y ésa es una
        // decisión de diseño explícita. Pero en los tests el contexto se
        // comparte y la base se trunca por detrás del cache, así que un total
        // cacheado de un test anterior haría que el resultado del siguiente
        // dependa del orden de ejecución. Con un TTL de 1ms el cache existe
        // —se siguen contando sus métricas— y nunca acierta entre tests.
        "reservations.cache.reservation-count-ttl=1ms",
        // Tokens HMAC firmados con la clave de desarrollo: es lo que permite
        // que el test de integración ejercite la cadena completa —firma,
        // claims, conversión a Actor, autorización por recurso— sin depender
        // de que haya un proveedor de identidad levantado. Mismo criterio que
        // con Redis y con el catálogo.
        "reservations.security.jwt.dev-tokens=true",
        // La cuota se prueba aparte: un contador por proceso compartido entre
        // los tests haría que el resultado dependa del orden de ejecución.
        "reservations.security.rate-limit.enabled=false",
        // Encendidos a propósito: lo que hay que verificar no es que estén
        // apagados —eso es el default de application.yml— sino que, cuando un
        // entorno decide publicarlos, se puedan usar de verdad y que lo que la
        // UI ejecuta siga exigiendo token.
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true",
        // Actuator vuelve al puerto de la aplicación sólo para los tests:
        // MockMvc levanta un único contexto servlet simulado y no puede pedir
        // contra dos puertos. Que en producción vaya a un puerto de gestión
        // aparte es una decisión de despliegue, no de código, y por eso no hay
        // test que la cubra: la cubre el manifiesto que no publica ese puerto.
        "management.server.port="
})
public abstract class AbstractPostgresIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("reservations")
                    .withUsername("reservations")
                    .withPassword("reservations");

    static {
        POSTGRES.start();
    }

    /** Orden de borrado: primero las tablas que referencian a otras. */
    private static final String TRUNCATE_ALL = """
            TRUNCATE TABLE auditoria, reserva_pasajero, reserva, itinerario_segmento, itinerario,
                           segmento, pasajero, usuario,
                           outbox_message, processed_message, notificacion_entrega
            RESTART IDENTITY CASCADE
            """;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    private TransactionTemplate transactionTemplate;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute(TRUNCATE_ALL);
    }

    /**
     * Ejecuta la acción dentro de una transacción, como lo hace un caso de uso.
     *
     * <p>Importa para los tests del adaptador: el optimistic locking y el
     * {@code ON CONFLICT} sólo se comportan como en producción si la lectura y
     * la escritura comparten transacción.
     */
    protected <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }

    protected void inTransaction(Runnable action) {
        inTransaction(() -> {
            action.run();
            return null;
        });
    }

    /** Crea un usuario y devuelve su id. La reserva necesita uno por la clave foránea. */
    protected long insertUser(String email) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO usuario (email, nombre, apellido, fecha_alta) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, email, "Ana", "Pérez", java.sql.Timestamp.from(Instant.now()));
    }

    /**
     * Filas de auditoría de una acción.
     *
     * <p>{@code TRUNCATE} no dispara el trigger que hace append-only la tabla
     * —los triggers {@code BEFORE DELETE} no se ejecutan en un truncate—, así
     * que los tests pueden limpiarla entre corridas sin bajar la protección.
     */
    protected long countAudit(String action) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM auditoria WHERE accion = ?", Long.class, action);
        return count == null ? 0L : count;
    }

    /**
     * {@code now()} en UTC, para el SQL crudo de los tests.
     *
     * <p>Las columnas {@code TIMESTAMP} sin zona guardan UTC, y el driver pone
     * la zona de la JVM en la sesión: un {@code now()} pelado escribiría la
     * hora de pared local y quedaría desfasada respecto de lo que escribió la
     * aplicación. Es el mismo desfase que {@code Utc} evita en los adaptadores.
     */
    protected static final String NOW_UTC = "(now() AT TIME ZONE 'UTC')";

    /** Mensajes del outbox en el estado indicado. */
    protected long countOutbox(String status) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_message WHERE status = ?", Long.class, status);
        return count == null ? 0L : count;
    }

    /** Tipos de los mensajes del outbox en ese estado, en orden de secuencia. */
    protected java.util.List<String> outboxTypes(String status) {
        return jdbcTemplate.queryForList(
                "SELECT type FROM outbox_message WHERE status = ? ORDER BY sequence", String.class, status);
    }

    protected long countRows(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }
}
