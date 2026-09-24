package com.edteam.reservations.support;

import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base de los tests de integración que necesitan un broker de verdad.
 *
 * <p>Con RabbitMQ real y no con un doble, porque lo que hay que validar es
 * justamente lo que un doble no puede reproducir: los <em>publisher
 * confirms</em>, el {@code mandatory} sobre un exchange sin bindings, la TTL de
 * la cola de espera que devuelve el mensaje por su DLX y el
 * {@code x-delivery-limit} de la cola cuórum. Con un mock, estos tests pasarían
 * sin probar nada de eso.
 *
 * <p>Hereda de {@link AbstractPostgresIT} y le <b>enciende la mensajería</b>:
 * esa clase corre sin broker a propósito —es la que verifica que la aplicación
 * arranque sin él— y acá se invierte esa decisión sólo para estos tests.
 *
 * <p>El contenedor se levanta una vez para todos: arrancar uno por clase
 * multiplicaría el tiempo del build sin agregar aislamiento, porque las colas
 * se purgan en cada test.
 *
 * <h2>Un solo consumidor vivo por vez</h2>
 * {@code @DirtiesContext} no es una precaución genérica, resuelve un problema
 * concreto: cada clase que usa {@code @MockitoSpyBean} o
 * {@code @AutoConfigureMockMvc} tiene su <b>propio</b> contexto, y Spring los
 * mantiene en caché al terminar la clase. Con dos contextos vivos hay dos
 * contenedores de listeners atados a la misma cola, así que los mensajes de una
 * clase se los come el consumidor de la otra —que además puede estar con un
 * doble que falla a propósito— y los tests se vuelven intermitentes según el
 * orden de ejecución. Cerrando el contexto al terminar cada clase, nunca hay
 * más de un consumidor escuchando.
 *
 * <p>Las propiedades van en {@code @TestPropertySource} y <b>no</b> en otro
 * {@code @SpringBootTest}: un segundo {@code @SpringBootTest} en la subclase
 * reemplaza al del padre en lugar de sumarse, y se perderían las que hacen que
 * el build no dependa de servicios externos (el stub del catálogo, el cache en
 * memoria, los tokens de desarrollo).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "reservations.messaging.enabled=true",
        // Declara las colas del consumidor: sin esto no hay circuito completo
        // que probar. Es la misma bandera que enciende el perfil local.
        "reservations.messaging.declare-consumer-topology=true",
        "reservations.messaging.consumer-enabled=true",
        // Reintento corto: el test no puede esperar los 30 s de producción, y
        // lo que se está probando es el mecanismo, no el valor.
        "reservations.messaging.retry-delay=1s",
        // Techo igual al primer escalón: el backoff del consumidor es
        // exponencial con jitter, y acá lo que se prueba es el mecanismo de
        // reintento y dead-letter, no la progresión —que tiene su propio test
        // unitario—. Sin esto, dos vueltas tardarían tres segundos en lugar
        // de uno y los tests se volverían lentos sin verificar nada nuevo.
        "reservations.messaging.max-retry-delay=1s",
        "reservations.messaging.max-retry-rounds=2"
})
public abstract class AbstractRabbitIT extends AbstractPostgresIT {

    private static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management-alpine"));

    static {
        RABBIT.start();
    }

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }
}
