package com.edteam.reservations.infrastructure.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Almacén de pares clave/valor con vencimiento.
 *
 * <p>Es el único punto del sistema que sabe <em>dónde</em> vive el cache. Los
 * decoradores que cachean algo —el maestro de aeropuertos, el total del
 * listado, la versión de una reserva— dependen de esta interfaz y no de Redis,
 * así que el mismo código corre con el almacén distribuido, con el fallback en
 * memoria o, en un test, con uno que falla siempre.
 *
 * <p>Vive en {@code infrastructure} a propósito: el dominio y la aplicación no
 * la conocen, y {@code HexagonalArchitectureTest} lo verifica. Un cache es una
 * decisión de despliegue, no una regla de negocio.
 *
 * <h2>El contrato importante: nunca falla</h2>
 * Ninguna implementación puede propagar un error de infraestructura. Si el
 * almacén no responde, {@link #get(String)} devuelve
 * {@link Optional#empty()} —lo mismo que un miss— y {@link #put} y
 * {@link #evict} no hacen nada. La consecuencia es deliberada: un Redis caído
 * degrada el sistema a ir al origen, que es exactamente cómo funcionaba antes
 * de que existiera el cache, y no tumba el servicio.
 *
 * <h2>Por qué {@code String} y no un tipo genérico</h2>
 * Lo que se cachea acá son escalares: un booleano, un total, un número de
 * versión. Serializarlos es trivial y hacerlo explícito en cada decorador
 * mantiene el almacén sin dependencias de Jackson y sin la tentación de
 * guardar un objeto entero —que es justamente lo que la restricción de datos
 * sensibles prohíbe.
 */
public interface CacheStore {

    /**
     * Valor vigente de la clave.
     *
     * @return el valor, o vacío si no está, venció o el almacén no respondió
     */
    Optional<String> get(String key);

    /**
     * Guarda el valor con el vencimiento indicado.
     *
     * <p>Un fallo del almacén se traga: no guardar es un miss futuro, no un
     * error del pedido que estaba en curso.
     */
    void put(String key, String value, Duration ttl);

    /** Borra la clave. Es idempotente: borrar algo que no está no es un error. */
    void evict(String key);

    /**
     * Cantidad de entradas, si el almacén la conoce.
     *
     * <p>El fallback en memoria la conoce y se publica como métrica. Redis
     * no: preguntarle el tamaño de una base compartida es un {@code DBSIZE}
     * que cuenta claves de todos los usos, así que devuelve vacío en lugar de
     * publicar un número que significa otra cosa.
     */
    default OptionalLong estimatedSize() {
        return OptionalLong.empty();
    }
}
