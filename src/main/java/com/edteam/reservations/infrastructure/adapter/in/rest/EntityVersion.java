package com.edteam.reservations.infrastructure.adapter.in.rest;

import java.util.Objects;

/**
 * Traducción entre la versión del agregado y el {@code ETag} de HTTP.
 *
 * <p>Los casos de uso controlan la concurrencia con un número de versión
 * ({@code expectedVersion}); HTTP lo hace con {@code ETag} e {@code If-Match}.
 * Son el mismo mecanismo con dos vocabularios, y esta clase es el único lugar
 * donde se cruzan: así el cliente nunca ve el número de versión como un dato
 * del recurso, y los casos de uso nunca ven un header.
 *
 * <p>Se admite la forma débil ({@code W/"7"}) además de la fuerte: algunos
 * proxies reescriben el {@code ETag} al comprimir la respuesta, y rechazar lo
 * que ellos mismos generaron dejaría al cliente sin poder modificar nada.
 */
public final class EntityVersion {

    private EntityVersion() {
    }

    /** {@code ETag} para la versión indicada, en su forma fuerte. */
    public static String toETag(long version) {
        return "\"%d\"".formatted(version);
    }

    /**
     * Versión que representa el {@code If-Match} recibido.
     *
     * @throws InvalidIfMatchException si el header no tiene la forma de un
     *                                 {@code ETag} emitido por esta API
     */
    public static long parseIfMatch(String ifMatch) {
        Objects.requireNonNull(ifMatch, "El header If-Match es obligatorio");

        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2).trim();
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }

        try {
            long version = Long.parseLong(value);
            if (version < 0) {
                throw new InvalidIfMatchException(ifMatch);
            }
            return version;
        } catch (NumberFormatException e) {
            // Incluye el comodín '*': acá no tiene sentido, porque el cliente
            // tiene que declarar sobre qué versión trabajó.
            throw new InvalidIfMatchException(ifMatch);
        }
    }

    /**
     * {@code true} si el {@code If-None-Match} recibido incluye la versión
     * indicada, es decir: si el cliente ya tiene esta representación.
     *
     * <p>A diferencia de {@link #parseIfMatch(String)}, acá nada se rechaza. La
     * semántica de los dos headers es opuesta: {@code If-Match} protege una
     * escritura y sin él no hay forma de detectar una carrera, así que un valor
     * malformado tiene que ser un 400. {@code If-None-Match} sólo pregunta
     * "¿cambió?", y ante un valor que no se entiende la respuesta correcta es
     * la representación completa —un {@code 200}, no un error—: el cliente
     * pierde el ahorro y nada más.
     *
     * <p>Se admite la lista separada por comas y el comodín {@code *}, que
     * significa "cualquier representación existente": si llegamos hasta acá, la
     * reserva existe.
     */
    public static boolean matchesIfNoneMatch(String ifNoneMatch, long version) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }

        String expected = toETag(version);
        for (String candidate : ifNoneMatch.split(",")) {
            String value = candidate.trim();
            if ("*".equals(value)) {
                return true;
            }
            if (value.startsWith("W/")) {
                value = value.substring(2).trim();
            }
            if (expected.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** El {@code If-Match} recibido no es un {@code ETag} de esta API. */
    public static class InvalidIfMatchException extends RuntimeException {

        public InvalidIfMatchException(String received) {
            super("El header If-Match '%s' no es un ETag válido de esta API".formatted(received));
        }
    }
}
