package com.edteam.reservations.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

/**
 * Captura de los registros emitidos, para poder afirmar sobre ellos.
 *
 * <p>Es la herramienta que faltaba para que los hallazgos de la auditoría
 * fueran <b>verificables</b> y no una lectura. La auditoría midió «0
 * ocurrencias de correlationId en un arranque más 15 pruebas» grepeando la
 * salida a mano; esto convierte esa medición en una aserción que falla el
 * build.
 *
 * <p>Se engancha al logger raíz para ver también lo que escriben los hilos
 * virtuales del fan-out y el del contenedor de Rabbit, que es justamente donde
 * los hallazgos de correlación viven.
 *
 * <h2>Se afirma sobre los pares clave/valor, no sobre el texto</h2>
 * {@link Captured#field(String)} lee el {@code KeyValuePair} del evento, que es
 * lo que el encoder convierte en un campo JSON. Afirmar sobre el texto del
 * mensaje sería volver a la expresión regular por formato de línea que el
 * esquema vino a eliminar.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger root;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Level previousLevel;

    private LogCapture(Level level) {
        this.root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        this.previousLevel = root.getLevel();
        root.setLevel(level);
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
    }

    /** Captura desde {@code INFO}, que es lo que está encendido en producción. */
    public static LogCapture start() {
        return new LogCapture(Level.INFO);
    }

    /** Captura desde el nivel dado. {@code DEBUG} para los caminos de diagnóstico. */
    public static LogCapture startAt(Level level) {
        return new LogCapture(level);
    }

    public List<Captured> events() {
        return appender.list.stream().map(Captured::new).toList();
    }

    /** Los eventos con ese valor de {@code event}. */
    public List<Captured> withEvent(String event) {
        return events().stream()
                .filter(captured -> event.equals(captured.field("event")))
                .toList();
    }

    public boolean hasEvent(String event) {
        return !withEvent(event).isEmpty();
    }

    public List<Captured> matching(Predicate<Captured> predicate) {
        return events().stream().filter(predicate).toList();
    }

    public void clear() {
        appender.list.clear();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
        root.setLevel(previousLevel);
    }

    /** Un registro capturado, con sus campos accesibles por nombre. */
    public record Captured(ILoggingEvent event) {

        public String level() {
            return event.getLevel().toString();
        }

        public String message() {
            return event.getFormattedMessage();
        }

        public String logger() {
            return event.getLoggerName();
        }

        /** Valor del MDC, que es de donde salen {@code correlationId} y {@code traceId}. */
        public String mdc(String key) {
            Map<String, String> mdc = event.getMDCPropertyMap();
            return mdc == null ? null : mdc.get(key);
        }

        /** Valor de un par clave/valor, como texto. */
        public String field(String key) {
            Object value = rawField(key);
            return value == null ? null : String.valueOf(value);
        }

        public Object rawField(String key) {
            List<KeyValuePair> pairs = event.getKeyValuePairs();
            if (pairs == null) {
                return null;
            }
            return pairs.stream()
                    .filter(pair -> pair.key.equals(key))
                    .map(pair -> pair.value)
                    .findFirst()
                    .orElse(null);
        }

        public Map<String, Object> fields() {
            List<KeyValuePair> pairs = event.getKeyValuePairs();
            if (pairs == null) {
                return Map.of();
            }
            return pairs.stream()
                    .collect(Collectors.toMap(
                            pair -> pair.key, pair -> pair.value == null ? "" : pair.value, (first, second) -> first));
        }

        /**
         * Todo el texto que este registro produce: mensaje, cadena de causas y
         * los valores de los campos.
         *
         * <p>Es lo que el gate de PII tiene que revisar. Un email que llega por
         * el mensaje de la causa de la causa es exactamente el hallazgo 3, y
         * mirar sólo {@code getFormattedMessage()} no lo ve.
         */
        public String allText() {
            StringBuilder text = new StringBuilder(message());
            fields().forEach((key, value) ->
                    text.append(' ').append(key).append('=').append(value));
            Throwable cause = event.getThrowableProxy() == null ? null : throwableOf(event);
            while (cause != null) {
                text.append(' ').append(cause.getClass().getName()).append(": ").append(cause.getMessage());
                cause = cause.getCause() == cause ? null : cause.getCause();
            }
            return text.toString();
        }

        private static Throwable throwableOf(ILoggingEvent event) {
            return event.getThrowableProxy() instanceof ch.qos.logback.classic.spi.ThrowableProxy proxy
                    ? proxy.getThrowable()
                    : null;
        }
    }
}
