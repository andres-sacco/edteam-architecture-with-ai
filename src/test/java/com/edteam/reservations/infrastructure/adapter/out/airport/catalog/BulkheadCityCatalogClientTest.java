package com.edteam.reservations.infrastructure.adapter.out.airport.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La cota de llamadas en vuelo contra el proveedor.
 *
 * <p>Los threads virtuales quitaron el <em>backpressure</em> que daba un pool
 * acotado y no lo reemplazó nada: quinientos {@code POST} concurrentes contra
 * un catálogo lento eran hasta quinientos sockets contra un proveedor con una
 * decena de conexiones a su base. Nuestro timeout se convertía en su
 * saturación y nuestro reintento en su caída.
 */
@DisplayName("Bulkhead del catálogo")
class BulkheadCityCatalogClientTest {

    private static final int PERMITS = 5;

    @Test
    @DisplayName("nunca hay más llamadas en vuelo que permisos")
    void neverExceedsTheConfiguredConcurrency() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(PERMITS);

        CityCatalogClient client = new BulkheadCityCatalogClient(
                code -> {
                    int current = inFlight.incrementAndGet();
                    peak.accumulateAndGet(current, Math::max);
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        inFlight.decrementAndGet();
                    }
                    return Optional.of(new CatalogCity(code, code));
                },
                bulkhead());

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 50; i++) {
                pool.submit(() -> {
                    try {
                        client.findByCode("BUE");
                    } catch (BulkheadFullException e) {
                        rejected.incrementAndGet();
                    }
                });
            }
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
        }

        assertThat(peak)
                .as("cincuenta pedidos concurrentes y el proveedor vio como mucho %d a la vez", PERMITS)
                .hasValueLessThanOrEqualTo(PERMITS);
        assertThat(rejected)
                .as("el resto se rechaza en el acto: la cola es nuestra y visible")
                .hasPositiveValue();
    }

    @Test
    @DisplayName("la espera es cero: sin permiso, el rechazo es inmediato")
    void rejectsImmediatelyWithoutWaiting() throws Exception {
        Bulkhead full = bulkhead();
        for (int i = 0; i < PERMITS; i++) {
            assertThat(full.tryAcquirePermission()).isTrue();
        }

        CityCatalogClient client =
                new BulkheadCityCatalogClient(code -> Optional.of(new CatalogCity(code, code)), full);

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> client.findByCode("BUE")).isInstanceOf(BulkheadFullException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed)
                .as("una cola de espera acá sería la misma cola que el bulkhead quiere evitar")
                .isLessThan(Duration.ofMillis(200));
    }

    @Test
    @DisplayName("con permisos libres no se interpone: la llamada pasa igual")
    void doesNotInterfereWhenThereIsRoom() {
        CityCatalogClient client =
                new BulkheadCityCatalogClient(code -> Optional.of(new CatalogCity(code, "Buenos Aires")), bulkhead());

        assertThat(client.findByCode("BUE")).map(CatalogCity::name).contains("Buenos Aires");
    }

    private static Bulkhead bulkhead() {
        return Bulkhead.of(
                "catalog-test-" + System.nanoTime(),
                BulkheadConfig.custom()
                        .maxConcurrentCalls(PERMITS)
                        .maxWaitDuration(Duration.ZERO)
                        .build());
    }
}
