package com.edteam.reservations.application.port.in;

/**
 * Puerto de entrada: aplicar un hecho de reserva recibido por mensajería.
 *
 * <p>Es un caso de uso propio, disparado por el sistema y no por un usuario, y
 * existe para que el consumidor del broker no tenga lógica: el adaptador de
 * entrada parsea, delega acá y decide qué hacer con el mensaje según el
 * resultado. Cambiar el transporte —otro broker, un webhook, un job de
 * recuperación— no toca esta lógica ni sus tests.
 */
public interface ProcessReservationEventUseCase {

    /**
     * Aplica el hecho si no estaba aplicado.
     *
     * <p>Idempotente por contrato: invocarlo dos veces con el mismo
     * {@code messageId} deja exactamente el mismo estado y produce un solo
     * efecto.
     *
     * @throws com.edteam.reservations.application.exception.UnprocessableEventException
     *         si el mensaje no se puede aplicar y reintentarlo no va a cambiarlo
     */
    EventProcessingOutcome process(InboundEvent event);
}
