package com.edteam.reservations.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Conversión entre {@link Instant} y las columnas {@code TIMESTAMP} sin zona,
 * <b>siempre en UTC</b>.
 *
 * <h2>Por qué hace falta y no alcanza con {@code java.sql.Timestamp}</h2>
 * Las columnas del modelo de datos son {@code TIMESTAMP} sin zona y guardan
 * UTC. Hibernate lo cumple porque se lo dice
 * {@code spring.jpa.properties.hibernate.jdbc.time_zone: UTC}, pero <b>esa
 * propiedad no la lee {@code JdbcTemplate}</b>: un
 * {@code Timestamp.from(instant)} se escribe con la representación de pared de
 * la zona por defecto de la JVM.
 *
 * <p>El resultado es silencioso y desagradable: la aplicación queda consistente
 * consigo misma —escribe y compara con el mismo desfase, y el driver además
 * pone la zona de la JVM en la sesión, así que un {@code now()} desde el
 * <em>pool</em> también coincide— y queda inconsistente con todo lo demás. Una
 * fila de {@code outbox_message} y una de {@code reserva} escritas en el mismo
 * segundo muestran horas distintas; un {@code psql} desde el contenedor —o
 * cualquier consulta de operaciones que compare con {@code now()}— da un
 * resultado equivocado por el desfase de la zona del servidor de aplicación.
 * Justo en las tablas donde la consulta manual es el procedimiento
 * documentado para drenar la dead letter.
 *
 * <p>{@link LocalDateTime} no tiene zona, así que el driver lo escribe y lo lee
 * tal cual, sin convertir nada. Pasando por él, lo que queda en la columna es
 * UTC cualquiera sea la zona de la JVM, que es la única propiedad que hace
 * falta.
 */
public final class Utc {

    private Utc() {
    }

    /** Parámetro para un {@code TIMESTAMP} sin zona, en UTC. */
    public static LocalDateTime param(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** Lee un {@code TIMESTAMP} sin zona interpretándolo como UTC. */
    public static Instant read(ResultSet resultSet, String column) throws SQLException {
        LocalDateTime value = resultSet.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
