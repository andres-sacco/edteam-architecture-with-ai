package com.edteam.reservations.infrastructure.config;

import com.edteam.reservations.infrastructure.security.crypto.PiiCipher;
import com.edteam.reservations.infrastructure.security.crypto.PiiCipherProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cableado del cifrado de datos personales en reposo.
 *
 * <p>El {@link PiiCipher} es un bean y no una instancia estática porque el
 * {@code AttributeConverter} que lo usa lo pide al contenedor de Spring:
 * Hibernate resuelve los converters por el {@code BeanContainer}, que es lo
 * que permite que el converter tenga la clave sin un singleton global ni un
 * inicializador que alguien pueda olvidarse de ejecutar.
 */
@Configuration
@EnableConfigurationProperties(PiiCipherProperties.class)
public class PiiProtectionConfiguration {

    @Bean
    public PiiCipher piiCipher(PiiCipherProperties properties) {
        return new PiiCipher(properties);
    }
}
