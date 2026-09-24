package com.edteam.reservations.infrastructure.adapter.in.messaging;

import com.edteam.reservations.application.port.in.ProcessReservationEventUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * El backoff del consumidor: creciente y con jitter.
 *
 * <p>Era fijo y sin jitter, y eso tenía dos consecuencias que se sumaban.
 * Todos los mensajes que fallaban juntos volvían <em>juntos</em>, exactamente
 * 30 s después, contra un destino que seguía caído; y cinco vueltas
 * sincronizadas cubrían 150 s, así que la caída de un consumidor de más de dos
 * minutos y medio vaciaba la cola hacia la dead letter en bloque.
 */
@DisplayName("Backoff del consumidor")
class ReservationEventListenerBackoffTest {

    private static final Duration INITIAL = Duration.ofSeconds(30);
    private static final Duration MAX = Duration.ofMinutes(10);

    private final ReservationEventListener listener = new ReservationEventListener(
            mock(ProcessReservationEventUseCase.class),
            new InboundEnvelopeParser(new ObjectMapper()),
            mock(RabbitTemplate.class),
            5, INITIAL, MAX, new SimpleMeterRegistry());

    @Test
    @DisplayName("crece: la quinta vuelta espera mucho más que la primera")
    void theDelayGrows() {
        assertThat(listener.retryDelay(0)).isBetween(Duration.ofMillis(22_500), INITIAL);
        assertThat(listener.retryDelay(1)).isBetween(Duration.ofSeconds(45), Duration.ofSeconds(60));
        assertThat(listener.retryDelay(4))
                .as("cinco vueltas cubren minutos, no 150 segundos")
                .isGreaterThan(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("las cinco vueltas cubren mucho más que la caída típica de un consumidor")
    void theFiveRoundsCoverALongOutage() {
        Duration total = Duration.ZERO;
        for (int round = 0; round < 5; round++) {
            total = total.plus(listener.retryDelay(round));
        }

        assertThat(total)
                .as("antes eran 150 s en total: una caída de 2 min 30 s vaciaba la cola a la DLQ")
                .isGreaterThan(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("nunca supera el techo, que es la TTL de la cola de espera")
    void theDelayNeverExceedsTheQueueTtl() {
        for (int round = 0; round < 20; round++) {
            assertThat(listener.retryDelay(round))
                    .as("vuelta %d", round)
                    .isLessThanOrEqualTo(MAX);
        }
    }

    @Test
    @DisplayName("lleva jitter: veinte mensajes que fallaron juntos no vuelven juntos")
    void theDelayIsJittered() {
        Set<Long> sample = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            sample.add(listener.retryDelay(2).toMillis());
        }

        // Sin jitter esto daría un único valor repetido cien veces. No se pide
        // que las cien sean distintas —el sorteo puede repetir— sino que haya
        // dispersión de verdad.
        assertThat(sample).hasSizeGreaterThan(10);
    }

    @Test
    @DisplayName("el jitter se reparte sobre el último cuarto: acota el bloqueo de cabeza de cola")
    void theJitterIsBoundedToTheLastQuarter() {
        // Con una sola cola de espera, un mensaje con vencimiento largo en la
        // cabeza retrasa a los que tiene detrás. Sortear sobre el cuarto
        // superior acota ese retraso al 25 % del escalón, en lugar de dejarlo
        // crecer hasta el escalón entero.
        for (int i = 0; i < 200; i++) {
            assertThat(listener.retryDelay(1))
                    .isBetween(Duration.ofSeconds(45), Duration.ofSeconds(60));
        }
    }
}
