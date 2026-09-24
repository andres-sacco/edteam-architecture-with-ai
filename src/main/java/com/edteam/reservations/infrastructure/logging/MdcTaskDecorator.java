package com.edteam.reservations.infrastructure.logging;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Le da identidad de correlación a lo que no nace de un pedido HTTP.
 *
 * <h2>Qué arregla</h2>
 * Las dos tareas de {@code adapter/in/scheduling} —el relay del outbox y la
 * purga— corren en el {@code ThreadPoolTaskScheduler} y no tenían ningún
 * identificador: el tick del relay, la línea de la purga y todo lo que
 * escriben por debajo salían sin nada con qué agruparse. La consecuencia
 * práctica es que no se podía preguntar «¿qué hizo la corrida de las 03:17?»,
 * que es exactamente la pregunta de una investigación sobre el relay.
 *
 * <p>El id es <b>sintético y por corrida</b>: {@code job-<nombre>-<8 hex>}. No
 * es un id de pedido y no pretende serlo. Su único trabajo es que las N líneas
 * de una misma vuelta se puedan agrupar y que la regla «todo registro lleva
 * identificador de correlación» no tenga excepciones.
 *
 * <h2>El decorador pone el piso; la tarea lo refina</h2>
 * El decorador es uno solo y lo comparten todas las tareas del scheduler, así
 * que no puede saber cuál está corriendo: pone un id genérico y garantiza que
 * <b>ninguna tarea pueda nacer sin uno</b>, que es la parte que no se puede
 * delegar en que el autor de un {@code @Scheduled} nuevo se acuerde. Cada
 * tarea llama a {@link #adopt(String)} en su primera línea y se queda con un
 * id que la nombra.
 *
 * <h2>Se repone, no se borra</h2>
 * El {@code finally} restituye el MDC que había antes en lugar de limpiarlo.
 * Parece un detalle y es el hallazgo 10 de la auditoría: {@code MDC.remove} en
 * el relay hacía que, después de despachar el primer mensaje —que pisa el id
 * de la corrida con el del pedido que originó el hecho—, el resto de la vuelta
 * saliera <b>sin ningún id</b>. Incluida la línea {@code INFO} del tick, que es
 * la que el §1.4 del diseño muestra como ejemplo llevándolo.
 */
public class MdcTaskDecorator implements TaskDecorator {

    /** Prefijo del id sintético. Hace obvio en el log que no es un id de pedido. */
    static final String PREFIX = "job-";

    /** Nombre que lleva una tarea que todavía no se identificó. */
    private static final String UNNAMED = "scheduled";

    /**
     * 8 hex. El id de corrida sólo tiene que distinguir vueltas consecutivas
     * del mismo job dentro de la ventana en la que alguien mira el log, no ser
     * único en el universo; y el formato tiene que seguir cumpliendo el
     * {@code [A-Za-z0-9_-]{8,64}} que {@code CorrelationIdFilter} valida, para
     * que el mismo campo signifique lo mismo venga de donde venga.
     */
    private static final int RUN_ID_LENGTH = 8;

    @Override
    public Runnable decorate(Runnable runnable) {
        Objects.requireNonNull(runnable, "La tarea es obligatoria");
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            MDC.put(LogFields.CORRELATION_ID, runIdFor(UNNAMED));
            MDC.put(LogFields.JOB, UNNAMED);
            try {
                runnable.run();
            } finally {
                restore(previous);
            }
        };
    }

    /**
     * La tarea se identifica: el id genérico del decorador se reemplaza por uno
     * que la nombra.
     *
     * @param job nombre de la tarea, de un vocabulario cerrado
     * @return el {@code job.runId} de esta corrida, para escribirlo como campo
     */
    public static String adopt(String job) {
        Objects.requireNonNull(job, "El nombre de la tarea es obligatorio");
        String runId = runIdFor(job);
        MDC.put(LogFields.CORRELATION_ID, runId);
        MDC.put(LogFields.JOB, job);
        return runId;
    }

    private static String runIdFor(String job) {
        return PREFIX + job + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, RUN_ID_LENGTH);
    }

    /**
     * Devuelve el MDC al estado anterior.
     *
     * <p>{@code setContextMap(null)} tira {@code IllegalArgumentException}, así
     * que el caso «no había nada» se escribe a mano. Con un pool de hilos,
     * dejar el mapa como estaba es lo que evita que la corrida de un job le
     * preste su id a la siguiente tarea que tome ese hilo.
     */
    public static void restore(Map<String, String> previous) {
        if (previous == null || previous.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }
}
