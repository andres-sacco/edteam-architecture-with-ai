package com.edteam.reservations.infrastructure.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.ThreadLocalAccessor;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Los tres saltos de correlación que la auditoría encontró rotos, uno por test.
 */
@DisplayName("Propagación del correlation id")
class MdcPropagationTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("el accessor del MDC se registra solo por ServiceLoader")
    void theMdcAccessorIsDiscoveredAutomatically() {
        // Si esto falla, el ContextSnapshot del fan-out propaga el contexto de
        // traza y NO el correlationId: la mitad del arreglo, y justo la mitad
        // que existe también cuando las trazas están apagadas.
        assertThat(ContextRegistry.getInstance().getThreadLocalAccessors())
                .extracting(ThreadLocalAccessor::key)
                .contains(MdcThreadLocalAccessor.KEY);
    }

    @Test
    @DisplayName("un hilo virtual del fan-out hereda el correlationId del hilo llamador")
    void virtualThreadsInheritTheMdc() throws Exception {
        // El hallazgo 11: `CompletableFuture.supplyAsync(..., workers)` no
        // lleva nada, y las 14 líneas que el catálogo escribe por un POST
        // degradado salían sin id. Son justamente las que uno va a buscar
        // cuando un POST sale degradado.
        MDC.put(LogFields.CORRELATION_ID, "audit-0000-0001");
        ContextSnapshot snapshot = ContextSnapshotFactory.builder().build().captureAll();

        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            String seenInsideTheTask = CompletableFuture.supplyAsync(
                            () -> {
                                try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                                    return MDC.get(LogFields.CORRELATION_ID);
                                }
                            },
                            workers)
                    .get();

            assertThat(seenInsideTheTask).isEqualTo("audit-0000-0001");
        }
    }

    @Test
    @DisplayName("el hilo virtual queda limpio al terminar: el ejecutor los reusa")
    void theVirtualThreadIsLeftClean() throws Exception {
        MDC.put(LogFields.CORRELATION_ID, "audit-0000-0001");
        ContextSnapshot snapshot = ContextSnapshotFactory.builder().build().captureAll();

        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            String afterTheScope = CompletableFuture.supplyAsync(
                            () -> {
                                try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                                    assertThat(MDC.get(LogFields.CORRELATION_ID))
                                            .isEqualTo("audit-0000-0001");
                                }
                                // Fuera del scope, nada: un id que sobrevive al pedido le miente
                                // al siguiente que tome ese hilo.
                                return MDC.get(LogFields.CORRELATION_ID);
                            },
                            workers)
                    .get();

            assertThat(afterTheScope).isNull();
        }
    }

    @Test
    @DisplayName("una tarea programada nace con un id sintético que la nombra")
    void scheduledTasksGetASyntheticId() {
        MdcTaskDecorator decorator = new MdcTaskDecorator();
        StringBuilder seen = new StringBuilder();

        decorator
                .decorate(() -> {
                    MdcTaskDecorator.adopt("outbox-relay");
                    seen.append(MDC.get(LogFields.CORRELATION_ID));
                })
                .run();

        assertThat(seen.toString())
                .startsWith("job-outbox-relay-")
                // Mismo formato que CorrelationIdFilter acepta del cliente: el
                // campo tiene que significar lo mismo venga de donde venga.
                .matches("[A-Za-z0-9_-]{8,64}");
    }

    @Test
    @DisplayName("el decorador RESTITUYE el MDC anterior en lugar de borrarlo")
    void theDecoratorRestoresInsteadOfRemoving() {
        // El hallazgo 10, en su forma general: con `remove`, después de que una
        // tarea pisa el correlationId, el resto de la corrida sale SIN ninguno.
        MDC.put(LogFields.CORRELATION_ID, "id-de-la-corrida");
        MDC.put("otro", "valor");

        new MdcTaskDecorator()
                .decorate(() -> MdcTaskDecorator.adopt("messaging-purge"))
                .run();

        assertThat(MDC.get(LogFields.CORRELATION_ID)).isEqualTo("id-de-la-corrida");
        assertThat(MDC.get("otro")).isEqualTo("valor");
        assertThat(MDC.get(LogFields.JOB)).isNull();
    }

    @Test
    @DisplayName("restaurar un MDC vacío lo deja vacío y no explota")
    void restoringAnEmptyMdcIsSafe() {
        // `MDC.setContextMap(null)` tira IllegalArgumentException, y el caso
        // «no había nada» es el normal en el hilo del scheduler.
        MDC.clear();
        new MdcTaskDecorator()
                .decorate(() -> MdcTaskDecorator.adopt("outbox-relay"))
                .run();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    @DisplayName("el accessor reemplaza el MDC del hilo destino en lugar de mezclarlo")
    void theAccessorReplacesRatherThanMerges() {
        // Un merge dejaría el correlationId de un pedido pegado al siguiente
        // que tome ese hilo, que es el bug que CorrelationIdFilter evita con su
        // finally.
        MdcThreadLocalAccessor accessor = new MdcThreadLocalAccessor();
        MDC.put("viejo", "del hilo anterior");

        accessor.setValue(Map.of(LogFields.CORRELATION_ID, "audit-0000-0001"));

        assertThat(MDC.get(LogFields.CORRELATION_ID)).isEqualTo("audit-0000-0001");
        assertThat(MDC.get("viejo")).isNull();
    }
}
