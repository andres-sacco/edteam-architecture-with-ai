package com.edteam.reservations.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La invariante del arranque: <b>ninguna combinación de propiedades puede
 * producir una aplicación que arranque sin validar tokens</b>.
 *
 * <p>Es lo que distingue una protección de una protección que alguien apagó
 * sin querer con una variable de entorno vacía.
 */
@DisplayName("Configuración del validador de tokens")
class JwtDecoderFactoryTest {

    private static final String DEV_SECRET = "dev-only-hmac-key-no-usar-fuera-de-local-0123456789";

    private static SecurityProperties.Jwt jwt(String jwkSetUri, Boolean devTokens, String devSecret) {
        return new SecurityProperties.Jwt(jwkSetUri, null, null, devTokens, devSecret);
    }

    @Test
    @DisplayName("con JWKS configurado, valida contra el emisor")
    void buildsARemoteDecoder() {
        // No se conecta al construirlo: las claves se piden en la primera
        // validación, así que esto no depende de la red.
        assertThat(JwtDecoderFactory.create(jwt("https://idp.example/.well-known/jwks.json", false, null)))
                .isNotNull();
    }

    @Test
    @DisplayName("sin JWKS y con los tokens de desarrollo apagados, la aplicación no arranca")
    void refusesToStartWithoutAWayToValidate() {
        assertThatThrownBy(() -> JwtDecoderFactory.create(jwt(null, false, DEV_SECRET)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwk-set-uri");
    }

    @Test
    @DisplayName("con los tokens de desarrollo activos y sin clave, tampoco")
    void refusesToStartWithoutADevSecret() {
        assertThatThrownBy(() -> JwtDecoderFactory.create(jwt(null, true, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev-secret");
    }

    @Test
    @DisplayName("rechaza una clave de desarrollo demasiado corta para HMAC-SHA256")
    void refusesAWeakDevSecret() {
        assertThatThrownBy(() -> JwtDecoderFactory.create(jwt(null, true, "corta")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("un JWKS por http:// no se acepta: quien esté en el camino serviría otras claves")
    void requiresHttpsForTheJwks() {
        assertThatThrownBy(() ->
                        JwtDecoderFactory.requireSecureJwkSetUri(jwt("http://idp.example/jwks.json", false, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    @DisplayName("localhost queda exento, para poder correr un IdP local")
    void allowsALocalIssuer() {
        JwtDecoderFactory.requireSecureJwkSetUri(jwt("http://localhost:9000/jwks.json", false, null));
    }

    @Test
    @DisplayName("la clave de desarrollo publicada se reconoce: es lo que permite avisar en el arranque")
    void detectsThePublishedDevelopmentSecret() {
        assertThat(jwt(null, true, DEV_SECRET).usesPublishedDevSecret()).isTrue();
        assertThat(jwt(null, true, "una-clave-propia-de-mas-de-32-bytes-0123").usesPublishedDevSecret())
                .isFalse();
    }
}
