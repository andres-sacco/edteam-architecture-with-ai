package com.edteam.reservations.infrastructure.cache;

import com.edteam.reservations.support.MutableClock;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InMemoryCacheStore")
class InMemoryCacheStoreTest {

    private static final Duration TTL = Duration.ofMinutes(1);

    private MutableClock clock;
    private InMemoryCacheStore store;

    @BeforeEach
    void setUp() {
        clock = MutableClock.at(TestFixtures.NOW);
        store = new InMemoryCacheStore(clock, 100);
    }

    @Test
    @DisplayName("devuelve lo que guardó")
    void storesAndReads() {
        store.put("k", "v", TTL);

        assertThat(store.get("k")).contains("v");
    }

    @Test
    @DisplayName("una clave que nunca se guardó es un miss")
    void missesUnknownKey() {
        assertThat(store.get("k")).isEmpty();
    }

    @Test
    @DisplayName("la entrada deja de servirse cuando vence el TTL")
    void expiresAfterTtl() {
        store.put("k", "v", TTL);

        clock.advance(TTL.minusSeconds(1));
        assertThat(store.get("k")).contains("v");

        clock.advance(Duration.ofSeconds(1));
        assertThat(store.get("k")).isEmpty();
    }

    @Test
    @DisplayName("leer una entrada vencida la saca del mapa")
    void releasesMemoryOnExpiredRead() {
        store.put("k", "v", TTL);
        clock.advance(TTL);

        store.get("k");

        assertThat(store.estimatedSize()).hasValue(0L);
    }

    @Test
    @DisplayName("evict borra la clave")
    void evictsKey() {
        store.put("k", "v", TTL);

        store.evict("k");

        assertThat(store.get("k")).isEmpty();
    }

    @Test
    @DisplayName("borrar algo que no está no es un error")
    void evictIsIdempotent() {
        store.evict("no-existe");

        assertThat(store.get("no-existe")).isEmpty();
    }

    @Test
    @DisplayName("no crece más allá del tope: un cliente con un bug no puede volcar la memoria")
    void boundsItsSize() {
        InMemoryCacheStore bounded = new InMemoryCacheStore(clock, 10);

        for (int i = 0; i < 1_000; i++) {
            bounded.put("k" + i, "v", TTL);
        }

        assertThat(bounded.estimatedSize().orElseThrow()).isLessThanOrEqualTo(10L);
        assertThat(bounded.capacityEvictions()).isPositive();
    }

    @Test
    @DisplayName("al desalojar prioriza lo vencido antes que lo vigente")
    void prefersEvictingExpiredEntries() {
        InMemoryCacheStore bounded = new InMemoryCacheStore(clock, 4);
        bounded.put("viejo", "v", Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(2));
        bounded.put("nuevo-1", "v", TTL);
        bounded.put("nuevo-2", "v", TTL);
        bounded.put("nuevo-3", "v", TTL);

        // La cuarta entrada obliga a hacer lugar: la vencida es la que sobra.
        bounded.put("nuevo-4", "v", TTL);

        assertThat(bounded.get("nuevo-1")).contains("v");
        assertThat(bounded.get("nuevo-4")).contains("v");
        assertThat(bounded.get("viejo")).isEmpty();
    }

    @Test
    @DisplayName("un TTL no positivo no guarda nada, en lugar de guardar para siempre")
    void ignoresNonPositiveTtl() {
        store.put("k", "v", Duration.ZERO);
        store.put("k2", "v", Duration.ofSeconds(-1));
        store.put("k3", "v", null);

        assertThat(store.get("k")).isEmpty();
        assertThat(store.get("k2")).isEmpty();
        assertThat(store.get("k3")).isEmpty();
    }

    @Test
    @DisplayName("exige clock y un tope positivo")
    void validatesConstructorArguments() {
        assertThatThrownBy(() -> new InMemoryCacheStore(null, 10))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new InMemoryCacheStore(clock, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
