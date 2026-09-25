package com.edteam.reservations.infrastructure.logging;

/**
 * Saneado de todo dato de origen externo antes de escribirlo en un log.
 *
 * <p>El vector es viejo y sigue vivo: en un log de texto el separador de
 * registros es el salto de línea, así que un valor externo que contiene
 * {@code \n} no agrega una línea a un registro, <b>agrega un registro</b>. Un
 * proveedor comprometido —o simplemente un proveedor que devuelve un HTML de
 * error— puede fabricar entradas que parezcan nuestras, y con eso tapar o
 * inventar evidencia justo en el lugar donde después se la va a buscar.
 *
 * <p>Se sanean dos cosas y se trunca:
 * <ul>
 *   <li>saltos de línea y retorno de carro, que rompen el registro;</li>
 *   <li>caracteres de control, que rompen el visor y las secuencias ANSI de
 *       una terminal;</li>
 *   <li>el largo, porque un cuerpo de error de 2 MB en un {@code WARN} es un
 *       problema de costo y de disponibilidad del propio sistema de logs.</li>
 * </ul>
 *
 * <p>Esto no reemplaza la solución de fondo, que es loguear en JSON
 * estructurado: ahí el salto de línea es un carácter escapado dentro de un
 * campo y deja de ser un separador. Pero el saneado sirve igual, porque el
 * visor de logs no es el único que lee estas líneas.
 */
public final class LogSanitizer {

    /** Suficiente para entender un error de un proveedor; el resto es ruido. */
    public static final int MAX_LENGTH = 512;

    private static final String ELLIPSIS = "…";
    private static final String EMPTY = "<vacío>";

    private LogSanitizer() {}

    public static String sanitize(String value) {
        return sanitize(value, MAX_LENGTH);
    }

    public static String sanitize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return EMPTY;
        }
        StringBuilder clean = new StringBuilder(Math.min(value.length(), maxLength));
        for (int i = 0; i < value.length() && clean.length() < maxLength; i++) {
            char c = value.charAt(i);
            clean.append(Character.isISOControl(c) ? ' ' : c);
        }
        return value.length() > maxLength ? clean + ELLIPSIS : clean.toString();
    }
}
