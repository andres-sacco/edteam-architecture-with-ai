package com.edteam.reservations.application.port.in;

/**
 * Datos del usuario que reserva, tal como los recibe el caso de uso.
 *
 * <p>Tipos primitivos, igual que {@link PassengerData} y {@link SegmentData}:
 * el adaptador de entrada no construye value objects del dominio, y la
 * traducción —con sus validaciones— ocurre en un solo lugar.
 *
 * <p>No lleva id: el usuario se identifica por su email, que es su clave
 * natural en el modelo de datos. Pedirle al cliente un id lo obligaría a
 * conocer de antemano una fila que quizá no existe, que es exactamente el
 * problema que este record resuelve.
 */
public record UserData(String email, String firstName, String lastName) {
}
