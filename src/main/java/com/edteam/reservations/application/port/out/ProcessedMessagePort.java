package com.edteam.reservations.application.port.out;

/**
 * Registro de los mensajes que el consumidor ya aplicó: la deduplicación.
 *
 * <p>Es la contracara obligatoria del <em>at-least-once</em>. La entrega
 * duplicada no es una falla del transporte, es su modo normal de operación: un
 * ack que se pierde, un reintento del relay, una redelivery después de que el
 * consumidor muriera antes de confirmar. Sin esta tabla, cada uno de esos
 * casos es un «tu reserva quedó confirmada» de más al mismo usuario.
 */
public interface ProcessedMessagePort {

    /**
     * Reserva el mensaje para este procesamiento.
     *
     * @return {@code true} si es la primera vez que se ve este id —hay que
     *         procesarlo—; {@code false} si ya estaba aplicado y no hay que
     *         hacer nada más que confirmarlo
     */
    boolean claim(String messageId, String type, String subject, long sequence);

    /**
     * Mayor {@code sequence} ya aplicado para esa reserva, sin contar el
     * mensaje en curso, o {@link Long#MIN_VALUE} si no hay ninguno.
     *
     * <p>La exclusión no es un detalle: {@link #claim} ya dejó la fila de este
     * mensaje, así que sin excluirlo la comparación sería contra sí mismo y
     * nunca detectaría un desorden.
     *
     * <p>Se consulta para <b>detectar</b> el desorden y registrarlo, nunca para
     * descartar. Descartar un mensaje por traer un {@code sequence} menor
     * borraría eventos legítimos que llegaron desordenados —que es lo que el
     * backoff del relay y el ciclo de retry producen por diseño— y lo haría en
     * silencio, porque para el broker el mensaje se procesó bien.
     */
    long lastAppliedSequence(String subject, String excludingMessageId);

    /** Borra los registros anteriores al límite. Es la ventana de retención. */
    int purgeProcessedBefore(java.time.Instant limit);
}
