package com.edteam.reservations.infrastructure.adapter.out.messaging;

import com.edteam.reservations.application.outbox.OutboxMessage;
import com.edteam.reservations.application.port.out.EventPublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Publicador de reemplazo: escribe una línea y devuelve.
 *
 * <p><strong>No es una concesión, es el mismo patrón que el stub del catálogo y
 * el cache en memoria:</strong> es lo que hace que {@code mvn test} y
 * {@code mvn spring-boot:run} no dependan de un contenedor. Se activa con
 * {@code reservations.messaging.enabled=false}.
 *
 * <p>El riesgo es concreto y está nombrado: que alguien lo deje encendido donde
 * no corresponde, porque marca los mensajes como despachados sin publicarlos.
 * Por eso avisa en cada arranque —ver {@code MessagingConfiguration}— y por eso
 * loguea en {@code WARN} y no en {@code DEBUG}: un log que nadie lee es la
 * mitigación que no mitiga.
 *
 * <p>Lo que <b>no</b> hace, y es deliberado: redactar el texto de la
 * notificación. El adaptador que reemplazó armaba el mensaje con un
 * {@code switch} sobre los cuatro eventos, y redactar nunca fue trabajo de este
 * servicio: es del dueño de las plantillas y del canal. El {@code switch}
 * exhaustivo se conserva donde corresponde, en
 * {@link DomainEventPayloadMapper}.
 */
public class LoggingEventPublisher implements EventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    private final String source;

    public LoggingEventPublisher(String source) {
        this.source = Objects.requireNonNull(source, "El source es obligatorio");
    }

    @Override
    public void publish(OutboxMessage message) {
        Objects.requireNonNull(message, "El mensaje es obligatorio");

        // Dos niveles, y el corte no es estético. En INFO/WARN queda la traza
        // operativa —qué tipo, sobre qué reserva, con qué id— que no identifica
        // a nadie fuera de nuestra base. El payload lleva ruta y fecha de
        // viaje, que atadas a un usuario sí son dato personal: va a DEBUG,
        // apagado en producción, donde el destino de estos logs es un SaaS de
        // observabilidad.
        log.warn("[mensajería APAGADA] no se publica nada: type={} subject={} messageId={} sequence={} source={}",
                message.type(), message.subject(), message.id(), message.sequence(), source);
        log.debug("[mensajería APAGADA] messageId={} payload={}", message.id(), message.payload());
    }
}
