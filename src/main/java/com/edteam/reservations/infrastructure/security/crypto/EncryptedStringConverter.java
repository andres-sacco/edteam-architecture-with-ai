package com.edteam.reservations.infrastructure.security.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Cifra y descifra una columna de texto de forma transparente para el mapeo.
 *
 * <p>Va como {@code AttributeConverter} y no como lógica del adaptador a
 * propósito: así el cifrado no depende de que cada camino de escritura se
 * acuerde de aplicarlo. Toda escritura de la entidad pasa por acá, incluidas
 * las que agregue alguien mañana.
 *
 * <p>Es un {@code @Component} para que Hibernate lo pida al contenedor de
 * Spring —que es quien tiene el {@link PiiCipher} con la clave— en lugar de
 * instanciarlo con el constructor sin argumentos.
 *
 * <p>{@code autoApply} queda en {@code false}: se anota columna por columna.
 * Un cifrado automático sobre todos los {@code String} de todas las entidades
 * cifraría también el código de aerolínea y el estado de la reserva, y
 * rompería las consultas que sí tienen que filtrar por ellos.
 */
@Component
@Converter(autoApply = false)
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final PiiCipher cipher;

    public EncryptedStringConverter(PiiCipher cipher) {
        this.cipher = Objects.requireNonNull(cipher, "El cifrador es obligatorio");
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return cipher.decrypt(dbData);
    }
}
