package com.edteam.reservations.infrastructure.adapter.out.audit;

import com.edteam.reservations.application.audit.AuditEntry;
import com.edteam.reservations.application.audit.AuditOutcome;
import com.edteam.reservations.application.port.out.AuditTrailPort;
import com.edteam.reservations.infrastructure.logging.LogSanitizer;
import com.edteam.reservations.infrastructure.security.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.Objects;

/**
 * Escribe el registro de auditoría en la tabla {@code auditoria}.
 *
 * <h2>Por qué JDBC y no JPA</h2>
 * Es un insert y nada más: no hay agregado, no hay actualización, no hay
 * navegación. Una entidad JPA agregaría un contexto de persistencia que puede
 * retrasar el insert hasta el flush y un mapeo que nadie va a consultar desde
 * la aplicación —la auditoría se lee con SQL, desde afuera—. {@code
 * JdbcTemplate} participa de la transacción de Spring igual que el repositorio
 * JPA, que es la única propiedad que hacía falta conservar.
 *
 * <h2>Dos transacciones, según el resultado</h2>
 * No es un detalle de implementación: es el requisito.
 *
 * <ul>
 *   <li><b>{@code ALLOWED}</b> va en la transacción del caso de uso. El
 *       registro y el cambio que describe tienen que confirmarse juntos: si el
 *       caso de uso hace rollback, no hubo cambio que auditar, y una línea que
 *       afirme lo contrario es peor que ninguna.</li>
 *   <li><b>{@code DENIED}</b> va en una transacción propia. El rechazo
 *       <em>termina</em> en excepción —un pedido sobre una reserva ajena
 *       responde 404— y esa excepción hace rollback: con propagación normal, la
 *       evidencia del intento se iría junto con el intento. Que quede el
 *       registro es justamente lo único que queda de un pedido que no cambió
 *       nada.</li>
 * </ul>
 *
 * <h2>Lo que agrega el adaptador</h2>
 * El correlation id y la IP del cliente. La capa de aplicación no los conoce
 * —no sabe que existe HTTP— y son los dos datos que permiten ir de una línea
 * de auditoría a los logs del pedido que la generó.
 */
@Repository
public class JdbcAuditTrailAdapter implements AuditTrailPort {

    private static final String RESOURCE_TYPE = "RESERVATION";

    private static final String INSERT = """
            INSERT INTO auditoria
                (fecha, accion, resultado, actor, recurso_tipo, recurso_id,
                 recurso_version, correlation_id, client_ip)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    /** Transacción propia para los rechazos; ver el javadoc de la clase. */
    private final TransactionTemplate isolated;

    public JdbcAuditTrailAdapter(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.isolated = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void record(AuditEntry entry) {
        Objects.requireNonNull(entry, "La entrada de auditoría es obligatoria");

        if (entry.outcome() == AuditOutcome.DENIED) {
            isolated.executeWithoutResult(status -> insert(entry));
            return;
        }
        insert(entry);
    }

    private void insert(AuditEntry entry) {
        jdbcTemplate.update(INSERT,
                Timestamp.from(entry.occurredAt()),
                entry.action().name(),
                entry.outcome().name(),
                // El actor va completo y no enmascarado: esto es evidencia, no
                // un log de observabilidad. La tabla está en la base, con su
                // propio control de acceso, y no sale del perímetro.
                entry.actor().value(),
                RESOURCE_TYPE,
                entry.reservationId(),
                entry.resourceVersion().map(Long::intValue).orElse(null),
                mdc(CorrelationIdFilter.MDC_KEY, 64),
                mdc(CorrelationIdFilter.MDC_CLIENT_IP, 45));
    }

    /**
     * Valor del MDC, saneado y acotado al largo de la columna.
     *
     * <p>Puede faltar: el despachador del outbox y los tests llaman a los casos
     * de uso fuera de un pedido HTTP. Una auditoría sin correlation id sigue
     * probando quién hizo qué; una excepción acá abortaría la operación que
     * está auditando.
     */
    private static String mdc(String key, int maxLength) {
        String value = MDC.get(key);
        return value == null ? null : LogSanitizer.sanitize(value, maxLength);
    }
}
