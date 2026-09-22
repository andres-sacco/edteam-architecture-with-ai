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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisCacheStore")
class RedisCacheStoreTest {

    private static final Duration TTL = Duration.ofMinutes(1);

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> values;

    private List<String> failures;
    private RedisCacheStore store;

    @BeforeEach
    void setUp() {
        failures = new ArrayList<>();
        store = new RedisCacheStore(redis, failures::add);
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
    @DisplayName("si Redis no responde al leer, degrada a un miss en lugar de propagar el error")
    void degradesToMissOnReadFailure() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("Redis caído"));

        assertThat(store.get("k")).isEmpty();
        assertThat(failures).containsExactly("get");
    }

    @Test
    @DisplayName("si Redis no responde al escribir, el pedido sigue igual")
    void swallowsWriteFailure() {
        when(redis.opsForValue()).thenReturn(values);
        doThrow(new QueryTimeoutException("timeout")).when(values).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> store.put("k", "v", TTL)).doesNotThrowAnyException();
        assertThat(failures).containsExactly("put");
    }

    @Test
    @DisplayName("si Redis no responde al invalidar, tampoco propaga: el TTL corto acota el daño")
    void swallowsEvictFailure() {
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("Redis caído"));

        assertThatCode(() -> store.evict("k")).doesNotThrowAnyException();
        assertThat(failures).containsExactly("evict");
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
