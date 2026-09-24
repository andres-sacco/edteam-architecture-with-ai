package com.edteam.reservations;

import com.edteam.reservations.support.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DataSourceUnwrapper;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los techos del lado de la base están puestos de verdad, no sólo escritos.
 *
 * <p>El hallazgo era que no había ninguno: ni {@code statement_timeout}, ni
 * {@code @Transactional(timeout)}, y un {@code connection-timeout} de tres
 * segundos. El {@code connection-timeout} acota la espera <em>por</em> una
 * conexión, no el uso de la que ya se tomó: una base lenta retenía las veinte
 * conexiones sin corte y toda la API caía, incluidos los {@code GET} que no
 * tocaban la tabla lenta.
 *
 * <p>La contracara —que todo método transaccional declare su techo— es una
 * regla de ArchUnit, porque eso se puede verificar sin levantar nada.
 */
@DisplayName("Techos del lado de la base")
class DatabaseCeilingsIT extends AbstractPostgresIT {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("el driver aplica statement_timeout: es el corte que cubre el flush y el commit")
    void theDriverAppliesAStatementTimeout() {
        // Va en el driver y no sólo en 'jakarta.persistence.query.timeout'
        // porque esa propiedad no cubre el flush ni el commit, que es justo
        // donde pega una base lenta.
        String timeout = jdbcTemplate.queryForObject("SHOW statement_timeout", String.class);

        assertThat(timeout)
                .as("sin esto, una sentencia lenta retiene su conexión sin límite")
                .isEqualTo("2s");
    }

    @Test
    @DisplayName("la espera por una conexión es de un segundo: rechazar es mejor que agrandar la cola")
    void theConnectionTimeoutIsOneSecond() {
        HikariDataSource hikari = DataSourceUnwrapper.unwrap(dataSource, HikariDataSource.class, HikariDataSource.class);

        assertThat(hikari).isNotNull();
        assertThat(hikari.getConnectionTimeout())
                .as("con 20 conexiones y consultas por índice, esperar 3 s significa cientos de pedidos "
                        + "adelante en la cola")
                .isEqualTo(1_000L);
    }
}
