package com.edteam.reservations.infrastructure.logging;

import java.util.regex.Pattern;

/**
 * Cómo se escribe una excepción en el log sin convertirla en un canal de PII.
 *
 * <h2>El problema, con nombre y apellido</h2>
 * La auditoría lo reprodujo tres veces:
 *
 * <ul>
 *   <li>una {@code DataIntegrityViolationException} que no se reconoce se
 *       relanza tal cual, y el mensaje de PostgreSQL trae el SQL y el
 *       {@code Detail: Key (email)=(ana.perez@example.com) already exists};</li>
 *   <li>el consumidor escribía {@code e.toString()}, con el comentario de la
 *       línea de arriba prometiendo justo lo contrario;</li>
 *   <li>el mensaje de Jackson al fallar la deserialización incrusta un
 *       fragmento del cuerpo del proveedor, sin pasar por {@link LogSanitizer}.</li>
 * </ul>
 *
 * <p>El denominador común es que el mensaje de una excepción es <b>texto que
 * eligió otro</b>: la base, una librería o un tercero. Truncarlo no alcanza
 * —el dato está en los primeros caracteres— y el {@code ShortenedThrowableConverter}
 * del encoder tampoco, porque acota el stack y el mensaje es la primera línea.
 *
 * <h2>La regla</h2>
 * En el borde se escriben dos campos: {@link #classOf(Throwable)}, que es un
 * nombre de clase y no lleva ningún valor, y {@link #reasonOf(Throwable)}, que
 * es el mensaje redactado y saneado. Lo que se redacta es lo que sabemos
 * reconocer y que no explica nada: emails, el {@code Detail: Key (…)=(…)} de
 * PostgreSQL, y cualquier cadena con pinta de token.
 *
 * <p>No pretende ser exhaustivo, y por eso el gate de PII sobre la salida de la
 * suite es parte del entregable: una lista de patrones sostenida por disciplina
 * vuelve a fallar en cuanto aparece un formato nuevo. Lo que esta clase compra
 * es que el camino por defecto —el que toma quien escribe la línea sin pensar
 * en esto— sea el seguro.
 */
public final class Throwables {

    /** Largo del motivo en el log. Alcanza para diagnosticar y no paga por el resto. */
    private static final int MAX_REASON = 256;

    private static final String REDACTED = "redactado";

    /** Email. El patrón es el mismo que usa el gate de PII de la suite. */
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /**
     * El {@code Detail: Key (columna)=(valor) …} de PostgreSQL. Es la forma
     * exacta en la que una violación de unique publica el valor de la columna,
     * y las columnas {@code email}, {@code nombre}, {@code apellido} y
     * {@code fecha_nacimiento} están en claro en el modelo.
     */
    private static final Pattern PG_DETAIL_KEY =
            Pattern.compile("(?i)(\\bDetail:\\s*)?\\bKey\\s*\\([^)]*\\)\\s*=\\s*\\([^)]*\\)");

    /** Un JWT, o cualquier fragmento que se le parezca. */
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{10,}");

    private Throwables() {
    }

    /**
     * El nombre simple de la clase. Es lo que responde «¿qué salió mal?» sin
     * llevar un solo valor adentro.
     */
    public static String classOf(Throwable e) {
        return e == null ? "<ninguna>" : e.getClass().getSimpleName();
    }

    /**
     * El nombre de la clase de la causa raíz, que suele ser el dato útil: una
     * {@code TransactionSystemException} no dice nada, su causa sí.
     */
    public static String rootClassOf(Throwable e) {
        if (e == null) {
            return "<ninguna>";
        }
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    /**
     * El mensaje, redactado y saneado. Nunca {@code toString()}: ése antepone
     * el nombre completo de la clase y sigue llevando el mensaje entero.
     */
    public static String reasonOf(Throwable e) {
        return e == null ? "<sin motivo>" : redact(e.getMessage());
    }

    /**
     * Redacta y sanea un texto de origen ajeno antes de escribirlo.
     *
     * <p>Público porque el mismo tratamiento hace falta en los tres lugares
     * donde entra texto que no escribimos nosotros: el mensaje de una
     * excepción, el cuerpo de error de un tercero y el valor crudo de una
     * entrada de cache, que es un almacén compartido y alcanzable por red.
     */
    public static String redact(String text) {
        if (text == null || text.isBlank()) {
            return "<sin motivo>";
        }
        // Se saca la construcción ENTERA, incluido el prefijo `Detail:`, y no
        // sólo el valor: un `Detail: Key (…)=(«redactado»)` sigue siendo el
        // formato reconocible de una fuga y el gate de PII lo marcaría igual,
        // con razón. Lo que queda dice que hubo una violación de integridad,
        // que es el dato operativo; cuál fue el valor está en la base.
        String redacted = PG_DETAIL_KEY.matcher(text).replaceAll("«detalle de integridad " + REDACTED + "»");
        redacted = EMAIL.matcher(redacted).replaceAll("«email " + REDACTED + "»");
        redacted = JWT.matcher(redacted).replaceAll("«token " + REDACTED + "»");
        return LogSanitizer.sanitize(redacted, MAX_REASON);
    }
}
