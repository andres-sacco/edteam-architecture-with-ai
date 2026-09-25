package com.edteam.reservations.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.edteam.reservations.domain.access.Actor;
import com.edteam.reservations.domain.access.ActorRole;
import com.edteam.reservations.domain.model.Email;
import com.edteam.reservations.infrastructure.security.ActorAuthenticationToken;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Identidades para los tests del borde HTTP.
 *
 * <p>Dos formas, y cada una prueba algo distinto:
 *
 * <ul>
 *   <li>{@link #as(Actor)} inyecta la autenticación directamente. Es lo que
 *       usan los tests de slice: lo que se está probando ahí es la
 *       <em>autorización</em> —qué pasa cuando el que pide no es el dueño— y
 *       no la validación del token, que tiene sus propios tests.</li>
 *   <li>{@link #bearer(Actor)} firma un JWT de verdad con la clave de
 *       desarrollo. Es lo que usa el test de integración, donde sí importa que
 *       la cadena completa —firma, claims, conversión a {@code Actor}—
 *       funcione de punta a punta.</li>
 * </ul>
 *
 * <p>No se usa el {@code jwt()} de {@code spring-security-test}: ése deja un
 * {@code JwtAuthenticationToken} cuyo principal es el {@code Jwt}, y este
 * sistema espera un {@code Actor}. Reproducirlo acá sería probar el
 * post-processor en lugar del conversor real.
 */
public final class SecurityTestSupport {

    /** La misma de {@code application.yml}: un placeholder, no un secreto. */
    public static final String DEV_SECRET = "dev-only-hmac-key-no-usar-fuera-de-local-0123456789";

    private SecurityTestSupport() {}

    /** Autenticación ya resuelta, para los tests de slice. */
    public static RequestPostProcessor as(Actor actor) {
        return authentication(new ActorAuthenticationToken(actor, null));
    }

    public static RequestPostProcessor asOwner() {
        return as(TestFixtures.owner());
    }

    public static RequestPostProcessor asStranger() {
        return as(TestFixtures.stranger());
    }

    public static RequestPostProcessor asBackoffice() {
        return as(TestFixtures.backoffice());
    }

    /** Token HMAC firmado de verdad, para los tests de integración. */
    public static String bearer(Actor actor) {
        return "Bearer " + token(actor, Instant.now().plusSeconds(300));
    }

    /** Token ya vencido: sirve para probar que la expiración se verifica. */
    public static String expiredBearer(Actor actor) {
        return "Bearer " + token(actor, Instant.now().minusSeconds(60));
    }

    public static String token(Actor actor, Instant expiresAt) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(actor.email().value())
                    .claim("email", actor.email().value())
                    .claim("given_name", actor.firstName())
                    .claim("family_name", actor.lastName())
                    .claim("roles", actor.roles().stream().map(ActorRole::name).toList())
                    .issueTime(Date.from(Instant.now().minusSeconds(5)))
                    .expirationTime(Date.from(expiresAt))
                    .build();

            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(DEV_SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo firmar el token de prueba", e);
        }
    }

    /** Token firmado con OTRA clave: tiene que rechazarse. */
    public static String forgedBearer(Actor actor) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(actor.email().value())
                    .claim("email", actor.email().value())
                    .claim("given_name", actor.firstName())
                    .claim("family_name", actor.lastName())
                    .claim("roles", List.of(ActorRole.BACKOFFICE.name()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build();

            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner("otra-clave-cualquiera-de-32-bytes-o-mas-0123".getBytes(StandardCharsets.UTF_8)));
            return "Bearer " + jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo firmar el token falsificado", e);
        }
    }

    /** Actor de conveniencia con un email arbitrario. */
    public static Actor customer(String email) {
        return Actor.customer(Email.of(email), "Nombre", "Apellido");
    }
}
