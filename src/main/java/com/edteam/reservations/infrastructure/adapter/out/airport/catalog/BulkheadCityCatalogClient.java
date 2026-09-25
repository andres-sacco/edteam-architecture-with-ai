package com.edteam.reservations.infrastructure.adapter.out.airport.catalog;

import io.github.resilience4j.bulkhead.Bulkhead;
import java.util.Objects;
import java.util.Optional;

/**
 * Cota de llamadas simultáneas contra {@code api-catalog}.
 *
 * <p>Es el reemplazo explícito del <em>backpressure</em> que los threads
 * virtuales quitaron. Con un pool de threads de plataforma, el tamaño del pool
 * era un límite implícito de llamadas en vuelo; con hilos virtuales ese límite
 * desapareció y no lo reemplazó nada. Quinientos {@code POST} concurrentes
 * contra un catálogo lento son hasta quinientos sockets contra un proveedor
 * con una decena de conexiones a su base: nuestro timeout se convierte en su
 * saturación, y nuestro reintento en su caída. Con el bulkhead la cola es
 * <strong>nuestra y visible</strong> en lugar de suya.
 *
 * <p><strong>Va entre el circuito y el retry</strong>, no en el lugar más
 * interno que es donde lo pondría el orden por defecto. Si estuviera adentro
 * del retry, un rechazo por bulkhead lleno se reintentaría, que es exactamente
 * al revés de lo que hay que hacer cuando ya hay demasiadas llamadas propias
 * en vuelo; y una ráfaga podría pasar el bulkhead en el primer intento y
 * volver a tomarlo en el segundo. Arriba del retry, el permiso representa
 * «este pedido está ocupando al proveedor» y se sostiene durante toda la
 * secuencia reintentada. El precio es que el permiso queda tomado durante el
 * backoff; con un techo de 200 ms y 50 permisos, es aceptable.
 *
 * <p>La espera es <strong>cero</strong>: si no hay permiso, se rechaza en el
 * acto con {@code BulkheadFullException}, que el clasificador cuenta para el
 * circuito y no reintenta. Una cola de espera acá sería la misma cola que el
 * bulkhead quiere evitar, sólo que del lado nuestro y sin cota visible.
 */
public class BulkheadCityCatalogClient implements CityCatalogClient {

    private final CityCatalogClient delegate;
    private final Bulkhead bulkhead;

    public BulkheadCityCatalogClient(CityCatalogClient delegate, Bulkhead bulkhead) {
        this.delegate = Objects.requireNonNull(delegate, "El delegado es obligatorio");
        this.bulkhead = Objects.requireNonNull(bulkhead, "El bulkhead es obligatorio");
    }

    @Override
    public Optional<CatalogCity> findByCode(String code) {
        return bulkhead.executeSupplier(() -> delegate.findByCode(code));
    }
}
