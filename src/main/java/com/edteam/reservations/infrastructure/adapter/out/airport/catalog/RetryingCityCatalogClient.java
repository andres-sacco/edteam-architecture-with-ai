package com.edteam.reservations.infrastructure.adapter.out.airport.catalog;

import com.edteam.reservations.application.exception.AirportCatalogIntegrationException;
import com.edteam.reservations.application.exception.AirportCatalogUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decorador que reintenta las consultas al catálogo que fallaron por algo
 * pasajero, con espera exponencial y jitter.
 *
 * <h2>Por qué se puede reintentar esto</h2>
 * {@code GET /city/{code}} es una lectura: es segura e idempotente, así que
 * repetirla no puede duplicar nada ni dejar el catálogo en otro estado. Eso es
 * lo que la separa de las operaciones de escritura del sistema, que siguen sin
 * reintentarse: el alta de una reserva no es idempotente por sí misma —lo es
 * sólo gracias a la {@code Idempotency-Key}— y el {@code PUT} depende de una
 * versión que un reintento ciego pisaría.
 *
 * <h2>Qué se reintenta y qué no</h2>
 * La clasificación ya la hace {@link RestCityCatalogClient}, y este decorador
 * se apoya en ella sin volver a mirar códigos de estado:
 *
 * <ul>
 *   <li>{@link AirportCatalogUnavailableException} —5xx, 429, timeout, error
 *       de conexión— <b>sí</b>: el pedido no tiene nada de malo, el problema
 *       es del otro lado o de la red, y por definición puede haberse
 *       resuelto.</li>
 *   <li>{@link AirportCatalogIntegrationException} —4xx que no es 404,
 *       credencial vencida, cuerpo que no cumple el contrato— <b>no</b>:
 *       repetir el mismo pedido da el mismo resultado. Insistir sólo agrega
 *       latencia al pedido del usuario y carga al proveedor.</li>
 *   <li>Un {@code Optional} vacío —la ciudad no existe— <b>tampoco</b>: es una
 *       respuesta, no un fallo.</li>
 * </ul>
 *
 * <h2>Por qué exponencial y con jitter</h2>
 * Un reintento inmediato llega casi siempre mientras el proveedor sigue
 * caído, así que gasta un intento sin ganar nada. Y el jitter no es un
 * detalle: cuando el catálogo devuelve 429 es porque lo estamos saturando, y
 * con esperas fijas todas las instancias reintentan en el mismo instante y
 * vuelven a saturarlo —el efecto manada que convierte una degradación en una
 * caída—. La espera se sortea entre la mitad y el total del backoff
 * calculado, que alcanza para desincronizarlas.
 *
 * <h2>El costo, dicho de frente</h2>
 * Los reintentos acotan la probabilidad de fallar, no el tiempo del pedido:
 * lo agrandan. Con los valores por defecto —2 s de read timeout, 3 intentos,
 * backoff de 100 ms a 500 ms— el peor caso de <em>una</em> ciudad es del
 * orden de 6,5 s, y un itinerario de ida y vuelta con escala son 8 ciudades
 * consultadas en serie. Dos cosas lo hacen aceptable, y las dos hay que
 * mantener:
 *
 * <ol>
 *   <li>{@code CachingAirportCatalog} está <em>encima</em> de este decorador,
 *       así que el caso malo sólo lo pagan las consultas que no están en
 *       cache;</li>
 *   <li>el {@code stale-while-error} de esa misma caché responde con el último
 *       valor conocido cuando los reintentos se agotan, de modo que un
 *       catálogo caído no rechaza reservas.</li>
 * </ol>
 *
 * <p>Lo que <b>no</b> hay todavía es un presupuesto de tiempo para el pedido
 * completo: cada ciudad tiene su propio techo, y el itinerario entero no. Es
 * la mejora que sigue si el peor caso llega a verse en producción.
 */
