package com.edteam.reservations.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.Encoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.event.KeyValuePair;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El esquema del log es un contrato, y esto es lo que lo sostiene.
 *
 * <p>Verifica el hallazgo 8 de la auditoría —«el correlationId no aparece en
 * ninguna línea de log»— por el único camino que no admite interpretación:
 * cargando el {@code logback-spring.xml} <b>real</b>, tomando el encoder que
 * ese archivo configura, corriéndolo sobre un evento y parseando el resultado
 * como JSON.
 *
 * <p>Un test que grepeara el XML buscando {@code <mdc/>} probaría que alguien
 * escribió una línea en un archivo. Este prueba que el campo sale, con los
 * providers que el archivo declara y con los nombres de campo que declara. Si
 * mañana alguien renombra {@code logger} o saca el provider del MDC, falla acá
 * y no el día del incidente.
 *
 * <h2>Las dos sustituciones</h2>
 * {@code springProperty} y {@code springProfile} necesitan el {@code Environment}
 * de Spring, que en un test unitario no existe. Se reemplazan por sus
 * equivalentes de Logback conservando los valores por defecto del archivo, que
 * es exactamente lo que el arranque usa antes de que Spring resuelva nada.
 * Todo lo demás —providers, nombres de campo, formato del timestamp, el
 * converter del stack trace— es el del archivo, sin tocar.
 */
