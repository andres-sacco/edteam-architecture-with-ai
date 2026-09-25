package com.edteam.reservations.infrastructure.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.edteam.reservations.application.exception.AirportCatalogIntegrationException;
import com.edteam.reservations.application.exception.AirportCatalogThrottledException;
import com.edteam.reservations.application.exception.AirportCatalogUnavailableException;
import com.edteam.reservations.application.exception.EventPublishException;
import com.edteam.reservations.application.exception.EventPublisherUnavailableException;
import com.edteam.reservations.application.exception.EventRoutingException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * La tabla de clasificación de fallos del diseño, convertida en aserciones.
 *
 * <p>Este test existe porque la clasificación estaba <strong>duplicada</strong>:
 * el {@code catch} por tipo del retry y la clasificación de la respuesta del
 * cliente HTTP decían cada uno lo suyo, y el día que se desincronizaran el
 * síntoma —un circuito que cuenta lo que el retry ignora— no se vería hasta el
 * incidente. Ahora hay un solo lugar y este test es su contrato.
 */
@DisplayName("Clasificación de fallos")
class FailuresTest {

    @Nested
    @DisplayName("api-catalog")
    class Catalog {

        @Test
        @DisplayName("5xx, timeout y conexión rechazada: cuentan y se reintentan")
        void transientFailuresCountAndRetry() {
            FailureClassification classification = Failures.catalog(new AirportCatalogUnavailableException("503"));

            assertThat(classification.countsForCircuit()).isTrue();
            assertThat(classification.retryable()).isTrue();
        }

        @Test
        @DisplayName("429: cuenta pero NO se reintenta")
        void throttlingCountsButDoesNotRetry() {
            // Los dos ejes no coinciden, y ésta es la fila que lo demuestra:
            // el proveedor está pidiendo explícitamente que bajemos el ritmo,
            // así que reintentar lo empeora; y contarlo es lo que hace que el
            // circuito abra y pare el tráfico de verdad.
            FailureClassification classification = Failures.catalog(new AirportCatalogThrottledException("429"));

            assertThat(classification.countsForCircuit()).isTrue();
            assertThat(classification.retryable()).isFalse();
        }

        @Test
        @DisplayName("401, credencial vencida, cuerpo fuera de contrato: ni cuenta ni se reintenta")
        void permanentFailuresNeitherCountNorRetry() {
            // Un circuito abierto ESCONDERÍA este fallo detrás de un 503
            // genérico y retrasaría el diagnóstico del único caso que ningún
            // mecanismo automático resuelve.
            FailureClassification classification = Failures.catalog(new AirportCatalogIntegrationException("401"));

            assertThat(classification.kind()).isEqualTo(FailureKind.PERMANENT);
            assertThat(classification.countsForCircuit()).isFalse();
            assertThat(classification.retryable()).isFalse();
        }

        @Test
        @DisplayName("el rechazo del propio circuito no cuenta: no se mide a sí mismo")
        void theCircuitDoesNotCountItsOwnRejections() {
            FailureClassification classification = Failures.catalog(
                    CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("x")));

            assertThat(classification.kind()).isEqualTo(FailureKind.SHED);
            assertThat(classification.retryable()).isFalse();
        }

        @Test
        @DisplayName("bulkhead lleno: cuenta —la saturación propia es síntoma de la lentitud ajena— y no se reintenta")
        void bulkheadFullCountsAndDoesNotRetry() {
            FailureClassification classification = Failures.catalog(
                    BulkheadFullException.createBulkheadFullException(Bulkhead.of("x", BulkheadConfig.ofDefaults())));

            assertThat(classification.countsForCircuit()).isTrue();
            assertThat(classification.retryable()).isFalse();
        }

        @Test
        @DisplayName("un bug nuestro no abre el circuito: lo ocultaría")
        void ourOwnBugsNeverOpenTheCircuit() {
            assertThat(Failures.catalog(new IllegalArgumentException("código vacío"))
                            .countsForCircuit())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Redis")
    class Cache {

        @Test
        @DisplayName("cualquier error de Lettuce cuenta, y nada se reintenta")
        void everyLettuceFailureCounts() {
            FailureClassification classification = Failures.cache(new RedisConnectionFailureException("caído"));

            assertThat(classification.countsForCircuit()).isTrue();
            // El "reintento" de un cache es ir al origen, que es lo que se
            // hace igual en un miss.
            assertThat(classification.retryable()).isFalse();
        }

        @Test
        @DisplayName("una clave nula es nuestra, no de Redis")
        void ourOwnMistakesArePermanent() {
            assertThat(Failures.cache(new IllegalArgumentException("clave nula"))
                            .countsForCircuit())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("RabbitMQ")
    class Broker {

        @Test
        @DisplayName("nack, confirm que no llega y AmqpException cuentan")
        void brokerProblemsCount() {
            assertThat(Failures.broker(new EventPublishException("no confirmó")).countsForCircuit())
                    .isTrue();
        }

        @Test
        @DisplayName("un mensaje devuelto por falta de binding NO cuenta")
        void aReturnedMessageNeverOpensTheCircuit() {
            // Es un error de topología nuestro. Abrir el circuito por una
            // routing key huérfana frenaría la entrega de TODOS los demás
            // eventos, que estaban saliendo bien: el circuito introduciría el
            // modo de falla que venía a evitar.
            assertThat(Failures.broker(new EventRoutingException("sin binding")).countsForCircuit())
                    .isFalse();
        }

        @Test
        @DisplayName("el rechazo del propio circuito no cuenta")
        void theCircuitDoesNotCountItsOwnRejections() {
            assertThat(Failures.broker(new EventPublisherUnavailableException("circuito abierto"))
                            .kind())
                    .isEqualTo(FailureKind.SHED);
        }

        @Test
        @DisplayName("un payload que no serializa es permanente: va a la dead letter en el primer intento")
        void aSerializationFailureIsPermanent() {
            assertThat(Failures.broker(new IllegalStateException("payload inválido"))
                            .kind())
                    .isEqualTo(FailureKind.PERMANENT);
        }

        @Test
        @DisplayName("nada se reintenta en proceso: el outbox ES el reintento")
        void nothingIsRetriedInProcess() {
            assertThat(Failures.broker(new EventPublishException("no confirmó")).retryable())
                    .isFalse();
            assertThat(Failures.broker(new EventRoutingException("sin binding")).retryable())
                    .isFalse();
        }
    }
}
