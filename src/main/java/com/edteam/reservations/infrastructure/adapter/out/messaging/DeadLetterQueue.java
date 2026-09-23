package com.edteam.reservations.infrastructure.adapter.out.messaging;

import java.util.List;

/**
 * Dead letter del <b>consumidor</b>: lo que llegó y no se pudo procesar.
 *
 * <p>Es distinta de la dead letter del productor ({@code outbox_message} en
 * estado {@code FAILED}) y por eso son dos cosas separadas con dos métricas
 * separadas: una dice «no pudimos publicar» y la otra «no pudieron procesar».
 * Se resuelven en lugares distintos y con gente distinta. Y si lo que está
 * caído es el broker, una dead letter <em>dentro</em> del broker es
 * inalcanzable justo cuando hace falta: por eso la del productor vive en la
 * base.
 *
 * <p>No es un puerto de la aplicación: ningún caso de uso inspecciona una cola.
 * Es la herramienta del operador, la consume el endpoint del puerto de gestión
 * y vive en infraestructura, del mismo lado que quien la usa.
 */
public interface DeadLetterQueue {

    /**
     * Cuántos mensajes hay. Es la métrica que alerta con {@code > 0}.
     *
     * @return la profundidad, o {@code -1} si no se pudo averiguar (sin broker)
     */
    long depth();

    /**
     * Mira los mensajes <b>sin consumirlos</b>.
     *
     * <p>No devuelve el cuerpo: lleva ruta y fecha de viaje atadas a un
     * usuario, y el contenido de la DLQ se trata como dato productivo. Para el
     * cuerpo hay que entrar a la consola del broker, que es una decisión
     * deliberada.
     */
    List<DeadLetter> peek(int limit);

    /**
     * Reencola la DLQ hacia la cola principal, para después de arreglar la
     * causa.
     *
     * <p>Los mensajes vuelven con su {@code messageId} intacto: el que ya se
     * hubiera aplicado se deduplica del otro lado y no produce un segundo
     * efecto. Es lo que hace que reprocesar sea seguro y no una apuesta.
     *
     * @param max tope de mensajes a mover
     * @return cuántos volvieron
     */
    int replay(int max);

    /**
     * Un mensaje muerto, como lo muestra el endpoint de gestión.
     *
     * @param messageId clave de idempotencia
     * @param type      tipo del hecho
     * @param subject   reserva a la que se refiere
     * @param attempts  vueltas de reintento consumidas
     * @param reason    por qué terminó acá
     */
    record DeadLetter(String messageId, String type, String subject, long sequence,
                      int attempts, String reason) {
    }
}
