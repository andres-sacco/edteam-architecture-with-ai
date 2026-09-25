package com.edteam.reservations.domain.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.edteam.reservations.domain.model.Email;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.support.TestFixtures;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * La regla de negocio «una reserva pertenece a un único usuario», probada sin
 * levantar nada.
 *
 * <p>Que este test no necesite un contexto de Spring ni un token es el punto
 * de tener la política en el dominio: la autorización de recurso se puede
 * razonar y verificar con cinco objetos y ningún framework.
 */
@DisplayName("Política de acceso a las reservas")
class ReservationAccessPolicyTest {

    private final Reservation reservation = TestFixtures.storedReservation(0L);

    @Nested
    @DisplayName("Lectura y escritura de una reserva")
    class SingleReservation {

        @Test
        @DisplayName("el titular alcanza su reserva")
        void ownerCanReachTheirOwnReservation() {
            assertThat(ReservationAccessPolicy.canRead(TestFixtures.owner(), reservation))
                    .isTrue();
            assertThat(ReservationAccessPolicy.canWrite(TestFixtures.owner(), reservation))
                    .isTrue();
            assertThat(ReservationAccessPolicy.isOwner(TestFixtures.owner(), reservation))
                    .isTrue();
        }

        @Test
        @DisplayName("otro titular no la alcanza, ni para leer ni para escribir")
        void strangersCannot() {
            assertThat(ReservationAccessPolicy.canRead(TestFixtures.stranger(), reservation))
                    .isFalse();
            assertThat(ReservationAccessPolicy.canWrite(TestFixtures.stranger(), reservation))
                    .isFalse();
            assertThat(ReservationAccessPolicy.isOwner(TestFixtures.stranger(), reservation))
                    .isFalse();
        }

        @Test
        @DisplayName("backoffice alcanza reservas ajenas, y no por eso es su dueño")
        void backofficeReachesButDoesNotOwn() {
            assertThat(ReservationAccessPolicy.canRead(TestFixtures.backoffice(), reservation))
                    .isTrue();
            assertThat(ReservationAccessPolicy.canWrite(TestFixtures.backoffice(), reservation))
                    .isTrue();
            assertThat(ReservationAccessPolicy.isOwner(TestFixtures.backoffice(), reservation))
                    .as("el privilegio no lo convierte en titular: la auditoría tiene que poder distinguirlos")
                    .isFalse();
        }

        @Test
        @DisplayName("el email se compara normalizado: la identidad no depende de las mayúsculas")
        void identityIsCaseInsensitive() {
            Actor gritando = Actor.customer(
                    Email.of(TestFixtures.USER_EMAIL.toUpperCase(java.util.Locale.ROOT)), "Ana", "Pérez");

            assertThat(ReservationAccessPolicy.isOwner(gritando, reservation)).isTrue();
        }
    }

    @Nested
    @DisplayName("Alcance del listado")
    class Listing {

        @Test
        @DisplayName("al titular se le impone su propio filtro aunque no pida ninguno")
        void customerAlwaysGetsTheirOwnScope() {
            assertThat(ReservationAccessPolicy.ownerFilterFor(TestFixtures.owner(), Optional.empty()))
                    .contains(Email.of(TestFixtures.USER_EMAIL));
        }

        @Test
        @DisplayName("pedir el propio email es válido, aunque redundante")
        void customerMayAskForTheirOwnScope() {
            assertThat(ReservationAccessPolicy.ownerFilterFor(
                            TestFixtures.owner(), Optional.of(Email.of(TestFixtures.USER_EMAIL))))
                    .contains(Email.of(TestFixtures.USER_EMAIL));
        }

        @Test
        @DisplayName("pedir el de otro se rechaza: acá el 403 no revela nada que el cliente no sepa")
        void customerMayNotAskForSomeoneElse() {
            assertThatThrownBy(() -> ReservationAccessPolicy.ownerFilterFor(
                            TestFixtures.owner(), Optional.of(Email.of(TestFixtures.OTHER_USER_EMAIL))))
                    .isInstanceOf(ReservationAccessDeniedException.class);
        }

        @Test
        @DisplayName("backoffice puede filtrar por cualquiera, o por nadie")
        void backofficeChooses() {
            assertThat(ReservationAccessPolicy.ownerFilterFor(
                            TestFixtures.backoffice(), Optional.of(Email.of(TestFixtures.USER_EMAIL))))
                    .contains(Email.of(TestFixtures.USER_EMAIL));
            assertThat(ReservationAccessPolicy.ownerFilterFor(TestFixtures.backoffice(), Optional.empty()))
                    .as("ver todo es un privilegio explícito, no el default de nadie")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("no acepta argumentos nulos: una decisión de acceso no se toma con datos faltantes")
    void rejectsNulls() {
        assertThatThrownBy(() -> ReservationAccessPolicy.canRead(null, reservation))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReservationAccessPolicy.canRead(TestFixtures.owner(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReservationAccessPolicy.ownerFilterFor(TestFixtures.owner(), null))
                .isInstanceOf(NullPointerException.class);
    }
}
