package com.edteam.reservations.infrastructure.adapter.out.messaging;

import com.edteam.reservations.domain.event.DomainEvent;
import com.edteam.reservations.domain.event.ReservationCancelled;
import com.edteam.reservations.domain.event.ReservationConfirmed;
import com.edteam.reservations.domain.event.ReservationCreated;
import com.edteam.reservations.domain.event.ReservationModified;
import com.edteam.reservations.domain.model.Reservation;
import com.edteam.reservations.support.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El contrato del payload, del lado que este repositorio emite.
 *
 * <p>Es el equivalente de {@code OpenApiContractTest} para la mensajería: un
 * campo que desaparece del payload rompe el build en lugar de romper al
 * consumidor en producción.
 */
@DisplayName("DomainEventPayloadMapper (contrato del payload)")
class DomainEventPayloadMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DomainEventPayloadMapper mapper = new DomainEventPayloadMapper(objectMapper);

    private static Reservation reservation() {
        return TestFixtures.storedReservation(3L);
    }

    private static Stream<DomainEvent> allEventTypes() {
        return Stream.of(
                ReservationCreated.of(reservation()),
                ReservationConfirmed.of(reservation()),
                ReservationModified.of(reservation(), TestFixtures.connectingItinerary()),
                ReservationCancelled.of(reservation()));
    }

    private JsonNode payloadOf(DomainEvent event) throws Exception {
        return objectMapper.readTree(mapper.toPayload(event));
    }

    // -----------------------------------------------------------------
    // Lo que tiene que viajar
    // -----------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("allEventTypes")
    @DisplayName("los cuatro tipos llevan reserva, destinatario e itinerario")
    void everyTypeCarriesTheCommonFields(DomainEvent event) throws Exception {
        JsonNode payload = payloadOf(event);

        assertThat(payload.path("reservationId").asText()).isEqualTo("10");
        assertThat(payload.path("userId").asText()).isEqualTo("1");
        JsonNode itinerary = payload.path("itinerary");
        assertThat(itinerary.path("origin").asText()).isEqualTo("EZE");
        assertThat(itinerary.path("destination").asText()).isEqualTo("SCL");
        assertThat(itinerary.path("firstDeparture").asText()).isNotBlank();
        assertThat(itinerary.path("segmentCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("el alta lleva la cantidad de pasajeros")
    void createdCarriesThePassengerCount() throws Exception {
        assertThat(payloadOf(ReservationCreated.of(reservation())).path("passengerCount").asInt())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("la modificación lleva el itinerario anterior además del nuevo")
    void modifiedCarriesThePreviousItinerary() throws Exception {
        JsonNode payload = payloadOf(ReservationModified.of(reservation(), TestFixtures.connectingItinerary()));

        // Sin esto el consumidor sólo puede decir "tu reserva cambió" y no "tu
        // vuelo pasó del 12 al 14"; y no lo puede reconstruir preguntándonos,
        // porque el estado previo ya no existe.
        assertThat(payload.path("previousItinerary").path("destination").asText()).isEqualTo("MAD");
        assertThat(payload.path("previousItinerary").path("segmentCount").asInt()).isEqualTo(2);
        assertThat(payload.path("itinerary").path("destination").asText()).isEqualTo("SCL");
    }

    @ParameterizedTest
    @MethodSource("allEventTypes")
    @DisplayName("el importe viaja como string, no como número")
    void priceTravelsAsAString(DomainEvent event) throws Exception {
        JsonNode amount = payloadOf(event).path("itinerary").path("price").path("amount");

        // Un consumidor que parsee JSON a double perdería precisión. Es el
        // mismo motivo por el que Money usa BigDecimal.
        assertThat(amount.isTextual()).isTrue();
        assertThat(amount.asText()).isEqualTo("1250.50");
        assertThat(payloadOf(event).path("itinerary").path("price").path("currency").asText())
                .isEqualTo("USD");
    }

    @ParameterizedTest
    @MethodSource("allEventTypes")
    @DisplayName("los ids viajan como string aunque en la base sean BIGSERIAL")
    void idsTravelAsStrings(DomainEvent event) throws Exception {
        JsonNode payload = payloadOf(event);

        assertThat(payload.path("reservationId").isTextual()).isTrue();
        assertThat(payload.path("userId").isTextual()).isTrue();
    }

    // -----------------------------------------------------------------
    // Lo que NO tiene que viajar
    // -----------------------------------------------------------------

    /**
     * La restricción no negociable: ni documento del pasajero, ni email, ni
     * nombre, ni datos de pago. El destinatario se identifica por id interno y
     * el sistema de notificaciones resuelve el contacto, que es dato suyo.
     */
    @ParameterizedTest
    @MethodSource("allEventTypes")
    @DisplayName("ningún dato sensible viaja en el mensaje")
    void carriesNoSensitiveData(DomainEvent event) {
        String payload = mapper.toPayload(event);

        assertThat(payload)
                .doesNotContain(TestFixtures.USER_EMAIL)
                .doesNotContain("@")
                // Documento del pasajero de las fixtures.
                .doesNotContain("30123456")
                .doesNotContain("Ana")
                .doesNotContain("Pérez")
                .doesNotContainIgnoringCase("email")
                .doesNotContainIgnoringCase("document")
                .doesNotContainIgnoringCase("passport")
                .doesNotContainIgnoringCase("card");
    }

    @ParameterizedTest
    @MethodSource("allEventTypes")
    @DisplayName("el payload sólo tiene las claves del contrato")
    void hasOnlyTheContractKeys(DomainEvent event) throws Exception {
        List<String> keys = new java.util.ArrayList<>();
        payloadOf(event).fieldNames().forEachRemaining(keys::add);

        // Allowlist y no denylist: un campo nuevo que se agregue sin pensar
        // rompe el test, y ése es exactamente el momento en el que hay que
        // preguntarse si es dato personal.
        assertThat(keys).isSubsetOf("reservationId", "userId", "passengerCount",
                "itinerary", "previousItinerary");
    }

    // -----------------------------------------------------------------
    // Versión del esquema
    // -----------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("allEventTypes")
    @DisplayName("los cuatro tipos declaran la versión 1 del esquema")
    void everyTypeDeclaresSchemaVersionOne(DomainEvent event) {
        assertThat(mapper.schemaVersion(event)).isEqualTo(1);
    }

    /**
     * El {@code switch} del mapper es exhaustivo sin {@code default} y sobre una
     * interfaz sellada: si mañana aparece un quinto hecho, el compilador marca
     * el mapper como pendiente de actualizar. Este test fija que la lista de
     * tipos permitidos es la que el contrato documenta, así que agregar uno
     * también obliga a pasar por acá.
     */
    @Test
    @DisplayName("la jerarquía sellada tiene exactamente los cuatro tipos del contrato")
    void theSealedHierarchyHasExactlyTheFourDocumentedTypes() {
        assertThat(DomainEvent.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(ReservationCreated.class, ReservationConfirmed.class,
                        ReservationModified.class, ReservationCancelled.class);
        assertThat(allEventTypes().map(DomainEvent::eventType).toList())
                .containsExactly("reservation.created", "reservation.confirmed",
                        "reservation.modified", "reservation.cancelled");
    }

    @Test
    @DisplayName("el payload se puede volver a leer: es JSON válido")
    void payloadIsReadableBack() throws Exception {
        assertThat(mapper.parse(mapper.toPayload(ReservationCreated.of(reservation()))))
                .isNotNull();
    }
}
