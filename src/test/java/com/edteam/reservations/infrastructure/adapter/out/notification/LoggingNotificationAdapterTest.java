package com.edteam.reservations.infrastructure.adapter.out.notification;

import com.edteam.reservations.domain.event.DomainEvent;
import com.edteam.reservations.domain.event.ReservationCancelled;
import com.edteam.reservations.domain.event.ReservationConfirmed;
import com.edteam.reservations.domain.event.ReservationCreated;
import com.edteam.reservations.domain.event.ReservationModified;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("LoggingNotificationAdapter")
class LoggingNotificationAdapterTest {

    private final LoggingNotificationAdapter adapter = new LoggingNotificationAdapter();

    @Test
    @DisplayName("arma el mensaje de los cuatro tipos de evento sin fallar")
    void handlesEveryEventType() {
        Reservation pendiente = TestFixtures.storedReservation(0L);
        Reservation modificada = pendiente.changeItinerary(TestFixtures.connectingItinerary(), TestFixtures.NOW);

        List<DomainEvent> events = List.of(
                ReservationCreated.of(pendiente),
                ReservationConfirmed.of(pendiente.confirm(TestFixtures.NOW)),
                ReservationModified.of(modificada, pendiente.itinerary()),
                ReservationCancelled.of(pendiente.cancel(TestFixtures.NOW)));

        assertThat(events).hasSize(4);
        events.forEach(event -> assertThatCode(() -> adapter.notify(event)).doesNotThrowAnyException());
    }

    @Test
    @DisplayName("cubre todos los tipos de evento declarados por la interfaz sellada")
    void coversEverySealedSubtype() {
        // Si se agrega un tipo de evento, este test recuerda que hay que
        // contemplarlo en el adaptador (que además no compila sin el nuevo caso).
        assertThat(DomainEvent.class.getPermittedSubclasses()).hasSize(4);
    }
}
