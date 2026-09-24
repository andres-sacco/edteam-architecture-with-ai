package com.edteam.reservations.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Arma el decodificador del token Bearer según cómo esté configurado el
 * entorno, y se niega a arrancar si la configuración no cierra.
 *
 * <p>La regla es una sola: <b>ninguna combinación de propiedades puede
 * producir una aplicación que arranque sin validar tokens</b>. No hay un modo
 * «sin seguridad» ni un fallback silencioso; si falta configuración, el
 * contexto no se levanta. Es la diferencia entre una protección y una
 * protección que alguien apagó sin querer con una variable de entorno vacía.
 *
 * <h2>Validaciones</h2>
 * Firma, expiración y {@code nbf} siempre. Emisor y audiencia, si están
 * configurados —y en cualquier entorno real lo están—. La audiencia es la que
 * evita que un token que el mismo IdP emitió para otro servicio sirva acá: sin
 * ella, cualquier aplicación del mismo emisor se vuelve un camino de entrada.
 */
public final class JwtDecoderFactory {

    private static final Logger log = LoggerFactory.getLogger(JwtDecoderFactory.class);

    /** HMAC-SHA256 exige al menos 256 bits de clave; por debajo Nimbus falla al firmar. */
    private static final int MIN_SECRET_BYTES = 32;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private JwtDecoderFactory() {
    }

    public static JwtDecoder create(SecurityProperties.Jwt properties) {
        Objects.requireNonNull(properties, "La configuración del token es obligatoria");

        if (properties.hasJwkSetUri()) {
            log.info("Tokens Bearer: se validan contra el JWKS de {}", properties.jwkSetUri());
            return withValidators(NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build(), properties);
        }
        if (!properties.devTokensEnabled()) {
            throw new IllegalStateException("""
                    Seguridad mal configurada: no hay 'reservations.security.jwt.jwk-set-uri' y los tokens \
                    de desarrollo están apagados. La aplicación no arranca sin una forma de validar tokens.""");
        }
        return devDecoder(properties);
    }

    /**
     * Decodificador HMAC de desarrollo.
     *
     * <p>El aviso es grande a propósito y sale en cada arranque: una clave
     * simétrica publicada en el repositorio significa que cualquiera que lea
     * el código puede emitirse un token, y eso tiene que ser imposible de
     * confundir con un entorno configurado.
     */
    private static JwtDecoder devDecoder(SecurityProperties.Jwt properties) {
        if (!properties.hasDevSecret()) {
            throw new IllegalStateException("""
                    Seguridad mal configurada: los tokens de desarrollo están activos pero no hay \
                    'reservations.security.jwt.dev-secret'.""");
        }
        byte[] key = properties.devSecret().getBytes(StandardCharsets.UTF_8);
        if (key.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "La clave de desarrollo necesita al menos %d bytes y tiene %d"
                            .formatted(MIN_SECRET_BYTES, key.length));
        }
        // Ver el comentario equivalente en PiiCipher: un evento, un registro.
        log.atWarn()
                .addKeyValue("event", "startup.wiring")
                .addKeyValue("component", "jwt-decoder")
                .addKeyValue("jwt.tokens.source", "dev")
                .addKeyValue("remediation", "reservations.security.jwt.jwk-set-uri + dev-tokens=false")
                .log("Se aceptan tokens firmados con una clave simétrica conocida");
        return withValidators(
                NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, HMAC_ALGORITHM)).build(), properties);
    }

    private static NimbusJwtDecoder withValidators(NimbusJwtDecoder decoder, SecurityProperties.Jwt properties) {
        Collection<OAuth2TokenValidator<Jwt>> validators =
                new ArrayList<>(List.of(new JwtTimestampValidator()));

        if (properties.hasIssuer()) {
            validators.add(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        }
        if (properties.hasAudience()) {
            validators.add(new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                    audiences -> audiences != null && audiences.contains(properties.audience())));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    /**
     * Verifica que el emisor configurado sea alcanzable por HTTPS. Un JWKS por
     * {@code http://} deja la validación de firma a merced de cualquiera que
     * esté en el camino: bastaría con servir otro juego de claves.
     */
    public static void requireSecureJwkSetUri(SecurityProperties.Jwt properties) {
        if (properties.hasJwkSetUri() && !properties.jwkSetUri().startsWith("https://")
                && !properties.jwkSetUri().startsWith("http://localhost")) {
            throw new IllegalStateException(
                    "El JWKS tiene que servirse por HTTPS: '%s' no lo hace".formatted(properties.jwkSetUri()));
        }
    }
}
