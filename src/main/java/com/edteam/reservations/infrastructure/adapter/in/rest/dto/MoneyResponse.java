package com.edteam.reservations.infrastructure.adapter.in.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Importe con su moneda.
 *
 * <p>El monto sale como string y no como número JSON para que el cliente lo
 * reciba con la precisión exacta con la que se guardó: un {@code double} de
 * JavaScript no representa {@code 1350.10}.
 *
 * @param amount   importe en decimal exacto, con dos decimales
 * @param currency código ISO 4217
 */
@Schema(name = "Money", description = "Importe con su moneda. Nunca se envía un monto sin moneda.")
public record MoneyResponse(
        @Schema(description = "Importe en decimal exacto, con dos decimales.", example = "1350.00")
        String amount,

        @Schema(description = "Código de moneda ISO 4217.", example = "USD")
        String currency) {}
