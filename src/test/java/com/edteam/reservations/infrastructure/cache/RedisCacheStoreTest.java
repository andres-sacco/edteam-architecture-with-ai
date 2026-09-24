package com.edteam.reservations.infrastructure.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El almacén de Redis después de sacarle la política de degradación.
 *
 * <p>Los tres tests que verificaban «degrada a un miss» cambiaron de signo, y
 * el cambio es el arreglo de un hallazgo, no una regresión. Mientras esta
 * clase se tragaba los errores, el circuito que va encima veía el 100 % de las
 * llamadas como exitosas: con Redis caído duro —conexión rechazada, que
 * responde rápido— no era ni un fallo contado ni una llamada lenta, así que el
 * circuito quedaba {@code CLOSED} para siempre mientras se seguía pagando el
 * viaje en cada operación.
 *
 * <p>Ahora esta clase relanza y la degradación la aplica
 * {@link CircuitBreakingCacheStore}, que es quien necesita ver el fallo para
 * contarlo. El contrato de «un cache caído no tumba el servicio» no se perdió:
 * se mudó, y {@code CircuitBreakingCacheStoreTest} lo verifica ahí.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisCacheStore")
class RedisCacheStoreTest {

    private static final Duration TTL = Duration.ofMinutes(1);

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> values;

    private RedisCacheStore store;

    @BeforeEach
    void setUp() {
        store = new RedisCacheStore(redis);
    }

    @Test
    @DisplayName("lee y escribe con el TTL pedido")
    void readsAndWrites() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("k")).thenReturn("v");

        assertThat(store.get("k")).contains("v");

        store.put("k", "v", TTL);
        verify(values).set("k", "v", TTL);
    }

    @Test
    @DisplayName("una clave inexistente es un miss y no un null que rompa al que llama")
    void missingKeyIsEmpty() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("k")).thenReturn(null);

        assertThat(store.get("k")).isEmpty();
    }

    @Test
    @DisplayName("lee varias claves con un solo MGET: once ciudades son una espera, no once")
    void readsManyKeysInOneRoundTrip() {
        when(redis.opsForValue()).thenReturn(values);
        List<String> keys = List.of("a", "b", "c");
        when(values.multiGet(keys)).thenReturn(Arrays.asList("1", null, "3"));

        assertThat(store.getAll(keys))
                .containsExactly(java.util.Map.entry("a", "1"), java.util.Map.entry("c", "3"));
        verify(values).multiGet(keys);
    }

    @Test
    @DisplayName("relanza si Redis no responde al leer: quien decide qué hacer es el circuito de arriba")
    void rethrowsOnReadFailure() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("Redis caído"));

        assertThatThrownBy(() -> store.get("k"))
                .isInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    @DisplayName("relanza si Redis no responde al escribir")
    void rethrowsOnWriteFailure() {
        when(redis.opsForValue()).thenReturn(values);
        doThrow(new QueryTimeoutException("timeout")).when(values).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> store.put("k", "v", TTL))
                .isInstanceOf(QueryTimeoutException.class);
    }

    @Test
    @DisplayName("relanza si Redis no responde al invalidar")
    void rethrowsOnEvictFailure() {
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("Redis caído"));

        assertThatThrownBy(() -> store.evict("k"))
                .isInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    @DisplayName("no guarda con TTL no positivo: sería una entrada sin vencimiento")
    void ignoresNonPositiveTtl() {
        store.put("k", "v", Duration.ZERO);
        store.put("k", "v", null);

        verify(redis, org.mockito.Mockito.never()).opsForValue();
    }

    @Test
    @DisplayName("no informa el tamaño: un DBSIZE cuenta claves de todos los usos")
    void doesNotReportSize() {
        assertThat(store.estimatedSize()).isEmpty();
    }
}
