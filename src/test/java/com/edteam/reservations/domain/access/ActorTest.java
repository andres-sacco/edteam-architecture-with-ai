package com.edteam.reservations.domain.access;

import com.edteam.reservations.domain.exception.InvalidUserException;
import com.edteam.reservations.domain.model.Email;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Actor")
class ActorTest {

    private static final Email EMAIL = Email.of("ana.perez@example.com");

    @Test
    @DisplayName("el titular no actúa por cuenta de nadie más")
    void customerActsOnlyForThemselves() {
        Actor actor = Actor.customer(EMAIL, "Ana", "Pérez");

        assertThat(actor.hasRole(ActorRole.CUSTOMER)).isTrue();
        assertThat(actor.actsOnBehalfOfOthers()).isFalse();
    }

    @Test
    @DisplayName("backoffice sí")
    void backofficeActsForOthers() {
        assertThat(Actor.backoffice(EMAIL, "Ana", "Pérez").actsOnBehalfOfOthers()).isTrue();
    }

    @Test
    @DisplayName("un actor sin roles no existe: el privilegio se otorga, no se deduce")
    void rejectsAnActorWithoutRoles() {
        assertThatThrownBy(() -> new Actor(EMAIL, "Ana", "Pérez", Set.of()))
                .isInstanceOf(InvalidUserException.class);
    }

    @Test
    @DisplayName("exige nombre y apellido: son los datos con los que se da de alta al usuario")
    void requiresAName() {
        assertThatThrownBy(() -> Actor.customer(EMAIL, "  ", "Pérez"))
                .isInstanceOf(InvalidUserException.class)
                .hasMessageContaining("nombre");
        assertThatThrownBy(() -> Actor.customer(EMAIL, "Ana", null))
                .isInstanceOf(InvalidUserException.class)
                .hasMessageContaining("apellido");
    }

    @Test
    @DisplayName("exige identidad")
    void requiresAnEmail() {
        assertThatThrownBy(() -> Actor.customer(null, "Ana", "Pérez"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("los roles son inmutables desde afuera")
    void rolesAreDefensivelyCopied() {
        Set<ActorRole> mutable = new java.util.HashSet<>(Set.of(ActorRole.CUSTOMER));
        Actor actor = new Actor(EMAIL, "Ana", "Pérez", mutable);

        mutable.add(ActorRole.BACKOFFICE);

        assertThat(actor.actsOnBehalfOfOthers())
                .as("escalar privilegios mutando el conjunto que se pasó al constructor")
                .isFalse();
    }
}
