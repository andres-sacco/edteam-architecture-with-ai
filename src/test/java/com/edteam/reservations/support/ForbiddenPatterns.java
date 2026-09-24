package com.edteam.reservations.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * El juego de patrones que <b>nunca</b> puede aparecer en la salida de la
 * suite.
 *
 * <p>Vive en un solo lugar porque lo usan tres cosas: el gate que grepea la
 * salida capturada de {@code mvn verify}, el test que provoca los caminos de
 * falla y afirma sobre los eventos capturados, y {@code Throwables}, que
 * redacta con los mismos patrones antes de escribir.
 *
 * <h2>Por qué una lista de patrones y no «tener cuidado»</h2>
 * La auditoría reprodujo la fuga: {@code INFO … El solicitante
 * bruno.diaz@example.com no puede listar las reservas de otro usuario}. La
 * regla existía y estaba escrita en el javadoc de {@code PiiMasker}, y se
 * rompió igual, en tres archivos distintos, incluido uno que la declaraba seis
 * líneas antes. Una regla sostenida por la disciplina de quien escribe la
 * línea vuelve a romperse; una sostenida por un test que falla el build, no.
 *
 * <h2>Lo que esta lista NO cubre, y hay que decirlo</h2>
 * Cubre lo que sabemos reconocer. Un nombre propio que no esté en las fixtures,
 * un número de documento con un formato nuevo o un dato personal en un campo
 * que todavía no existe pasan sin que nadie se entere. Es una red, no una
 * garantía: lo que de verdad cierra el vector es que el camino por defecto
 * —{@code Throwables.redact}, {@code PiiMasker}, {@code LogSanitizer}— sea el
 * seguro, y esto es lo que avisa cuando alguien lo saltea.
 */
public final class ForbiddenPatterns {

    /**
     * Patrón por nombre, para que el mensaje del fallo diga QUÉ se filtró y no
     * sólo que algo se filtró.
     */
    public static final Map<String, Pattern> PATTERNS = patterns();

    private ForbiddenPatterns() {
    }

    private static Map<String, Pattern> patterns() {
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        // Email en claro. Se excluye el dominio del seudónimo y los ejemplos
        // del contrato OpenAPI, que no son datos de nadie.
        patterns.put("email", Pattern.compile(
                "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));
        // Un JWT, o cualquier cosa con su forma.
        patterns.put("jwt", Pattern.compile("eyJ[A-Za-z0-9_-]{10,}"));
        // El header de autorización con su valor.
        patterns.put("authorization", Pattern.compile("(?i)Authorization\\s*[:=]\\s*Bearer\\s+\\S+"));
        // La marca de la clave de cifrado publicada en el repositorio. Que el
        // valor exista en el código fuente no lo hace menos grave en un log: un
        // log indexado con la clave adentro es la clave replicada.
        patterns.put("pii-key", Pattern.compile("ZGV2LW9ubHk"));
        // Los apellidos de las fixtures: son los únicos "datos de persona"
        // que la suite puede llegar a escribir, y el hallazgo 6 los tenía
        // dentro de mensajes de excepción del dominio.
        patterns.put("passenger-name", Pattern.compile("\\b(P[eé]rez|D[ií]az)\\b"));
        patterns.put("document", Pattern.compile("\\bDNI[- ]?\\d{7,9}\\b"));
        // El 'Detail: Key (columna)=(valor)' de PostgreSQL, que es la forma
        // exacta en la que una violación de unique publica el valor de una
        // columna en claro.
        patterns.put("pg-detail", Pattern.compile("(?i)\\bDetail:\\s*Key\\s*\\([^)]*\\)\\s*=\\s*\\([^)]+\\)"));
        return Map.copyOf(patterns);
    }

    /**
     * El primer patrón que matchea, si alguno matchea.
     *
     * @return {@code "nombre: fragmento"} para que el fallo sea accionable
     */
    public static Optional<String> firstMatch(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (Map.Entry<String, Pattern> entry : PATTERNS.entrySet()) {
            var matcher = entry.getValue().matcher(text);
            if (matcher.find()) {
                return Optional.of(entry.getKey() + ": '" + matcher.group() + "'");
            }
        }
        return Optional.empty();
    }
}
