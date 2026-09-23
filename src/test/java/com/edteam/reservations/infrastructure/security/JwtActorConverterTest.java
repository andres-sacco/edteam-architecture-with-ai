package com.edteam.reservations.infrastructure.security;

import com.edteam.reservations.domain.access.Actor;
import com.edteam.reservations.domain.access.ActorRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La frontera entre el token y el dominio.
 *
 * <p>Es el único lugar donde se decide qué claim significa qué, así que es el
 * único lugar donde hay que probarlo. Lo que más importa acá no es el camino
 * feliz: es que un token incompleto o con un rol desconocido <b>no</b>
 * produzca un actor a medias ni un privilegio que nadie otorgó.
 */
@DisplayName("Conversión del token al actor de dominio")
class JwtActorConverterTest {

    private final JwtActorConverter converter = new JwtActorConverter();

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private Actor actorOf(Map<String, Object> claims) {
        return (Actor) converter.convert(jwt(claims)).getPrincipal();
    }

    @Test
    @DisplayName("arma el actor con el email, el nombre y el apellido del token")
    void buildsTheActor() {
        Actor actor = actorOf(Map.of(
                "email", "ana.perez@example.com",
                "given_name", "Ana",
                "family_name", "Pérez"));

        assertThat(actor.email().value()).isEqualTo("ana.perez@example.com");
        assertThat(actor.firstName()).isEqualTo("Ana");
        assertThat(actor.lastName()).isEqualTo("Pérez");
    }

    @Test
    @DisplayName("sin claim de roles, el actor es un titular: el privilegio nunca se deduce")
    void defaultsToTheSmallestRole() {
        Actor actor = actorOf(Map.of(
                "email", "ana.perez@example.com", "given_name", "Ana", "family_name", "Pérez"));

        assertThat(actor.roles()).containsExactly(ActorRole.CUSTOMER);
        assertThat(actor.actsOnBehalfOfOthers()).isFalse();
    }

    @Test
    @DisplayName("reconoce backoffice y partner, en la lista de roles o en el scope de OAuth2")
    void recognisesPrivilegedRoles() {
        assertThat(actorOf(Map.of("email", "s@example.com", "given_name", "S", "family_name", "R",
                "roles", List.of("backoffice"))).actsOnBehalfOfOthers()).isTrue();
        assertThat(actorOf(Map.of("email", "s@example.com", "given_name", "S", "family_name", "R",
                "roles", List.of("PARTNER"))).actsOnBehalfOfOthers()).isTrue();
        assertThat(actorOf(Map.of("email", "s@example.com", "given_name", "S", "family_name", "R",
                "scope", "openid backoffice")).actsOnBehalfOfOthers()).isTrue();
    }

    @Test
    @DisplayName("un rol que no conocemos no otorga nada")
    void ignoresUnknownRoles() {
        Actor actor = actorOf(Map.of("email", "s@example.com", "given_name", "S", "family_name", "R",
                "roles", List.of("superadmin", "root", "admin")));

        assertThat(actor.roles()).containsExactly(ActorRole.CUSTOMER);
    }

    @Test
    @DisplayName("usa el 'sub' como identidad sólo si es un email")
    void fallsBackToSubject() {
        Actor actor = actorOf(Map.of("sub", "ana.perez@example.com", "given_name", "Ana", "family_name", "Pérez"));

        assertThat(actor.email().value()).isEqualTo("ana.perez@example.com");
    }

    @Test
    @DisplayName("un 'sub' opaco sin claim de email es un token inválido, no un actor anónimo")
    void rejectsAnOpaqueSubjectWithoutEmail() {
        assertThatThrownBy(() -> actorOf(Map.of(
                "sub", "a1b2c3d4-0000-0000-0000-000000000000", "given_name", "Ana", "family_name", "Pérez")))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    @DisplayName("sin nombre o sin apellido se rechaza el token en vez de inventar el dato")
    void rejectsIncompleteTokens() {
        assertThatThrownBy(() -> actorOf(Map.of("email", "a@example.com", "family_name", "Pérez")))
                .isInstanceOf(InvalidBearerTokenException.class)
                .hasMessageContaining("given_name");
        assertThatThrownBy(() -> actorOf(Map.of("email", "a@example.com", "given_name", "Ana")))
                .isInstanceOf(InvalidBearerTokenException.class)
                .hasMessageContaining("family_name");
    }

    @Test
    @DisplayName("el error nombra el claim y nunca su valor")
    void doesNotEchoTheClaimValue() {
        // La causa no se adjunta a propósito: OAuth2AuthenticationException
        // adopta el mensaje del cause como propio, y el del dominio nombra el
        // email que no validó. Un detalle de error termina en consolas, en
        // capturas de pantalla y en tickets de soporte.
        assertThatThrownBy(() -> actorOf(Map.of(
                "email", "no-es-un-email", "given_name", "Ana", "family_name", "Pérez")))
                .isInstanceOf(InvalidBearerTokenException.class)
                .hasMessageContaining("email")
                .hasMessageNotContaining("no-es-un-email")
                .hasNoCause();
    }

    @Test
    @DisplayName("las authorities salen de los roles, para que el borde pueda seguir usándolas")
    void exposesAuthorities() {
        var authentication = converter.convert(jwt(Map.of(
                "email", "s@example.com", "given_name", "S", "family_name", "R",
                "roles", List.of("backoffice"))));

        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_BACKOFFICE");
        assertThat(authentication.getName()).isEqualTo("s@example.com");
    }
}
