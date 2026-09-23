package com.edteam.reservations.infrastructure.config;

import com.edteam.reservations.application.port.in.ProcessReservationEventUseCase;
import com.edteam.reservations.application.port.out.EventPublisherPort;
import com.edteam.reservations.infrastructure.adapter.in.messaging.InboundEnvelopeParser;
import com.edteam.reservations.infrastructure.adapter.in.messaging.ReservationEventListener;
import com.edteam.reservations.infrastructure.adapter.out.messaging.DeadLetterQueue;
import com.edteam.reservations.infrastructure.adapter.out.messaging.LoggingEventPublisher;
import com.edteam.reservations.infrastructure.adapter.out.messaging.MessagingTopology;
import com.edteam.reservations.infrastructure.adapter.out.messaging.RabbitDeadLetterQueue;
import com.edteam.reservations.infrastructure.adapter.out.messaging.RabbitEventPublisher;
import com.edteam.reservations.infrastructure.adapter.out.messaging.UnavailableDeadLetterQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Cableado de la mensajería.
 *
 * <h2>La regla que manda: la aplicación arranca sin broker</h2>
 * Con {@code reservations.messaging.enabled=false} —o sin RabbitMQ levantado—
 * el publicador es el que sólo loguea, el consumidor no se levanta y los
 * eventos se acumulan en el outbox. Es el mismo criterio con el que hoy la
 * aplicación arranca sin Redis y sin el catálogo externo, y es lo que permite
 * que {@code mvn test} no necesite un contenedor.
 *
 * <p>Un broker caído <b>tampoco</b> tumba el servicio con la mensajería
 * encendida: la declaración de la topología es perezosa y el fallo de
 * publicación es un reintento del relay, no un error del pedido del usuario. Y
 * el <em>health indicator</em> de RabbitMQ va apagado, por el mismo motivo que
 * el de Redis: marcarnos {@code DOWN} nos sacaría de rotación por algo que no
 * afecta a los clientes.
 *
 * <h2>Qué se declara y qué no</h2>
 * En cualquier entorno, sólo el exchange: es lo único que el productor necesita
 * conocer. Las colas del consumidor se declaran bajo
 * {@code declare-consumer-topology}, encendida sólo en local, para que
 * {@code docker compose up} deje el circuito completo andando.
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MessagingConfiguration.class);

    // -----------------------------------------------------------------
    // Publicador
    // -----------------------------------------------------------------

    /**
     * Publicador real. Se cablea sólo con la mensajería encendida.
     *
     * <p>El {@code RabbitTemplate} llega de la autoconfiguración de Spring
     * Boot, que ya lee {@code publisher-confirm-type} y
     * {@code publisher-returns} de {@code application.yml}: el confirm y el
     * return no son opcionales para este adaptador, y declararlos en la
     * configuración —y no acá— es lo que permite verlos sin leer el código.
     */
    @Bean
    @ConditionalOnProperty(name = "reservations.messaging.enabled", havingValue = "true", matchIfMissing = true)
    public EventPublisherPort rabbitEventPublisher(RabbitTemplate rabbitTemplate,
                                                   ObjectMapper objectMapper,
                                                   MessagingProperties properties,
                                                   Clock clock) {
        log.info("Mensajería: publicando a '{}' como '{}' (confirm {} ms)",
                properties.exchange(), properties.source(), properties.confirmTimeout().toMillis());
        return new RabbitEventPublisher(rabbitTemplate, objectMapper, properties.exchange(),
                properties.source(), properties.confirmTimeout(), clock);
    }

    /** Reemplazo sin broker. Ver {@link LoggingEventPublisher}. */
    @Bean
    @ConditionalOnProperty(name = "reservations.messaging.enabled", havingValue = "false")
    public EventPublisherPort loggingEventPublisher(MessagingProperties properties) {
        log.warn("Mensajería APAGADA (reservations.messaging.enabled=false): los eventos se marcan como "
                + "despachados sin publicarse en ningún lado. Es correcto en local y en los tests; "
                + "en cualquier otro entorno es una pérdida silenciosa de notificaciones.");
        return new LoggingEventPublisher(properties.source());
    }

    // -----------------------------------------------------------------
    // Topología
    // -----------------------------------------------------------------

    /**
     * El exchange al que publica el relay.
     *
     * <p>{@code TopicExchange} y no {@code DirectExchange} porque el routing
     * declarativo es justamente lo que se compró: el productor publica con el
     * tipo del hecho como routing key y no nombra a ningún destinatario. Un
     * consumidor nuevo se suscribe sin que cambie una línea de acá.
     */
    @Bean
    public TopicExchange reservationEventsExchange(MessagingProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }

    /**
     * {@code RabbitAdmin} con declaración perezosa y tolerante.
     *
     * <p>{@code setFailFast(false)}: un broker caído en el arranque no puede
     * impedir que la aplicación levante. Es la diferencia entre "la mensajería
     * está degradada" y "el servicio no arranca".
     */
    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setIgnoreDeclarationExceptions(true);
        admin.afterPropertiesSet();
        return admin;
    }

    /**
     * Topología de referencia del consumidor.
     *
     * <p>Estas colas son <b>del servicio de notificaciones</b>, no nuestras. Se
     * declaran acá, y sólo bajo {@code declare-consumer-topology}, para que el
     * repositorio funcione de punta a punta en local: {@code docker compose up}
     * deja el circuito completo andando. En cualquier otro entorno la
     * aplicación declara únicamente el exchange y cada consumidor declara y ata
     * su propia cola.
     *
     * <p>Declararlas es, técnicamente, saber quién consume. Se acepta con dos
     * límites: está detrás de una bandera apagada por defecto, y el código de
     * negocio no menciona ninguna cola en ningún caso.
     *
     * <p>Va en una clase anidada para que se vea que es un bloque opcional y
     * que sale entero el día que el consumidor sea de verdad otro servicio.
     */
    @Configuration
    @ConditionalOnProperty(
            name = {"reservations.messaging.enabled", "reservations.messaging.declare-consumer-topology"},
            havingValue = "true")
    public static class ConsumerTopology {

        private final MessagingProperties properties;

        ConsumerTopology(MessagingProperties properties) {
            this.properties = properties;
        }

        /**
         * Cola de trabajo.
         *
         * <p>Cuórum y durable: el trabajo tiene que esperar al consumidor
         * aunque esté caído, y eso lo da la cola, no el exchange —un exchange
         * sin colas atadas descarta lo que recibe—.
         *
         * <p>{@code x-dead-letter-exchange} apunta a la DLQ y <b>nunca</b> a la
         * cola de espera: si el rechazo del broker y el reintento apuntaran al
         * mismo lugar, un mensaje podría entrar en un bucle
         * espera → reinyección → espera. Los reintentos los decide el
         * consumidor de forma explícita.
         *
         * <p>{@code x-delivery-limit} corta el crash-loop: un consumidor que
         * muere antes de confirmar hace que la cola redelivere, y sin este tope
         * lo haría para siempre.
         *
         * <p>{@code reject-publish} en lugar de {@code drop-head}: al llenarse,
         * la cola hace fallar <em>nuestra</em> publicación, el relay la
         * reintenta con backoff y la fila se acumula en el outbox, donde se
         * puede ver y drenar. {@code drop-head} descartaría notificaciones en
         * silencio.
         */
        @Bean
        public Queue reservationEventsQueue() {
            return QueueBuilder.durable(MessagingTopology.CONSUMER_QUEUE)
                    .quorum()
                    .deadLetterExchange(MessagingTopology.DLQ_EXCHANGE)
                    .maxLength(properties.queueMaxLength())
                    .overflow(QueueBuilder.Overflow.rejectPublish)
                    .deliveryLimit(properties.maxRetryRounds())
                    .build();
        }

        /**
         * Cola de espera del reintento: sin consumidor, con TTL y con DLX hacia
         * el exchange de reinyección. La TTL <b>es</b> el backoff.
         */
        @Bean
        public Queue reservationEventsRetryQueue() {
            return QueueBuilder.durable(MessagingTopology.RETRY_QUEUE)
                    .ttl((int) properties.retryDelay().toMillis())
                    .deadLetterExchange(MessagingTopology.REQUEUE_EXCHANGE)
                    .build();
        }

        /** Dead letter del consumidor: durable y sin TTL. Alerta con profundidad &gt; 0. */
        @Bean
        public Queue reservationEventsDlq() {
            return QueueBuilder.durable(MessagingTopology.DLQ).build();
        }

        @Bean
        public FanoutExchange retryExchange() {
            return new FanoutExchange(MessagingTopology.RETRY_EXCHANGE, true, false);
        }

        @Bean
        public FanoutExchange requeueExchange() {
            return new FanoutExchange(MessagingTopology.REQUEUE_EXCHANGE, true, false);
        }

        @Bean
        public FanoutExchange dlqExchange() {
            return new FanoutExchange(MessagingTopology.DLQ_EXCHANGE, true, false);
        }

        /**
         * {@code reservation.*} y nunca {@code reservation.#}: {@code *}
         * matchea exactamente una palabra, así que una versión mayor futura
         * ({@code reservation.created.v2}) no llega a este consumidor. Con
         * {@code #} recibiría v1 y v2 y duplicaría todo.
         */
        @Bean
        public Binding reservationEventsBinding(TopicExchange reservationEventsExchange) {
            return BindingBuilder.bind(reservationEventsQueue())
                    .to(reservationEventsExchange)
                    .with(MessagingTopology.CONSUMER_BINDING);
        }

        @Bean
        public Binding retryBinding() {
            return BindingBuilder.bind(reservationEventsRetryQueue()).to(retryExchange());
        }

        /** Segundo binding de la cola principal: por acá vuelven los que esperaron. */
        @Bean
        public Binding requeueBinding() {
            return BindingBuilder.bind(reservationEventsQueue()).to(requeueExchange());
        }

        @Bean
        public Binding dlqBinding() {
            return BindingBuilder.bind(reservationEventsDlq()).to(dlqExchange());
        }
    }

    // -----------------------------------------------------------------
    // Consumidor
    // -----------------------------------------------------------------

    /**
     * Conexión propia del lado de consumo.
     *
     * <p>No es una duplicación gratuita: <b>los confirms y las transacciones de
     * canal son incompatibles en el mismo canal</b>. El broker rechaza pasar de
     * {@code tx} a {@code confirm} con un {@code PRECONDITION_FAILED}, y las dos
     * cosas se necesitan de verdad: el publicador no puede marcar
     * {@code DISPATCHED} sin el ack del broker, y el consumidor no puede
     * reencolar y confirmar en dos operaciones separadas sin abrir la ventana en
     * la que el mensaje se multiplica.
     *
     * <p>La conexión de red subyacente se reutiliza: lo que cambia es la
     * configuración del canal.
     *
     * <p>{@code defaultCandidate = false} para que este bean no compita con el
     * de la autoconfiguración en las inyecciones por tipo: se pide siempre por
     * nombre, y así queda explícito cuál de los dos usa cada pieza.
     */
    @Bean(defaultCandidate = false)
    public CachingConnectionFactory consumerConnectionFactory(CachingConnectionFactory rabbitConnectionFactory) {
        CachingConnectionFactory consumer =
                new CachingConnectionFactory(rabbitConnectionFactory.getRabbitConnectionFactory());
        consumer.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.NONE);
        consumer.setPublisherReturns(false);
        consumer.setConnectionNameStrategy(factory -> "reservations-consumer");
        return consumer;
    }

    /**
     * Template del lado de consumo, transaccionado.
     *
     * <p>Lo usa el listener para reencolar y para mandar a la DLQ. Comparte la
     * conexión del contenedor, que es lo que hace que su publicación entre en la
     * <b>misma transacción de canal</b> que el {@code ack} del mensaje original.
     */
    @Bean(defaultCandidate = false)
    public RabbitTemplate consumerRabbitTemplate(
            @Qualifier("consumerConnectionFactory") CachingConnectionFactory consumerConnectionFactory) {
        RabbitTemplate template = new RabbitTemplate(consumerConnectionFactory);
        template.setChannelTransacted(true);
        return template;
    }

    /**
     * Template del lado de consumo <b>sin</b> transacción, para inspeccionar la
     * dead letter.
     *
     * <p>Existe porque {@code basic.nack} no es transaccional en RabbitMQ: si
     * el reencolado del peek fuera dentro de un {@code tx.commit} se
     * descartaría, y mirar la dead letter la vaciaría.
     */
    @Bean(defaultCandidate = false)
    public RabbitTemplate dlqInspectionRabbitTemplate(
            @Qualifier("consumerConnectionFactory") CachingConnectionFactory consumerConnectionFactory) {
        return new RabbitTemplate(consumerConnectionFactory);
    }

    /**
     * Contenedor del consumidor, con <b>canal transaccionado</b>.
     *
     * <p>No es una optimización: es la corrección de una ventana concreta. El
     * consumidor, al fallar de forma transitoria, publica el mensaje a la cola
     * de espera y confirma el original. Con dos operaciones separadas, una
     * caída en el medio deja el mensaje en la cola de espera <em>y</em> sin
     * confirmar, así que la cola lo vuelve a entregar y el mensaje se
     * multiplica en cada vuelta. Con la transacción de canal, publicación y
     * confirmación se comprometen juntas en el broker.
     *
     * <p><b>Sin {@code transactionManager}</b>, y es deliberado. Atar la
     * transacción de la base a la del canal parece más prolijo y rompe el
     * manejo de errores: el caso de uso es {@code @Transactional}, así que una
     * excepción suya marcaría como <em>rollback-only</em> la transacción del
     * contenedor y el commit posterior fallaría —justo en el camino en el que el
     * listener ya decidió mandar el mensaje a la DLQ—. Sin él, el orden es el
     * correcto: la base confirma primero (al volver el caso de uso) y el canal
     * después (al volver el listener). No son atómicos entre sí, y no hace falta
     * que lo sean: una caída entre los dos commits produce una reentrega, y la
     * reentrega la absorbe la deduplicación.
     *
     * <p>{@code prefetch=1}: el consumidor procesa una notificación por vez.
     * Con un volumen de una operación de reserva no hay nada que ganar
     * paralelizando, y de paso se conserva el orden <em>best effort</em> que un
     * prefetch mayor rompería.
     *
     * <p>{@code defaultRequeueRejected=false}: si una excepción se escapara del
     * listener, el mensaje no vuelve a la cola en caliente. El destino se
     * decide de forma explícita en el listener.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            @Qualifier("consumerConnectionFactory") CachingConnectionFactory consumerConnectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(consumerConnectionFactory);
        factory.setChannelTransacted(true);
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(false);
        // Arranque perezoso: sin la cola declarada (o sin broker) el
        // contenedor reintenta en lugar de impedir el arranque.
        factory.setMissingQueuesFatal(false);
        return factory;
    }

    /**
     * Consumidor de referencia de este repositorio.
     *
     * <p>En producción el consumidor es el servicio de notificaciones, que es
     * su dueño: el nuestro existe para que el circuito se pueda levantar y
     * probar de punta a punta acá, y por eso va detrás de su propia bandera.
     *
     * <p>Exige <b>las dos</b> banderas, y la primera no es redundante: con la
     * mensajería apagada —que es el interruptor documentado para levantar sin
     * broker— un consumidor encendido igual abre su contenedor y se queda
     * reintentando la conexión contra un broker que no está, llenando el log de
     * «Connection refused» y de «Failed to check/redeclare queue». Es
     * exactamente el ruido que ese interruptor tiene que evitar.
     */
    @Bean
    @ConditionalOnProperty(
            name = {"reservations.messaging.enabled", "reservations.messaging.consumer-enabled"},
            havingValue = "true")
    public ReservationEventListener reservationEventListener(
            ProcessReservationEventUseCase processEvent,
            ObjectMapper objectMapper,
            @Qualifier("consumerRabbitTemplate") RabbitTemplate rabbitTemplate,
            MessagingProperties properties,
            MeterRegistry registry) {
        log.info("Mensajería: consumidor de referencia levantado sobre '{}' ({} vueltas de reintento de {} ms)",
                MessagingTopology.CONSUMER_QUEUE, properties.maxRetryRounds(),
                properties.retryDelay().toMillis());
        return new ReservationEventListener(processEvent, new InboundEnvelopeParser(objectMapper),
                rabbitTemplate, properties.maxRetryRounds(), registry);
    }

    // -----------------------------------------------------------------
    // Dead letter del consumidor
    // -----------------------------------------------------------------

    /**
     * El peek y el replay mueven mensajes entre colas, así que van sobre el
     * template transaccionado del lado de consumo: o el mensaje se movió o
     * sigue donde estaba, nunca en las dos colas ni en ninguna.
     */
    @Bean
    @ConditionalOnProperty(name = "reservations.messaging.enabled", havingValue = "true", matchIfMissing = true)
    public DeadLetterQueue rabbitDeadLetterQueue(
            @Qualifier("consumerRabbitTemplate") RabbitTemplate consumerRabbitTemplate,
            @Qualifier("dlqInspectionRabbitTemplate") RabbitTemplate dlqInspectionRabbitTemplate,
            RabbitAdmin rabbitAdmin) {
        return new RabbitDeadLetterQueue(consumerRabbitTemplate, dlqInspectionRabbitTemplate, rabbitAdmin);
    }

    @Bean
    @ConditionalOnProperty(name = "reservations.messaging.enabled", havingValue = "false")
    public DeadLetterQueue unavailableDeadLetterQueue() {
        return new UnavailableDeadLetterQueue();
    }
}
