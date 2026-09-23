package com.edteam.reservations.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Lo que el slice web necesita y no trae por sí solo.
 *
 * <p>Sólo el reloj: la cadena de seguridad lo usa para la ventana de la cuota
 * de pedidos, y en un {@code @WebMvcTest} no se carga
 * {@code AdapterConfiguration}, que es quien lo publica en la aplicación real.
 */
@TestConfiguration(proxyBeanMethods = false)
public class WebSliceConfiguration {

    @Bean
    public Clock clock() {
        return TestFixtures.fixedClock();
    }
}
