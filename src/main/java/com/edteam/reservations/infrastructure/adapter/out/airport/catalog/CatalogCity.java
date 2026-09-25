package com.edteam.reservations.infrastructure.adapter.out.airport.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Ciudad tal como la devuelve la API de catálogo en un 200.
 *
 * <p>Sólo se mapean los dos campos que el contrato declara en la respuesta
 * exitosa ({@code BaseDTO}: {@code name} y {@code code}). El resto de lo que
 * la API pueda mandar —{@code timeZone}, {@code country}— se ignora a
 * propósito: traer campos que nadie usa convierte cualquier agregado del
 * proveedor en un cambio nuestro.
 *
 * <p>{@code ignoreUnknown} es justamente eso: que el proveedor sume un campo
 * no puede romper las reservas.
 *
 * <p>Es un DTO del adaptador, no un modelo de dominio: no cruza el puerto.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogCity(String code, String name) {}
