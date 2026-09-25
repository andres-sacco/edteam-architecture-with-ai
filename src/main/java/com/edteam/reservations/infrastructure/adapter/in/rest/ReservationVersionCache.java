package com.edteam.reservations.infrastructure.adapter.in.rest;

import com.edteam.reservations.domain.model.ReservationId;
import com.edteam.reservations.infrastructure.cache.CacheKeys;
import com.edteam.reservations.infrastructure.cache.CacheStore;
import com.edteam.reservations.infrastructure.logging.LogFields;
import com.edteam.reservations.infrastructure.logging.LogSanitizer;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache de la versión de una reserva, para responder {@code 304}.
 * <strong>Es la entrada P2 del análisis de cuellos de botella.</strong>
 *
 * <h2>Qué problema resuelve, y cuál no</h2>
 * {@code GET /v1/reservations/{id}} es el endpoint más llamado —cada frontend
 * refresca la reserva que el usuario está mirando—, pero su consulta es un
 * lookup por clave primaria: cara en hidratación (cinco tablas con
 * {@code @EntityGraph}), no en búsqueda. Y su cuerpo es exactamente lo que la
 * restricción prohíbe cachear: {@code PassengerResponse} expone
 * {@code documentNumber} y {@code birthDate} de personas físicas, sobre una
 * API que todavía no tiene autenticación.
 *
 * <p>Así que no se cachea el cuerpo: se cachea <strong>el número de
 * versión</strong>. Un pedido con {@code If-None-Match} que coincide se
 * responde {@code 304 Not Modified} con un {@code GET} al cache, sin tocar
 * PostgreSQL, sin hidratar cinco tablas y sin serializar ni un dato de
 * pasajero. En un listado que el usuario deja abierto con refresco periódico,
 * la mayoría de los refrescos son 304.
 *
 * <p>Lo que hay guardado es un entero. No hay nada que proteger: ~90 B por
 * entrada, y aun con 100k reservas en caliente son ~9 MB.
 *
 * <h2>Por qué vive en el adaptador REST y no detrás del puerto</h2>
 * Porque {@code ReservationRepositoryPort} lo usan por igual la lectura y la
 * escritura, y una regla de este diseño es que <em>el camino de escritura
 * nunca lee del cache</em> (ver abajo). Un decorador sobre el puerto no puede
 * distinguir quién llama. Además, {@code ETag} / {@code If-None-Match} es
 * vocabulario de HTTP: resolver una petición condicional es trabajo del
 * adaptador de entrada, igual que traducir {@code If-Match} a
 * {@code expectedVersion}.
 *
 * <h2>La interacción con el locking optimista</h2>
 * Acá es donde un cache mal hecho genera exactamente el {@code 409} que el
 * sistema quiere evitar. Si la versión cacheada quedara vieja —dice 7, la real
 * es 8—, un cliente con {@code If-None-Match: "7"} recibiría {@code 304},
 * conservaría su representación vieja y en el {@code PUT} mandaría
 * {@code If-Match: "7"} → 409 evitable. Sería peor que no cachear.
 *
 * <p>Tres reglas lo cierran:
 * <ol>
 *   <li><b>La escritura borra la clave, no la actualiza.</b> Un
 *       {@link #forget(ReservationId)} es idempotente y seguro ante fallos
 *       parciales; un {@code SET} con un valor equivocado se queda pegado
 *       hasta que venza.</li>
 *   <li><b>TTL corto igual</b> (60 s por defecto), como red de seguridad: si
 *       una invalidación se pierde —el proceso se cae entre el commit y el
 *       borrado, o Redis no responde—, el daño se autolimita a un minuto en
 *       lugar de ser permanente.</li>
 *   <li><b>El camino de escritura nunca lee de acá.</b> Los casos de uso que
 *       modifican siguen leyendo con {@code findById()} dentro de su
 *       transacción, contra PostgreSQL. Este cache sirve únicamente para
 *       responder {@code If-None-Match}.</li>
 * </ol>
 *
 * <p>Con eso, el peor caso de una entrada desactualizada es un {@code 200} con
 * cuerpo fresco en lugar de un {@code 304}: se pierde un ahorro, no se genera
 * un conflicto.
 */
public class ReservationVersionCache {

    private static final Logger log = LoggerFactory.getLogger(ReservationVersionCache.class);

    private final CacheStore cache;
    private final Duration ttl;

    public ReservationVersionCache(CacheStore cache, Duration ttl) {
        this.cache = Objects.requireNonNull(cache, "El almacén de cache es obligatorio");
        this.ttl = Objects.requireNonNull(ttl, "El TTL es obligatorio");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("El TTL debe ser positivo");
        }
    }

    /** Versión conocida de la reserva, si está vigente en el cache. */
    public OptionalLong find(ReservationId reservationId) {
        Objects.requireNonNull(reservationId, "El id es obligatorio");
        Optional<String> raw = cache.get(keyOf(reservationId));
        if (raw.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(raw.get()));
        } catch (NumberFormatException e) {
            log.atDebug()
                    .addKeyValue(LogFields.EVENT, "cache.unreadable")
                    .addKeyValue(LogFields.CACHE, "reservation-version")
                    .addKeyValue(LogFields.RESERVATION_ID, reservationId.value())
                    .addKeyValue("raw", LogSanitizer.sanitize(raw.get(), 64))
                    .log("Versión cacheada ilegible: se trata como miss");
            return OptionalLong.empty();
        }
    }

    /** Guarda la versión leída del origen. */
    public void remember(ReservationId reservationId, long version) {
        Objects.requireNonNull(reservationId, "El id es obligatorio");
        cache.put(keyOf(reservationId), Long.toString(version), ttl);
    }

    /**
     * Borra la versión: la llama toda operación que escribe, después de que el
     * caso de uso devolvió (es decir, con la transacción ya confirmada).
     */
    public void forget(ReservationId reservationId) {
        Objects.requireNonNull(reservationId, "El id es obligatorio");
        cache.evict(keyOf(reservationId));
    }

    private static String keyOf(ReservationId reservationId) {
        return CacheKeys.RESERVATION_VERSION_PREFIX + reservationId.value();
    }
}
