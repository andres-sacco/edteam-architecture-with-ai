package com.edteam.reservations.infrastructure.security;

import com.edteam.reservations.domain.access.Actor;
import com.edteam.reservations.domain.access.ActorRole;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * La autenticación de Spring Security con un {@link Actor} de dominio adentro.
 *
 * <p>Es la única pieza que une los dos mundos, y está de este lado a
 * propósito: el {@code principal} que ven los adaptadores y los casos de uso
 * es el {@code Actor}, no un {@code Jwt}. Si el principal fuera el token, cada
 * capa que quisiera saber quién pide algo tendría que leer claims, y la
 * decisión de qué claim significa qué quedaría repartida por todo el código en
 * vez de estar en {@link JwtActorConverter}.
 *
 * <p>Las authorities se derivan de los roles para que los mecanismos de Spring
 * —{@code hasRole}, {@code @PreAuthorize}— sigan funcionando en el borde. La
 * autorización que importa, la de recurso, no las usa: la decide el dominio
 * con el {@code Actor}.
 */
public final class ActorAuthenticationToken extends AbstractAuthenticationToken {

    private static final String ROLE_PREFIX = "ROLE_";

    private final Actor actor;
    private final transient Jwt token;

    public ActorAuthenticationToken(Actor actor, Jwt token) {
        super(authoritiesOf(actor));
        this.actor = Objects.requireNonNull(actor, "El actor es obligatorio");
        this.token = token;
        setAuthenticated(true);
    }

    private static Set<GrantedAuthority> authoritiesOf(Actor actor) {
        Objects.requireNonNull(actor, "El actor es obligatorio");
        return actor.roles().stream()
                .map(ActorRole::name)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** El principal es el modelo de dominio: es lo que reciben los casos de uso. */
    @Override
    public Actor getPrincipal() {
        return actor;
    }

    /** El token validado. No se expone hacia adentro: sólo lo usa el borde. */
    @Override
    public Jwt getCredentials() {
        return token;
    }

    @Override
    public String getName() {
        return actor.email().value();
    }
}
