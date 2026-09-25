package com.edteam.reservations.infrastructure.adapter.in.rest.dto;

/**
 * Expresiones regulares del contrato, en un solo lugar.
 *
 * <p>Están acá y no repetidas en cada DTO para que coincidan con los
 * {@code pattern} del OpenAPI: si una se toca, se toca una sola vez y el
 * contrato y la validación no se separan.
 */
public final class ApiFormats {

    /** Código IATA de aeropuerto, en mayúsculas. */
    public static final String AIRPORT_CODE = "^[A-Z]{3}$";

    /** Código de moneda ISO 4217, en mayúsculas. */
    public static final String CURRENCY_CODE = "^[A-Z]{3}$";

    /**
     * Importe decimal con hasta dos decimales.
     *
     * <p>Ocho dígitos enteros: es el máximo que admiten el modelo de datos
     * ({@code NUMERIC(10,2)}) y el value object {@code Money}. Rechazarlo acá
     * devuelve un 400 con el campo señalado, en lugar de un error de base.
     */
    public static final String DECIMAL_AMOUNT = "^\\d{1,8}(\\.\\d{1,2})?$";

    /** Criterio de orden admitido en el listado: {@code campo,dirección}. */
    public static final String SORT = "^(createdAt|firstDepartureAt),(asc|desc)$";

    private ApiFormats() {}
}
