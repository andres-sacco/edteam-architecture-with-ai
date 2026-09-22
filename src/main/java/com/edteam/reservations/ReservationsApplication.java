package com.edteam.reservations;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de entrada de la aplicación.
 *
 * <p>Es lo único que vive en la raíz del paquete: el escaneo de componentes
 * arranca desde acá y alcanza las tres capas (dominio, aplicación e
 * infraestructura).
 */
@SpringBootApplication
public class ReservationsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReservationsApplication.class, args);
    }
}
