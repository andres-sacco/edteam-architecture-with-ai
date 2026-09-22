package com.edteam.reservations.application.port.in;

import java.time.LocalDate;

/**
 * Datos de un pasajero tal como los recibe el caso de uso.
 *
 * @param documentNumber puede venir nulo: el modelo de datos admite pasajeros
 *                       sin documento cargado
 */
public record PassengerData(String firstName, String lastName, LocalDate birthDate, String documentNumber) {
}
