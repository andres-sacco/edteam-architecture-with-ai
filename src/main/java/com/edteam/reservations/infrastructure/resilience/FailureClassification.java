package com.edteam.reservations.infrastructure.resilience;

/**
 * La decisión completa sobre una excepción: qué es y qué se hace con ella.
 *
 * <p>Son dos ejes y no uno, porque no coinciden. El caso que lo demuestra es
 * el {@code 429} del catálogo: es transitorio y tiene que contar para que el
 * circuito abra, pero reintentarlo es desobedecer al proveedor que acaba de
 * pedirnos que bajemos el ritmo. Con un solo booleano —«¿es transitorio?»— esa
 * fila de la tabla del diseño no se puede expresar, y termina duplicada como
 * un {@code if} suelto en el retry.
 */
public record FailureClassification(FailureKind kind, boolean retryable) {

    public static final FailureClassification TRANSIENT_RETRYABLE =
            new FailureClassification(FailureKind.TRANSIENT, true);
    public static final FailureClassification TRANSIENT_NOT_RETRYABLE =
            new FailureClassification(FailureKind.TRANSIENT, false);
    public static final FailureClassification PERMANENT =
            new FailureClassification(FailureKind.PERMANENT, false);
    public static final FailureClassification SHED =
            new FailureClassification(FailureKind.SHED, false);

    /** Lo que el circuito pregunta: sólo lo transitorio mueve su ventana. */
    public boolean countsForCircuit() {
        return kind == FailureKind.TRANSIENT;
    }
}
