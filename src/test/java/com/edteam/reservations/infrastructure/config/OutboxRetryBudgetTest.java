package com.edteam.reservations.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Los dos cortes del outbox tienen que decir lo mismo.
 *
 * <p>El hallazgo era que no lo decían. Con {@code initial-backoff 5s},
 * {@code max-backoff 5m} y {@code max-attempts 10}, los diez intentos se
 * agotaban en unos veinte minutos de reloj y el {@code retry-ceiling: 6h} no
 * participaba <strong>nunca</strong>: era configuración muerta. El par de
 * números documentaba una intención —«seis horas reintentando es un problema
 * que ya tiene dueño»— que no ocurría, y una caída del broker de más de veinte
 * minutos mandaba todo el outbox a la dead letter.
 *
 * <p>Este test es lo que impide que vuelvan a divergir: calcula la ventana que
 * cubren los intentos configurados y la compara con el techo de tiempo.
 */
@DisplayName("Presupuesto de reintentos del outbox")
class OutboxRetryBudgetTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Properties.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OutboxProperties.class)
    static class Properties {}

    @Test
    @DisplayName("los intentos configurados cubren al menos el techo de tiempo: manda el techo, no el contador")
    void theAttemptBudgetCoversTheTimeCeiling() {
        runner.run(context -> {
            OutboxProperties properties = context.getBean(OutboxProperties.class);

            assertThat(properties.worstCaseRetryWindow())
                    .as(
                            "con %d intentos de %s a %s se cubren %d min, y el techo es de %d min",
                            properties.maxAttempts(),
                            properties.initialBackoff(),
                            properties.maxBackoff(),
                            properties.worstCaseRetryWindow().toMinutes(),
                            properties.retryCeiling().toMinutes())
                    .isGreaterThanOrEqualTo(properties.retryCeiling());
        });
    }

    @Test
    @DisplayName("el lease del reclamo se eleva al peor caso de un tick en lugar de ser una constante suelta")
    void theClaimLeaseCoversTheWorstCaseTick() {
        runner.run(context -> {
            OutboxProperties properties = context.getBean(OutboxProperties.class);

            // batch-size × confirm-timeout: contra un broker que acepta y no
            // confirma, un tick de 50 mensajes dura más de cuatro minutos. Un
            // lease de dos dejaba que otra instancia re-reclamara mensajes
            // todavía en vuelo.
            Duration worstCaseTick =
                    Duration.ofSeconds(5).multipliedBy(properties.batchSize()).plusSeconds(30);

            assertThat(properties.withClaimLeaseAtLeast(worstCaseTick).claimLease())
                    .isGreaterThanOrEqualTo(worstCaseTick);
        });
    }

    @Test
    @DisplayName("la ventana se calcula, no se escribe: con el par viejo el test habría fallado")
    void theOldPairWouldHaveFailed() {
        OutboxProperties old = new OutboxProperties(
                Duration.ofSeconds(5),
                50,
                10,
                Duration.ofHours(6),
                Duration.ofSeconds(5),
                Duration.ofMinutes(5),
                Duration.ofMinutes(2),
                Duration.ofDays(7),
                Duration.ofSeconds(5));

        assertThat(old.worstCaseRetryWindow())
                .as("diez intentos se agotaban en ~20 min, no en seis horas")
                .isLessThan(Duration.ofMinutes(25))
                .isLessThan(old.retryCeiling());
    }
}
