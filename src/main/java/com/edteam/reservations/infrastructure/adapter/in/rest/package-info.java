/**
 * Adaptadores de entrada HTTP.
 *
 * <p>Vacío a propósito: los endpoints están fuera del alcance de este
 * esqueleto. Cuando se agreguen, acá van los {@code @RestController} más sus
 * DTOs de request/response y un {@code @RestControllerAdvice} que traduzca las
 * excepciones a códigos HTTP:
 *
 * <ul>
 *   <li>{@code DomainException} y {@code UnknownAirportException} → 400</li>
 *   <li>{@code ReservationNotFoundException} → 404</li>
 *   <li>{@code ConcurrentUpdateException} → 409</li>
 * </ul>
 *
 * <p>Regla a mantener: los controllers hablan con los puertos de entrada
 * ({@code CreateReservationUseCase} y compañía) y nunca con los servicios
 * concretos ni con los adaptadores de salida. Los DTOs son propios de esta
 * capa: el agregado del dominio no se serializa directamente, para que el
 * contrato de la API pueda evolucionar sin arrastrar al modelo.
 */
package com.edteam.reservations.infrastructure.adapter.in.rest;