@DisplayName("Esquema del log")
class LogSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Los campos que el §1.2 del diseño declara obligatorios en TODO registro. */
    private static final List<String> ALWAYS_PRESENT = List.of(
            "@timestamp", "level", "logger", "message", "thread",
            "service", "env", "version", "instance");

    private static Encoder<ILoggingEvent> encoder;
    private static String xml;

    @BeforeAll
    static void loadTheRealConfiguration() throws Exception {
        xml = new String(new ClassPathResource("logback-spring.xml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        String standalone = xml
                // <springProperty name="x" source="..." defaultValue="d"/> → <property name="x" value="d"/>
                .replaceAll("<springProperty[^>]*name=\"(\\w+)\"[^>]*defaultValue=\"([^\"]*)\"[^>]*/>",
                        "<property scope=\"context\" name=\"$1\" value=\"$2\"/>")
                // El perfil de desarrollo no aplica: la suite corre con el
                // perfil por defecto, que es JSON.
                .replaceAll("(?s)<springProfile name=\"local,dev\">.*?</springProfile>", "")
                .replaceAll("(?s)<springProfile name=\"!local &amp; !dev\">(.*?)</springProfile>", "$1");

        LoggerContext context = new LoggerContext();
        context.setName("schema-test");
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(new ByteArrayInputStream(standalone.getBytes(StandardCharsets.UTF_8)));

        ch.qos.logback.classic.Logger root =
                context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        @SuppressWarnings("unchecked")
        ConsoleAppender<ILoggingEvent> json = (ConsoleAppender<ILoggingEvent>) root.getAppender("json");
        assertThat(json)
                .withFailMessage("El appender 'json' no quedó enchufado al root: "
                        + "fuera de los perfiles de desarrollo el formato tiene que ser JSON")
                .isNotNull();
        encoder = json.getEncoder();
    }

    @Test
    @DisplayName("un registro emitido es un objeto JSON con los nueve campos comunes del esquema")
    void emitsTheCommonSchema() throws Exception {
        JsonNode record = encode(event(Level.INFO, "Reserva creada",
                Map.of("event", "reservation.created", "reservationId", 10241L),
                Map.of("correlationId", "audit-0000-0001")));

        assertThat(ALWAYS_PRESENT)
                .allSatisfy(field -> assertThat(record.has(field))
                        .withFailMessage("Falta el campo obligatorio '%s' en el registro: %s", field, record)
                        .isTrue());
        assertThat(record.get("level").asText()).isEqualTo("INFO");
        assertThat(record.get("message").asText()).isEqualTo("Reserva creada");
        assertThat(record.get("service").asText()).isEqualTo("flight-reservations");
    }

    @Test
    @DisplayName("el correlationId del MDC sale como campo propio: es el hallazgo que desbloquea a los otros ocho")
    void writesTheCorrelationIdFromTheMdc() throws Exception {
        JsonNode record = encode(event(Level.INFO, "Reserva creada",
                Map.of("event", "reservation.created"),
                Map.of("correlationId", "audit-0000-0001", "actorRef", "3c6c5c25f4b6")));

        assertThat(record.get("correlationId").asText()).isEqualTo("audit-0000-0001");
        // El seudónimo del solicitante va en TODA línea del pedido, y no sólo
        // en la de acceso: es lo que permite responder «todo lo que hizo este
        // solicitante» sin que el log sea una lista de emails.
        assertThat(record.get("actorRef").asText()).isEqualTo("3c6c5c25f4b6");
    }

    @Test
    @DisplayName("la IP del cliente NO sale en cada línea, aunque esté en el MDC")
    void theClientIpIsExcludedFromTheMdcProvider() throws Exception {
        // Está en el MDC porque el adaptador de auditoría la lee de ahí, y es
        // dato personal: en cada línea es el mismo dato multiplicado por el
        // volumen del log, con la retención del log y no la de la auditoría.
        // Donde sí se escribe es en `event=http.request`, y la escribe
        // `RequestLogFilter` como campo explícito.
        JsonNode record = encode(event(Level.DEBUG, "Catálogo consultado",
                Map.of("event", "catalog.call"),
                Map.of("correlationId", "audit-0000-0001", "clientIp", "172.18.0.1")));

        assertThat(record.has("clientIp"))
                .withFailMessage("La IP del cliente volvió a salir en una línea que no es la de acceso: %s",
                        record)
                .isFalse();
    }

    @Test
    @DisplayName("el timestamp es UTC con milisegundos y no el offset local")
    void timestampIsUtc() throws Exception {
        // La muestra de la auditoría tenía el @timestamp con offset -03:00. Dos
        // instancias en zonas distintas producen líneas que no se pueden
        // ordenar entre sí, que es lo primero que se hace en una investigación.
        JsonNode record = encode(event(Level.INFO, "Reserva creada",
                Map.of("event", "reservation.created"), Map.of()));

        assertThat(record.get("@timestamp").asText())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    }

    @Test
    @DisplayName("cada dato es un campo, y el mensaje queda como texto fijo")
    void oneFieldPerDatum() throws Exception {
        JsonNode record = encode(event(Level.INFO, "Reserva creada",
                Map.of("event", "reservation.created",
                        "reservationId", 10241L,
                        "userId", 4471L,
                        "reservationVersion", 0,
                        "itinerary.origin", "EZE",
                        "itinerary.destination", "MAD",
                        "passengers", 2),
                Map.of("correlationId", "audit-0000-0001")));

        assertThat(record.get("reservationId").asLong()).isEqualTo(10241L);
        assertThat(record.get("userId").asLong()).isEqualTo(4471L);
        assertThat(record.get("itinerary.origin").asText()).isEqualTo("EZE");
        assertThat(record.get("passengers").asInt()).isEqualTo(2);

        // Lo que el §1.2 quiere decir con «message fijo, sin interpolar»: ni un
        // dígito ni un marcador adentro del texto. Es lo que permite que la
        // redacción cambie sin romper una consulta.
        assertThat(record.get("message").asText())
                .doesNotContain("{}")
                .doesNotMatch(".*\\d.*");
    }

    @Test
    @DisplayName("un salto de línea en un valor externo no fabrica un registro nuevo")
    void anExternalNewlineCannotForgeARecord() throws Exception {
        // El vector que LogSanitizer existe para cerrar, cerrado también por
        // construcción: en JSON el salto de línea es un carácter escapado
        // dentro de un campo y no un separador de registros.
        String hostile = "boom\n{\"level\":\"INFO\",\"message\":\"todo bien\"}";
        String encoded = encodeRaw(event(Level.WARN, "El catálogo respondió con error",
                Map.of("event", "catalog.call", "reason", hostile),
                Map.of("correlationId", "audit-0000-0001")));

        assertThat(encoded.strip().lines()).hasSize(1);
        assertThat(JSON.readTree(encoded).get("reason").asText()).contains("boom");
    }

    @Test
    @DisplayName("el stack trace sale en su propio campo y no parte el registro")
    void stackTraceIsItsOwnField() throws Exception {
        LoggingEvent event = event(Level.ERROR, "Error no controlado procesando el pedido",
                Map.of("event", "unhandled.error", "exception.class", "IllegalStateException"),
                Map.of("correlationId", "audit-0000-0001"));
        event.setThrowableProxy(new ThrowableProxy(new IllegalStateException("algo se rompió")));

        String encoded = encodeRaw(event);
        assertThat(encoded.strip().lines())
                .withFailMessage("Un stack trace multilínea tiene que ser UN registro, no N")
                .hasSize(1);
        assertThat(JSON.readTree(encoded).get("stack_trace").asText())
                .contains("IllegalStateException");
    }

    @ParameterizedTest(name = "declara el provider <{0}>")
    @ValueSource(strings = {"timestamp", "logLevel", "loggerName", "message", "threadName",
            "mdc", "keyValuePairs", "stackTrace"})
    @DisplayName("declara los providers uno por uno en lugar de heredar los defaults del encoder")
    void declaresEveryProviderExplicitly(String provider) {
        // Los defaults del encoder cambian entre versiones mayores de la
        // librería, y un campo que se renombra solo rompe cada consulta y cada
        // alerta escrita contra él sin que falle nada hasta el día del
        // incidente. La versión está fija en el pom por la misma razón.
        assertThat(xml).containsPattern("<" + provider + "\\s*[/>]");
    }

    @Test
    @DisplayName("hay un formato legible para desarrollo y JSON para cualquier otro entorno")
    void developmentProfileIsReadableAndEverythingElseIsJson() {
        assertThat(xml)
                .contains("<springProfile name=\"local,dev\">")
                .contains("<springProfile name=\"!local &amp; !dev\">")
                // El perfil por defecto —con el que corre esta suite— es JSON:
                // el esquema tiene que estar bajo prueba en cada `mvn verify` y
                // no sólo en producción.
                .contains("<property name=\"rootAppender\" value=\"json\"/>");
    }

    @Test
    @DisplayName("PageNotFound tiene un nivel explícito: un 405 no es un WARN nuestro")
    void thirdPartyLoggersHaveAnExplicitLevel() {
        // Hallazgo 33: `logging.level.com.edteam.reservations` no alcanza a los
        // loggers de terceros, y `o.s.web.servlet.PageNotFound` escribe un WARN
        // por cada 405 —o sea, por el sistema funcionando— que entra al panel
        // de «WARN por minuto» y lo contamina.
        assertThat(xml)
                .contains("<logger name=\"org.springframework.web.servlet.PageNotFound\" level=\"ERROR\"/>");
    }

    // ------------------------------------------------------------------
    // Andamiaje
    // ------------------------------------------------------------------

    private static JsonNode encode(ILoggingEvent event) throws Exception {
        return JSON.readTree(encodeRaw(event));
    }

    private static String encodeRaw(ILoggingEvent event) {
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    private static LoggingEvent event(Level level, String message,
                                      Map<String, Object> fields, Map<String, String> mdc) {
        LoggingEvent event = new LoggingEvent();
        event.setLoggerName("com.edteam.reservations.application.service.CreateReservationTransaction");
        event.setLevel(level);
        event.setMessage(message);
        event.setThreadName("http-nio-8080-exec-3");
        event.setTimeStamp(System.currentTimeMillis());
        fields.forEach((key, value) -> event.addKeyValuePair(new KeyValuePair(key, value)));
        event.setMDCPropertyMap(Map.copyOf(mdc));
        return event;
    }
}
