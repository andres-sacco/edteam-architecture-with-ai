package com.edteam.reservations.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Habilita las tareas programadas y les da un pool propio.
 *
 * <p>El pool es separado del que atiende los pedidos: el despacho de
 * notificaciones es I/O contra un servicio externo que puede estar lento, y no
 * debe competir por los hilos que responden a los usuarios.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({OutboxProperties.class, AirportCatalogProperties.class})
public class SchedulingConfiguration {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // Tres tareas: el relay del outbox, la purga y margen para que una
        // corrida lenta del relay no retrase a la siguiente.
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("reservations-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        return scheduler;
    }
}
