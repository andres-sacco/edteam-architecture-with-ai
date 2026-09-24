package com.edteam.reservations.infrastructure.adapter.out.airport.catalog;

import com.edteam.reservations.infrastructure.resilience.Circuit;

import java.util.Objects;
import java.util.Optional;

/**
 * El circuito del catálogo de ciudades.
 *
 * <p><strong>Va por fuera del retry</strong>, que es la decisión de orden que
 * más consecuencias tiene y la que este diseño justifica explícitamente:
 *
 * <ul>
 *   <li>Con el retry adentro, la unidad que el circuito cuenta es
 *       <em>resolver una ciudad</em>, con sus hasta dos intentos incluidos: un
 *       pedido desafortunado vale un voto. Con el retry afuera, cada intento
 *       HTTP sería un voto y un solo evento de red pesaría el doble o el
 *       triple según cuántos intentos tenga configurados ese día, con lo cual
 *       «50 % de fallo» dejaría de significar «la mitad de las resoluciones
 *       falla» para significar «la mitad de los paquetes falla».</li>
 *   <li>Y la razón decisiva: con el circuito adentro, un circuito
 *       <strong>abierto</strong> haría girar el bucle de reintentos sobre el
 *       rechazo del propio circuito, durmiendo backoff para nada. Con el
 *       circuito afuera, un circuito abierto cortocircuita el retry entero y
 *       la llamada cuesta microsegundos — que es exactamente lo que se está
 *       comprando.</li>
 * </ul>
 *
 * <p>El precio, dicho de frente: el circuito sólo ve los fallos que
 * sobrevivieron al retry, así que abre más tarde. Se compensa con un mínimo de
 * llamadas bajo (20, ≈ 3 itinerarios) y contando las llamadas lentas, que
 * aparecen antes que los fallos.
 *
 * <p><strong>Un {@code 404} no abre el circuito</strong>, y eso no es una
 * excepción escrita acá: el cliente HTTP devuelve «esa ciudad no existe» como
 * un {@code Optional} vacío, que para el circuito es una llamada exitosa. Lo
 * que no es una excepción no puede contar como fallo, y por eso una ráfaga de
 * códigos mal tipeados no puede frenar el tráfico sano.
 *
 * <p>El rechazo del circuito abierto sale como
 * {@code CallNotPermittedException} y lo traduce {@link CatalogCityResolver},
 * que es la clase inmediatamente superior y la última de infraestructura antes
 * del fallback: la librería no llega ni al puerto ni a la aplicación.
 */
public class CircuitBreakingCityCatalogClient implements CityCatalogClient {

    private final CityCatalogClient delegate;
    private final Circuit circuit;

    public CircuitBreakingCityCatalogClient(CityCatalogClient delegate, Circuit circuit) {
        this.delegate = Objects.requireNonNull(delegate, "El delegado es obligatorio");
        this.circuit = Objects.requireNonNull(circuit, "El circuito es obligatorio");
    }

    @Override
    public Optional<CatalogCity> findByCode(String code) {
        return circuit.execute(() -> delegate.findByCode(code));
    }
}
