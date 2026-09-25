package com.edteam.reservations.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.edteam.reservations.domain.exception.InvalidSegmentException;
import com.edteam.reservations.support.TestFixtures;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

@DisplayName("Segment")
class SegmentTest {

    @Test
    @DisplayName("un segmento nuevo no tiene id")
    void newSegmentHasNoId() {
        assertThat(TestFixtures.directSegment().id()).isEmpty();
    }

    @Test
    @DisplayName("un segmento persistido conserva su id")
    void existingSegmentKeepsId() {
        Segment segment = TestFixtures.existingSegment(7L, TestFixtures.EZE, TestFixtures.SCL, TestFixtures.DEPARTURE);

        assertThat(segment.id()).contains(SegmentId.of(7L));
    }

    @Test
    @DisplayName("normaliza la aerolínea a mayúsculas sin espacios sobrantes")
    void normalizesAirline() {
        Segment segment = Segment.newSegment(TestFixtures.EZE, TestFixtures.SCL, "  latam  ", TestFixtures.DEPARTURE);

        assertThat(segment.airline()).isEqualTo("LATAM");
    }

    @Test
    @DisplayName("rechaza origen y destino iguales")
    void rejectsSameOriginAndDestination() {
        assertThatThrownBy(() -> Segment.newSegment(
                        TestFixtures.EZE, TestFixtures.EZE, TestFixtures.AIRLINE, TestFixtures.DEPARTURE))
                .isInstanceOf(InvalidSegmentException.class)
                .hasMessageContaining("mismo aeropuerto");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("exige aerolínea")
    void rejectsMissingAirline(String airline) {
        assertThatThrownBy(
                        () -> Segment.newSegment(TestFixtures.EZE, TestFixtures.SCL, airline, TestFixtures.DEPARTURE))
                .isInstanceOf(InvalidSegmentException.class)
                .hasMessageContaining("aerolínea es obligatoria");
    }

    @Test
    @DisplayName("rechaza una aerolínea más larga que la columna")
    void rejectsTooLongAirline() {
        assertThatThrownBy(() ->
                        Segment.newSegment(TestFixtures.EZE, TestFixtures.SCL, "A".repeat(51), TestFixtures.DEPARTURE))
                .isInstanceOf(InvalidSegmentException.class)
                .hasMessageContaining("50 caracteres");
    }

    @Test
    @DisplayName("exige origen, destino, fecha e id explícito")
    void rejectsNulls() {
        assertThatNullPointerException()
                .isThrownBy(
                        () -> Segment.newSegment(null, TestFixtures.SCL, TestFixtures.AIRLINE, TestFixtures.DEPARTURE));
        assertThatNullPointerException()
                .isThrownBy(
                        () -> Segment.newSegment(TestFixtures.EZE, null, TestFixtures.AIRLINE, TestFixtures.DEPARTURE));
        assertThatNullPointerException()
                .isThrownBy(() -> Segment.newSegment(TestFixtures.EZE, TestFixtures.SCL, TestFixtures.AIRLINE, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new Segment(
                        null, TestFixtures.EZE, TestFixtures.SCL, TestFixtures.AIRLINE, TestFixtures.DEPARTURE));
    }

    @Test
    @DisplayName("la clave natural es la misma que el UNIQUE del modelo de datos")
    void naturalKeyIgnoresId() {
        Segment nuevo = TestFixtures.directSegment();
        Segment persistido =
                TestFixtures.existingSegment(7L, TestFixtures.EZE, TestFixtures.SCL, TestFixtures.DEPARTURE);

        assertThat(nuevo.naturalKey()).isEqualTo(persistido.naturalKey());
        assertThat(nuevo.naturalKey()).contains("EZE", "SCL", TestFixtures.AIRLINE, TestFixtures.DEPARTURE.toString());
    }

    @Test
    @DisplayName("hasDeparted es true desde el instante exacto de la salida")
    void detectsDeparture() {
        Segment segment = TestFixtures.directSegment();

        assertThat(segment.hasDeparted(TestFixtures.NOW)).isFalse();
        assertThat(segment.hasDeparted(TestFixtures.DEPARTURE.minusSeconds(1))).isFalse();
        assertThat(segment.hasDeparted(TestFixtures.DEPARTURE)).isTrue();
        assertThat(segment.hasDeparted(TestFixtures.DEPARTURE.plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("dos segmentos con los mismos datos son iguales")
    void hasValueEquality() {
        assertThat(TestFixtures.directSegment())
                .isEqualTo(Segment.newSegment(
                        TestFixtures.EZE, TestFixtures.SCL, TestFixtures.AIRLINE, TestFixtures.DEPARTURE));
        assertThat(TestFixtures.directSegment().id()).isEqualTo(Optional.empty());
    }
}
