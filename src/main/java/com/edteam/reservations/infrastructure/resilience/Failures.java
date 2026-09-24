package com.edteam.reservations.infrastructure.resilience;

import com.edteam.reservations.application.exception.AirportCatalogIntegrationException;
import com.edteam.reservations.application.exception.AirportCatalogThrottledException;
import com.edteam.reservations.application.exception.AirportCatalogUnavailableException;
import com.edteam.reservations.application.exception.EventPublishException;
import com.edteam.reservations.application.exception.EventPublisherUnavailableException;
import com.edteam.reservations.application.exception.EventRoutingException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import java.util.function.Predicate;

/**
 * El <strong>único</strong> lugar donde se decide si una excepción es
 * transitoria, permanente o un rechazo propio.
 *
 * <p>Existe porque antes de este paso la misma decisión estaba escrita dos
 * veces: el {@code catch (AirportCatalogUnavailableException)} del retry y la
 * clasificación de la respuesta en {@code RestCityCatalogClient}. Dos copias
 * de una regla que tiene que valer para los dos es una copia que se va a
 * desincronizar, y el síntoma —un circuito que cuenta lo que el retry ignora—
 * es de los que no se ven hasta el incidente.
 *
 * <p>Ahora hay un método por dependencia, cada uno con la tabla del diseño §4
 * traducida a código, y el circuito y el retry consumen los dos el mismo
 * resultado: el circuito por {@link #countsFor(java.util.function.Function)}
 * como predicado de {@code recordException}, el retry preguntando
 * {@link FailureClassification#retryable()}.
 *
 * <p>Vive en infraestructura, como toda la resiliencia: conoce las excepciones
 * de la aplicación —que son parte del contrato de los puertos— y las de la
 * librería, pero nadie de adentro la conoce a ella.
 */
public final class Failures {

    private Failures() {
    }

    // -----------------------------------------------------------------
    // api-catalog
    // -----------------------------------------------------------------

    /**
     * Catálogo de ciudades. El {@code 404} y el {@code 200} sin cuerpo no
     * aparecen acá: no son excepciones, son respuestas de negocio («esa ciudad
     * no existe») y el proveedor está sano. Contarlas abriría el circuito ante
     * una ráfaga de códigos mal tipeados, justo cuando todo funciona.
     */
    public static FailureClassification catalog(Throwable error) {
        if (error instanceof CallNotPermittedException) {
            // El circuito no cuenta sus propios rechazos.
            return FailureClassification.SHED;
        }
        if (error instanceof BulkheadFullException) {
            // «Ya hay demasiadas llamadas nuestras en vuelo contra el
            // proveedor»: la saturación propia es síntoma de la lentitud
            // ajena, así que cuenta. Reintentar es empujar una puerta cerrada.
            return FailureClassification.TRANSIENT_NOT_RETRYABLE;
        }
        if (error instanceof AirportCatalogThrottledException) {
            return FailureClassification.TRANSIENT_NOT_RETRYABLE;
        }
        if (error instanceof AirportCatalogUnavailableException) {
            // 5xx, read timeout, connect timeout, conexión rechazada.
            return FailureClassification.TRANSIENT_RETRYABLE;
        }
        if (error instanceof AirportCatalogIntegrationException) {
            // 401/403, credencial vencida, cuerpo ilegible o sin 'code'.
            return FailureClassification.PERMANENT;
        }
        // IllegalArgumentException y cualquier otra: es un bug nuestro. Un
        // circuito que se abre por un bug propio oculta el bug.
        return FailureClassification.PERMANENT;
    }

    // -----------------------------------------------------------------
    // Redis
    // -----------------------------------------------------------------

    /**
     * Cache distribuida. Cualquier {@code RuntimeException} de Lettuce
     * —timeout de comando, fallo de conexión— es transitoria y cuenta: el
     * circuito de Redis existe sólo para dejar de pagar los 200 ms por
     * operación cuando ya sabemos que no está.
     *
     * <p>Nunca es reintentable: el «reintento» de un cache es ir al origen,
     * que es lo que se hace igual en un miss.
     */
    public static FailureClassification cache(Throwable error) {
        if (error instanceof CallNotPermittedException) {
            return FailureClassification.SHED;
        }
        if (error instanceof IllegalArgumentException || error instanceof NullPointerException) {
            // Clave nula, valor nulo: nuestro.
            return FailureClassification.PERMANENT;
        }
        return FailureClassification.TRANSIENT_NOT_RETRYABLE;
    }

    // -----------------------------------------------------------------
    // RabbitMQ
    // -----------------------------------------------------------------

    /**
     * Publicación hacia el broker. Ningún fallo es reintentable en proceso: el
     * outbox <em>es</em> el reintento, durable y con su propia política, y
     * duplicarla adentro del publicador retendría el hilo del relay.
     */
    public static FailureClassification broker(Throwable error) {
        if (error instanceof CallNotPermittedException
                || error instanceof EventPublisherUnavailableException) {
            return FailureClassification.SHED;
        }
        if (error instanceof EventRoutingException) {
            // Mensaje devuelto: falta un binding. Es topología nuestra, y
            // abrir el circuito por una routing key huérfana frenaría la
            // entrega de todos los demás eventos, que salían bien.
            return FailureClassification.PERMANENT;
        }
        if (error instanceof EventPublishException) {
            // nack, confirm que no llega, AmqpException: del lado del broker.
            return FailureClassification.TRANSIENT_NOT_RETRYABLE;
        }
        // IllegalStateException del serializador y cualquier otra: permanente
        // y propia. Va a la dead letter del productor en el primer intento.
        return FailureClassification.PERMANENT;
    }

    // -----------------------------------------------------------------
    // Uso desde la configuración del circuito
    // -----------------------------------------------------------------

    /**
     * Convierte un clasificador en el predicado {@code recordException} de un
     * {@code CircuitBreakerConfig}. Es el punto donde la tabla del diseño se
     * enchufa a la librería, y el que garantiza que el circuito no pueda
     * contar algo distinto de lo que el retry reintenta.
     */
    public static Predicate<Throwable> countsFor(java.util.function.Function<Throwable, FailureClassification> classifier) {
        return error -> classifier.apply(error).countsForCircuit();
    }
}
