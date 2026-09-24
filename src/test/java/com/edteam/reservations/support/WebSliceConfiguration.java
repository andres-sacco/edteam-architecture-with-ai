package com.edteam.reservations.support;

import com.edteam.reservations.infrastructure.observability.BusinessMetrics;
import com.edteam.reservations.infrastructure.observability.SecurityMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Lo que el slice web necesita y no trae por sí solo.
 *
 * <p>El reloj: la cadena de seguridad lo usa para la ventana de la cuota de
 * pedidos, y en un {@code @WebMvcTest} no se carga
 * {@code AdapterConfiguration}, que es quien lo publica en la aplicación real.
 *
 * <p>Y los dos medidores de la observabilidad, por el mismo motivo: la cadena
 * de seguridad ahora cuenta los rechazos por credencial y por cuota, y
 * {@code ObservabilityConfiguration} —que los publica— no entra al slice. Se
 * arman sobre un {@link SimpleMeterRegistry} propio: el slice no necesita un
 * registry real, y uno por contexto evita que los contadores de un test se
 * arrastren al siguiente.
 */
@TestConfiguration(proxyBeanMethods = false)
public class WebSliceConfiguration {

    @Bean
    public Clock clock() {
        return TestFixtures.fixedClock();
    }

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    public SecurityMetrics securityMetrics(MeterRegistry registry) {
        return new SecurityMetrics(registry);
    }

    @Bean
    public BusinessMetrics businessMetrics(MeterRegistry registry) {
        return new BusinessMetrics(registry);
    }
}