public class RetryingCityCatalogClient implements CityCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(RetryingCityCatalogClient.class);

    private final CityCatalogClient delegate;
    private final Retry retry;
    private final Sleeper sleeper;

    public RetryingCityCatalogClient(CityCatalogClient delegate, Retry retry, Sleeper sleeper) {
        this.delegate = Objects.requireNonNull(delegate, "El delegado es obligatorio");
        this.retry = Objects.requireNonNull(retry, "La política de reintentos es obligatoria");
        this.sleeper = Objects.requireNonNull(sleeper, "El sleeper es obligatorio");
    }

    /** Con el {@link Sleeper} real, que es lo que se usa en producción. */
    public RetryingCityCatalogClient(CityCatalogClient delegate, Retry retry) {
        this(delegate, retry, Sleeper.real());
    }

    @Override
    public Optional<CatalogCity> findByCode(String code) {
        AirportCatalogUnavailableException last = null;

        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            try {
                return delegate.findByCode(code);
            } catch (AirportCatalogUnavailableException e) {
                last = e;
                if (attempt == retry.maxAttempts()) {
                    break;
                }
                Duration wait = retry.backoffFor(attempt);
                log.warn("El catálogo no respondió por {} (intento {}/{}): se reintenta en {} ms. Causa: {}",
                        code, attempt, retry.maxAttempts(), wait.toMillis(), e.getMessage());
                if (!sleeper.sleep(wait)) {
                    // Interrumpieron el hilo: se corta acá en lugar de seguir
                    // ocupando a un pedido que ya nadie está esperando.
                    log.warn("Reintento de {} interrumpido: se devuelve el último fallo", code);
                    break;
                }
            }
        }

        log.warn("El catálogo no respondió por {} después de {} intentos", code, retry.maxAttempts());
        throw new AirportCatalogUnavailableException(
                "El catálogo no respondió por '%s' después de %d intentos".formatted(code, retry.maxAttempts()),
                last);
    }

    /**
     * Política de reintentos.
     *
     * @param maxAttempts    intentos totales, el primero incluido. 1 significa
     *                       "no reintentar"
     * @param initialBackoff espera después del primer fallo
     * @param maxBackoff     techo de la espera; sin él, la progresión
     *                       exponencial se vuelve absurda a partir del cuarto
     *                       o quinto intento
     */
    public record Retry(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {

        /** Cada intento espera el doble que el anterior, hasta el techo. */
        private static final int MULTIPLIER = 2;

        public Retry {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("Tiene que haber al menos un intento");
            }
            requirePositive(initialBackoff, "El backoff inicial");
            requirePositive(maxBackoff, "El backoff máximo");
            if (maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException("El backoff máximo no puede ser menor que el inicial");
            }
        }

        /** Sin reintentos: un solo intento. Es el comportamiento anterior. */
        public static Retry disabled() {
            return new Retry(1, Duration.ofMillis(1), Duration.ofMillis(1));
        }

        /**
         * Espera antes del intento siguiente al número indicado, con jitter.
         *
         * <p>El sorteo es sobre la mitad superior del backoff calculado: no
         * tanto como para que dos instancias esperen tiempos muy distintos,
         * suficiente para que no reintenten todas en el mismo milisegundo.
         */
        Duration backoffFor(int attempt) {
            long millis = initialBackoff.toMillis();
            for (int i = 1; i < attempt && millis < maxBackoff.toMillis(); i++) {
                millis *= MULTIPLIER;
            }
            long capped = Math.min(millis, maxBackoff.toMillis());
            long half = Math.max(1L, capped / 2);
            return Duration.ofMillis(half + ThreadLocalRandom.current().nextLong(half + 1));
        }

        private static void requirePositive(Duration value, String what) {
            Objects.requireNonNull(value, what + " es obligatorio");
            if (value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(what + " debe ser positivo");
            }
        }
    }

    /**
     * La espera entre reintentos, inyectable.
     *
     * <p>Existe para que los tests verifiquen la política sin dormir de
     * verdad: un test que espera backoff real es un test lento, y uno que
     * baja el backoff a cero no prueba la política que corre en producción.
     */
    @FunctionalInterface
    public interface Sleeper {

        /** @return {@code false} si interrumpieron el hilo mientras esperaba */
        boolean sleep(Duration duration);

        /**
         * El real. Con threads virtuales —que es como corre esta aplicación—
         * un {@code sleep} no bloquea un hilo de sistema operativo: libera el
         * carrier, así que esperar acá no le saca capacidad a los demás
         * pedidos.
         */
        static Sleeper real() {
            return duration -> {
                try {
                    Thread.sleep(duration);
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            };
        }
    }
}
