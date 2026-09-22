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
        // El esquema lo crea Flyway; que Hibernate valide que el mapeo coincide.
        "spring.jpa.hibernate.ddl-auto=validate"
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
            TRUNCATE TABLE reserva_pasajero, reserva, itinerario_segmento, itinerario, segmento, pasajero, usuario
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

    protected long countRows(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }
}
