package com.edteam.reservations.application.port.in;

/** Qué hizo el caso de uso con el mensaje recibido. */
public enum EventProcessingOutcome {

    /** Primera vez que se ve: se aplicó y dejó su efecto. */
    APPLIED,

    /**
     * Ya estaba aplicado: no se hizo nada. El adaptador lo confirma igual.
     *
     * <p>Es el caso normal, no la excepción: la entrega es at-least-once.
     */
    DUPLICATE,

    /**
     * Se aplicó, y además llegó fuera de orden respecto de lo ya aplicado para
     * esa reserva.
     *
     * <p>Se aplica <b>igual</b> y se registra como anomalía. La alternativa
     * —descartarlo por traer un {@code sequence} menor— parece prolija y borra
     * eventos legítimos: un alta que se demoró 30 s en el retry y llega después
     * de su confirmación es un alta que el usuario nunca recibe, y nadie se
     * entera, porque para el broker el mensaje se procesó bien. Una
     * notificación tardía es peor que una puntual y mucho mejor que una
     * silenciosamente descartada.
     */
    APPLIED_OUT_OF_ORDER
}
