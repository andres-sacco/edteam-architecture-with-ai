package com.edteam.reservations.infrastructure.security;

import com.edteam.reservations.domain.access.Actor;
import com.edteam.reservations.domain.access.ActorRole;
import com.edteam.reservations.domain.exception.InvalidUserException;
import com.edteam.reservations.domain.model.Email;
import com.edteam.reservations.infrastructure.logging.ActorRef;
import com.edteam.reservations.infrastructure.logging.LogFields;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Traduce el token validado al {@link Actor} del dominio.
 *
 * <p>Es la frontera: acá adentro se habla de claims, y de acá para adentro se
 * habla de un actor con un email y unos roles. Todo lo que dependa del emisor
 * —cómo se llama el claim del email, si los roles vienen en {@code roles} o en
 * {@code scope}— vive únicamente en esta clase, y cambiar de proveedor de
 * identidad es cambiarla a ella.
 *
 * <h2>Un token incompleto es un token inválido</h2>
 * Si falta el email, o el nombre, o el apellido, no se construye un actor
 * parcial ni se completa con valores inventados: se rechaza con 401. Un actor
 * a medias terminaría dando de alta un usuario con datos fabricados —que es
 * exactamente lo que se quiso sacar del cuerpo del pedido— y, peor, haría que
 * la identidad con la que se autoriza dependa de un default nuestro y no de lo
 * que el emisor afirmó.
 *
 * <h2>Por qué el rol por defecto es el más chico</h2>
 * Un token sin claim de roles es un titular. Nunca backoffice: el privilegio
 * se otorga explícitamente o no existe.
 *
 * <h2>Los mensajes de rechazo van en ASCII</h2>
 * No es descuido. La descripción de un {@code InvalidBearerTokenException}
 * termina en el {@code error_description} del header
 * {@code WWW-Authenticate}, y la RFC 6750 restringe ese campo a ASCII
 * imprimible sin comillas ni barras. Spring lo verifica: una descripción con
 * un acento se descarta entera y se reemplaza por «Invalid token», con lo que
 * el log del servidor pierde el único dato útil. El detalle en castellano, con
 * acentos y todo, va al log por separado.
 */
public class JwtActorConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Logger log = LoggerFactory.getLogger(JwtActorConverter.class);

    /** Identidad. Se prefiere {@code email} y se cae a {@code sub} sólo si es un email. */
    static final String EMAIL_CLAIM = "email";

    static final String SUBJECT_CLAIM = "sub";

    static final String GIVEN_NAME_CLAIM = "given_name";

    static final String FAMILY_NAME_CLAIM = "family_name";

    /** Roles de negocio. Se acepta la lista propia del IdP y el {@code scope} de OAuth2. */
    static final String ROLES_CLAIM = "roles";

    static final String SCOPE_CLAIM = "scope";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Email email = emailOf(jwt);
        String firstName = requiredClaim(jwt, GIVEN_NAME_CLAIM);
        String lastName = requiredClaim(jwt, FAMILY_NAME_CLAIM);

        try {
            ActorAuthenticationToken token =
                    new ActorAuthenticationToken(new Actor(email, firstName, lastName, rolesOf(jwt)), jwt);
            // El seudónimo del solicitante entra al MDC ACÁ y no en el log de
            // acceso, y el motivo es de orden de filtros: el log de acceso
            // corre por FUERA de la cadena de seguridad, así que para cuando
            // recupera el control el SecurityContext ya está limpio. Lo que
            // sobrevive es el MDC, que lo limpia CorrelationIdFilter, que es
            // todavía más externo.
            //
            // Es el seudónimo y nunca el email: el log sale del perímetro hacia
            // un sistema indexado con otra retención.
            MDC.put(LogFields.ACTOR_REF, ActorRef.of(email.value()));
            return token;
        } catch (InvalidUserException e) {
            // Sin la causa: OAuth2AuthenticationException adopta el mensaje del
            // cause como propio, y ese mensaje nombra el valor del claim. El
            // detalle que sale al cliente no es el lugar para reflejarlo.
            log.debug("Token rechazado: el solicitante no es válido", e);
            throw new InvalidBearerTokenException("El token no alcanza para identificar al solicitante");
        }
    }

    private static Email emailOf(Jwt jwt) {
        String claimed = stringClaim(jwt, EMAIL_CLAIM);
        if (claimed == null) {
            claimed = stringClaim(jwt, SUBJECT_CLAIM);
        }
        if (claimed == null) {
            throw new InvalidBearerTokenException(
                    "El token no trae el claim '%s' ni un '%s' utilizable".formatted(EMAIL_CLAIM, SUBJECT_CLAIM));
        }
        try {
            return Email.of(claimed);
        } catch (InvalidUserException e) {
            // Se responde el nombre del claim, no su valor: el detalle de un
            // error no es el lugar donde reflejar una identidad. La causa no
            // se adjunta a propósito: OAuth2AuthenticationException adopta el
            // mensaje del cause, y ese mensaje sí lleva el valor.
            log.debug("Token rechazado: el claim de identidad no es un email", e);
            throw new InvalidBearerTokenException(
                    "El claim '%s' del token no tiene formato de email".formatted(EMAIL_CLAIM));
        }
    }

    private static String requiredClaim(Jwt jwt, String claim) {
        String value = stringClaim(jwt, claim);
        if (value == null) {
            throw new InvalidBearerTokenException("El token no trae el claim obligatorio '%s'".formatted(claim));
        }
        return value;
    }

    private static String stringClaim(Jwt jwt, String claim) {
        Object value = jwt.getClaim(claim);
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        return null;
    }

    /**
     * Roles del token. Se leen dos formas porque los emisores difieren, y
     * ninguna otorga nada que no esté escrito: un valor desconocido se ignora
     * en silencio en lugar de mapearse a algo «parecido».
     */
    private static Set<ActorRole> rolesOf(Jwt jwt) {
        Set<ActorRole> roles = new LinkedHashSet<>();
        for (String claimed : rawRoles(jwt)) {
            switch (claimed.toUpperCase(Locale.ROOT)) {
                case "BACKOFFICE", "PARTNER" -> roles.add(ActorRole.BACKOFFICE);
                case "CUSTOMER" -> roles.add(ActorRole.CUSTOMER);
                default -> {
                    /* rol que no conocemos: no otorga nada */
                }
            }
        }
        if (roles.isEmpty()) {
            roles.add(ActorRole.CUSTOMER);
        }
        return roles;
    }

    private static Collection<String> rawRoles(Jwt jwt) {
        Object claim = jwt.getClaim(ROLES_CLAIM);
        if (claim instanceof Collection<?> values) {
            return values.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        // 'scope' es un string separado por espacios: es lo que dice la RFC 6749.
        String scope = stringClaim(jwt, SCOPE_CLAIM);
        return scope == null ? List.of() : List.of(scope.split("\\s+"));
    }
}
