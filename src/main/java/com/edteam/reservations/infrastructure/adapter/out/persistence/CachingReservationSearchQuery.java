package com.edteam.reservations.infrastructure.adapter.out.persistence;

import com.edteam.reservations.application.query.ReservationSearchCriteria;
import com.edteam.reservations.domain.model.Email;
import com.edteam.reservations.domain.model.ReservationStatus;
import com.edteam.reservations.infrastructure.cache.CacheKeys;
import com.edteam.reservations.infrastructure.cache.CacheStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Decorador que cachea <strong>sólo el conteo</strong> del listado.
 * <strong>Es la entrada P1 del análisis de cuellos de botella.</strong>
 *
 * <p>{@code GET /v1/reservations} es el endpoint más caro del sistema: tres
 * consultas por pedido, dos de ellas con el join
 * {@code reserva → itinerario → itinerario_segmento → segmento} filtrando
 * {@code orden = 0}. Sin filtros, el conteo recorre la tabla entera. Y es el
 * endpoint que los frontends llaman en cada carga de pantalla, con los mismos
 * filtros por defecto una y otra vez.
 *
 * <p>Bajo concurrencia el ahorro que importa no es la latencia de la consulta
 * sino la conexión: con {@code maximum-pool-size: 20} y threads virtuales, el
 * recurso escaso es el pool, y una consulta que no se hace es una conexión que
 * queda libre para otro pedido.
 *
 * <h2>Qué se cachea y qué no</h2>
 * <ul>
 *   <li><b>Sí:</b> el escalar {@code count(criteria)}. Es la mitad cara del
 *       pedido, es un {@code long} y no contiene ningún dato personal.</li>
 *   <li><b>No:</b> los ids ni los cuerpos de la página. Dos razones
 *       independientes, cada una suficiente: la cardinalidad explota
 *       —filtros × página × tamaño × orden— contra una memoria acotada, y la
 *       representación lleva {@code documentNumber} y {@code birthDate} de
 *       hasta 100 pasajeros por entrada.</li>
 * </ul>
 *
 * <h2>La clave ignora la paginación</h2>
 * El total no depende de {@code page}, {@code size} ni del orden, así que las
 * cinco páginas que recorre un usuario comparten una sola entrada. Es lo que
 * hace que la tasa de aciertos sea alta con pocas entradas vivas: ~100 B por
 * combinación de filtros, del orden de cientos de KB.
 *
 * <h2>Invalidación: ninguna, sólo TTL</h2>
 * Invalidar por escritura exigiría saber qué filtros matchea una reserva
 * nueva, lo que es imposible sin recorrer las claves. Y no hace falta: el
 * total de una paginación es una pista para la interfaz, no un invariante de
 * negocio. Que diga 1.204 cuando son 1.205 durante medio minuto no rompe nada.
 * Esa es toda la justificación del TTL corto, y es la razón por la que este
 * cache es seguro sin invalidación activa.
 *
 * <h2>El cero no se cachea</h2>
 * {@link ReservationPersistenceAdapter#search} corta sin pedir la página
 * cuando el total es cero. Servir un cero viejo escondería reservas reales
 * —típicamente, las que el usuario acaba de crear— durante toda la ventana del
 * TTL, y eso ya no es "una pista desactualizada" sino una página vacía
 * incorrecta. Un conteo que da cero es además el caso barato: no hay filas que
 * agregar.
 *
 * <h2>Lo que este cache no arregla</h2>
 * Faltan índices sobre {@code reserva(fecha_creacion DESC, id DESC)} —el orden
 * por defecto— y sobre {@code segmento(fecha_vuelo)}. Con {@code OFFSET}
 * creciente el listado se degrada igual, porque cada página es una consulta
 * distinta y ninguna la resuelve este decorador. El cache tapa parte del
 * problema; los índices lo arreglan.
 */
public class CachingReservationSearchQuery implements ReservationSearchQuery {

    private static final Logger log = LoggerFactory.getLogger(CachingReservationSearchQuery.class);

    private final ReservationSearchQuery delegate;
    private final CacheStore cache;
    private final Duration ttl;

    public CachingReservationSearchQuery(ReservationSearchQuery delegate, CacheStore cache, Duration ttl) {
        this.delegate = Objects.requireNonNull(delegate, "El delegado es obligatorio");
        this.cache = Objects.requireNonNull(cache, "El almacén de cache es obligatorio");
        this.ttl = Objects.requireNonNull(ttl, "El TTL es obligatorio");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("El TTL debe ser positivo");
        }
    }

    @Override
    public long count(ReservationSearchCriteria criteria) {
        String key = keyOf(criteria);

        Long cached = cache.get(key).map(CachingReservationSearchQuery::parse).orElse(null);
        if (cached != null) {
            return cached;
        }

        long total = delegate.count(criteria);
        if (total > 0) {
            cache.put(key, Long.toString(total), ttl);
        }
        log.trace("Total del listado resuelto contra la base: {}", total);
        return total;
    }

    /**
     * Va siempre a la base. Cachear la página es exactamente lo que la
     * restricción de datos sensibles y la cota de memoria descartan.
     */
    @Override
    public List<Long> findPageOfIds(ReservationSearchCriteria criteria) {
        return delegate.findPageOfIds(criteria);
    }

    /**
     * Clave a partir de los filtros normalizados, sin paginación ni orden.
     *
     * <p>Los estados se ordenan: {@code {PENDING, CONFIRMED}} y
     * {@code {CONFIRMED, PENDING}} son el mismo filtro y tienen que dar la
     * misma clave, o el cache guarda dos veces lo mismo y acierta la mitad.
     */
    static String keyOf(ReservationSearchCriteria criteria) {
        String descriptor = new StringBuilder()
                .append("user=").append(criteria.userEmail().map(Email::value).orElse(""))
                .append("|statuses=").append(criteria.statuses().stream()
                        .map(ReservationStatus::name).sorted().collect(Collectors.joining(",")))
                .append("|from=").append(criteria.departureFrom().map(Object::toString).orElse(""))
                .append("|to=").append(criteria.departureTo().map(Object::toString).orElse(""))
                .toString();
        return CacheKeys.RESERVATION_COUNT_PREFIX + CacheKeys.digest(descriptor);
    }

    /** Un valor ilegible es un miss, no un error: el listado sigue contra la base. */
    private static Long parse(String raw) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            log.debug("Total cacheado ilegible, se trata como miss: {}", raw);
            return null;
        }
    }
}
