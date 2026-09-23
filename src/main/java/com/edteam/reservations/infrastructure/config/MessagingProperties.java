package com.edteam.reservations.infrastructure.config;

import com.edteam.reservations.infrastructure.adapter.out.messaging.MessagingTopology;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuración de la mensajería.
 *
 * <p>Los timeouts son <b>de este proveedor</b> y no globales, siguiendo la
 * decisión que ya gobierna al catálogo de ciudades: el tiempo que tolera un
 * <em>publisher confirm</em> de RabbitMQ no tiene por qué ser el del próximo
 * servicio que se integre.
 *
 * @param enabled         con {@code false} queda el publicador que sólo
 *                        loguea: la aplicación arranca y los tests corren sin
 *                        broker, igual que arrancan sin Redis y sin el
 *                        catálogo. Es el mismo patrón, con el mismo riesgo
 *                        —que alguien lo deje apagado donde no corresponde— y
 *                        la misma mitigación: un aviso en cada arranque
 * @param exchange        topic exchange al que publica el relay. Es lo único
 *                        que el productor necesita conocer de la topología
 * @param source          URN del emisor. Va en el envelope y es lo que evita
 *                        que un mensaje de staging se procese como productivo
 * @param confirmTimeout  cuánto se espera la confirmación del broker antes de
 *                        tratar la publicación como fallida. Sin esperarla, el
 *                        mensaje se marcaría como despachado apenas los bytes
 *                        salen del socket, que es mucho antes de que el broker
 *                        los haya escrito
 * @param declareConsumerTopology declara también las colas del consumidor.
 *                        Sólo en local: es lo que hace que
 *                        {@code docker compose up} deje el circuito completo
 *                        andando. Declararlas es, técnicamente, saber quién
 *                        consume; en cualquier otro entorno la aplicación
 *                        declara únicamente el exchange y cada consumidor
 *                        declara y ata su propia cola
 * @param consumerEnabled levanta el consumidor de referencia de este
 *                        repositorio. En producción el consumidor es el
 *                        servicio de notificaciones y esto va apagado
 * @param retryDelay      espera de la cola de reintento (su {@code x-message-ttl}).
 *                        El backoff vive ahí y no en un {@code sleep} del
 *                        handler, que ocuparía el canal y frenaría los mensajes
 *                        sanos de atrás
 * @param maxRetryRounds  vueltas de reintento antes de la DLQ
 * @param queueMaxLength  tope de la cola principal. Al llenarse rechaza la
 *                        publicación en lugar de descartar en silencio: la fila
 *                        se acumula en el outbox, que es donde se puede ver,
 *                        medir y drenar
 */
@ConfigurationProperties(prefix = "reservations.messaging")
public record MessagingProperties(boolean enabled,
                                  String exchange,
                                  String source,
                                  Duration confirmTimeout,
                                  boolean declareConsumerTopology,
                                  boolean consumerEnabled,
                                  Duration retryDelay,
                                  int maxRetryRounds,
                                  int queueMaxLength) {

    public MessagingProperties {
        if (exchange == null || exchange.isBlank()) {
            exchange = MessagingTopology.EVENTS_EXCHANGE;
        }
        if (source == null || source.isBlank()) {
            source = "urn:edteam:flight-reservations";
        }
        if (confirmTimeout == null || confirmTimeout.isNegative() || confirmTimeout.isZero()) {
            confirmTimeout = Duration.ofSeconds(5);
        }
        if (retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()) {
            retryDelay = Duration.ofSeconds(30);
        }
        if (maxRetryRounds < 1) {
            maxRetryRounds = 5;
        }
        if (queueMaxLength < 1) {
            queueMaxLength = 100_000;
        }
    }
}
