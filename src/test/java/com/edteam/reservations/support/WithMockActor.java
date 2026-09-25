package com.edteam.reservations.support;

import com.edteam.reservations.domain.access.ActorRole;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.test.context.support.WithSecurityContext;

/**
 * Corre el test con un {@code Actor} autenticado.
 *
 * <p>Equivale a {@code @WithMockUser}, pero produce la autenticación que este
 * sistema usa de verdad —un {@code ActorAuthenticationToken} con el modelo de
 * dominio adentro— en lugar de un usuario genérico de Spring Security.
 * Escribirla así evita que los tests del borde tengan que construir un JWT
 * para probar autorización, que es otra cosa: la validación del token tiene su
 * propio test, y el de integración la ejercita de punta a punta.
 *
 * <p>Los defaults son el titular de las reservas de {@link TestFixtures}, que
 * es el caso normal. Un test que necesita otra identidad la declara.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Inherited
@WithSecurityContext(factory = WithMockActorSecurityContextFactory.class)
public @interface WithMockActor {

    String email() default TestFixtures.USER_EMAIL;

    String firstName() default "Ana";

    String lastName() default "Pérez";

    ActorRole[] roles() default {ActorRole.CUSTOMER};
}
