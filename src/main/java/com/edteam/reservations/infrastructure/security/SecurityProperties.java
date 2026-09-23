package com.edteam.reservations.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Configuración de la capa de seguridad.
 *
 * <p>Ningún valor sensible tiene default en el código ni en el YAML: la clave
 * de firma de desarrollo es un placeholder publicado a propósito
 * ({@link Jwt#DEV_SECRET_MARKER}) y la aplicación lo <b>rechaza</b> apenas se
 * apagan los tokens de desarrollo. Todo lo demás —emisor, audiencia, orígenes
 * permitidos, cuotas— es configuración por entorno, no secreto.
 *
 * @param jwt       validación del token Bearer
 * @param cors      orígenes admitidos para los frontends
 * @param rateLimit cuotas por identidad y por IP
 */
@ConfigurationProperties(prefix = "reservations.security")
public record SecurityProperties(Jwt jwt, Cors cors, RateLimit rateLimit) {

    public SecurityProperties {
        if (jwt == null) {
            jwt = new Jwt(null, null, null, null, null);
        }
        if (cors == null) {
            cors = new Cors(null, null);
        }
        if (rateLimit == null) {
            rateLimit = new RateLimit(null, null, null, null);
        }
    }

    /**
     * Validación del token.
     *
     * <p>Dos modos, y el que manda es el primero que esté configurado:
     * <ol>
     *   <li><b>{@code jwk-set-uri}</b> — el modo de cualquier entorno real. Las
     *       claves las publica el emisor, rotan sin que nosotros toquemos nada
     *       y este servicio nunca ve una clave privada.</li>
     *   <li><b>{@code dev-secret}</b> — HMAC simétrico, para levantar la
     *       aplicación y correr los tests sin un IdP. Que este modo exista es
     *       lo que permite que el build no dependa de un servicio externo; que
     *       esté apagado por configuración en cualquier otro entorno es lo que
     *       hace que no sea un agujero.</li>
     * </ol>
     *
     * @param jwkSetUri     JWKS del emisor; vacío en local
     * @param issuer        emisor esperado en el claim {@code iss}
     * @param audience      audiencia esperada en {@code aud}: que un token emitido
     *                      para otro servicio del mismo IdP no sirva acá
     * @param devTokens     si se aceptan tokens firmados con {@code devSecret}
     * @param devSecret     clave HMAC de desarrollo; nunca un secreto real
     */
    public record Jwt(String jwkSetUri, String issuer, String audience, Boolean devTokens, String devSecret) {

        /**
         * Marca del placeholder de desarrollo. La aplicación se niega a arrancar
         * con este valor si los tokens de desarrollo están apagados, así que no
         * puede llegar a producción por olvido.
         */
        public static final String DEV_SECRET_MARKER = "dev-only";

        public Jwt {
            if (devTokens == null) {
                devTokens = Boolean.TRUE;
            }
        }

        public boolean hasJwkSetUri() {
            return jwkSetUri != null && !jwkSetUri.isBlank();
        }

        public boolean devTokensEnabled() {
            return Boolean.TRUE.equals(devTokens);
        }

        public boolean hasDevSecret() {
            return devSecret != null && !devSecret.isBlank();
        }

        public boolean usesPublishedDevSecret() {
            return hasDevSecret() && devSecret.contains(DEV_SECRET_MARKER);
        }

        public boolean hasIssuer() {
            return issuer != null && !issuer.isBlank();
        }

        public boolean hasAudience() {
            return audience != null && !audience.isBlank();
        }
    }

    /**
     * CORS.
     *
     * <p>La lista es explícita por entorno y nunca {@code *}: la API se
     * consume desde varios frontends, y la presión de «que ande el de mobile»
     * es exactamente la que termina en un {@code @CrossOrigin("*")} suelto en
     * un controller. Acá la decisión está en configuración, en un solo lugar y
     * revisable en un diff.
     *
     * @param allowedOrigins orígenes admitidos; vacío deja CORS apagado
     * @param maxAge         cuánto puede cachear el navegador el preflight
     */
    public record Cors(List<String> allowedOrigins, Duration maxAge) {

        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
            if (maxAge == null) {
                maxAge = Duration.ofMinutes(30);
            }
        }

        public boolean enabled() {
            return !allowedOrigins.isEmpty();
        }
    }

    /**
     * Cuotas del borde.
     *
     * <p>El lugar correcto para esto es el gateway, no el proceso: un filtro
     * en la aplicación ya gastó el hilo, la conexión y el parseo del pedido
     * que está por rechazar, y cada instancia lleva su propia cuenta. Existe
     * igual porque es la última línea: si el gateway se cae, se
     * desconfigura o alguien alcanza el puerto por dentro de la red, esto
     * sigue ahí. Las cuotas están dimensionadas para que no le peguen a un
     * cliente legítimo y sí a un scraper.
     *
     * @param enabled      permite apagarlo cuando el gateway ya lo hace
     * @param window       ventana de la cuota
     * @param reads        lecturas por ventana y por identidad
     * @param writes       escrituras por ventana y por identidad, más caras
     */
    public record RateLimit(Boolean enabled, Duration window, Integer reads, Integer writes) {

        public RateLimit {
            if (enabled == null) {
                enabled = Boolean.TRUE;
            }
            if (window == null) {
                window = Duration.ofMinutes(1);
            }
            if (reads == null || reads < 1) {
                reads = 120;
            }
            if (writes == null || writes < 1) {
                writes = 20;
            }
        }

        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }
    }
}
