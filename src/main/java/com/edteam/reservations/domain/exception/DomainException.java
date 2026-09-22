package com.edteam.reservations.domain.exception;

/**
 * Raíz de los errores de negocio. El dominio no conoce HTTP ni ninguna
 * tecnología: es el adaptador de entrada el que traduce estas excepciones al
 * protocolo correspondiente (por ejemplo, un 4xx en REST).
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
