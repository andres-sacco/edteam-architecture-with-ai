package com.edteam.reservations.application.service;

import com.edteam.reservations.application.port.out.ReservationRepositoryPort;
import com.edteam.reservations.application.port.out.UserRepositoryPort;
import com.edteam.reservations.domain.model.Email;
import com.edteam.reservations.domain.model.IdempotencyKey;
import com.edteam.reservations.domain.model.Reservation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * Resuelve la clave de idempotencia <strong>antes</strong> de validar el
 * itinerario.
 *
 * <p>La clave ya protegía la base: dos {@code POST} con la misma
 * {@code Idempotency-Key} crean una sola reserva. Lo que no protegía era la
 * <em>carga</em>. Un cliente que corta a los treinta segundos y reintenta
 * disparaba otra tanda entera de consultas contra un catálogo que ya estaba
 * degradado, mientras el pedido anterior seguía corriendo. La clave evitaba la
 * reserva doble —bien— pero no la amplificación, que es justamente lo que
 * convierte la degradación de un tercero en su caída.
 *
 * <p>Con esta consulta primero, el reintento del usuario cuesta una lectura
 * por índice y devuelve la reserva que ya existe: no hay nada que validar
 * sobre un itinerario que ya se guardó.
 *
 * <p>Es una clase aparte y no un método del caso de uso por la regla de
 * arquitectura que ya existe: {@code CreateReservationService} no puede ser
 * transaccional, porque eso metería la llamada al catálogo dentro de la
 * transacción y convertiría la lentitud del proveedor en agotamiento del pool.
 * Acá la transacción es de sólo lectura, con techo propio y sin ninguna
 * dependencia del catálogo.
 */
@Service
public class ReservationIdempotencyLookup {

    private final UserRepositoryPort userRepository;
    private final ReservationRepositoryPort reservationRepository;

    ReservationIdempotencyLookup(UserRepositoryPort userRepository,
                                 ReservationRepositoryPort reservationRepository) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.reservationRepository = Objects.requireNonNull(reservationRepository);
    }

    /**
     * @param timeout 1 segundo: son dos lecturas por índice único. Más que eso
     *                significa que la base está saturada, y ahí lo correcto es
     *                rechazar rápido y no sumar a la cola.
     */
    @Transactional(readOnly = true, timeout = 1)
    public Optional<Reservation> findExisting(Email email, IdempotencyKey key) {
        if (email == null || key == null) {
            return Optional.empty();
        }
        return userRepository.findByEmail(email)
                .flatMap(user -> reservationRepository.findByIdempotencyKey(user.requireId(), key));
    }
}
