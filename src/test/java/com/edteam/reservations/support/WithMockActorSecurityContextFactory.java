package com.edteam.reservations.support;

import com.edteam.reservations.domain.access.Actor;
import com.edteam.reservations.domain.model.Email;
import com.edteam.reservations.infrastructure.security.ActorAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.util.Set;

/** Construye el contexto de seguridad que declara {@link WithMockActor}. */
public class WithMockActorSecurityContextFactory implements WithSecurityContextFactory<WithMockActor> {

    @Override
    public SecurityContext createSecurityContext(WithMockActor annotation) {
        Actor actor = new Actor(
                Email.of(annotation.email()),
                annotation.firstName(),
                annotation.lastName(),
                Set.of(annotation.roles()));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        // El token no se usa hacia adentro: el principal es el Actor, que es
        // lo único que ven el controller y los casos de uso.
        context.setAuthentication(new ActorAuthenticationToken(actor, null));
        return context;
    }
}
