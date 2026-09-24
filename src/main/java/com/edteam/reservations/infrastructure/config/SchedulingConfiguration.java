package com.edteam.reservations.infrastructure.config;

import com.edteam.reservations.infrastructure.logging.MdcTaskDecorator;
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
 *
 * <h2>Ninguna tarea puede nacer sin identificador de correlación</h2>
 * El {@link MdcTaskDecorator} va sobre el scheduler y no adentro de cada
 * {@code @Scheduled}. La diferencia no es de estilo: puesto acá, una tarea
 * programada nueva hereda el id sin que su autor tenga que acordarse de nada,
 * y la regla «todo registro lleva correlationId» deja de depender de una
 * revisión de código. Cada tarea después lo refina con
 * {@code MdcTaskDecorator.adopt(nombre)} para que el id la nombre.
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
        scheduler.setTaskDecorator(new MdcTaskDecorator());
        return scheduler;
    }
}
