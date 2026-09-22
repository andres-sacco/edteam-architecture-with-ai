package com.edteam.reservations.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Metadatos del documento OpenAPI que genera springdoc.
 *
 * <p>Las operaciones, los parámetros y los esquemas salen del código: springdoc
 * los deduce de los {@code @RequestMapping}, de los tipos de los DTOs y de sus
 * anotaciones de Bean Validation, más las {@code @Operation} y {@code @Schema}
 * que los describen. Lo que el código no puede decir por sí solo —quién
 * publica la API, contra qué servidores corre, qué licencia tiene— se declara
 * acá.
 *
 * <h2>Los servidores</h2>
 * No se declaran acá a propósito. Cuando el documento no trae {@code servers},
 * springdoc completa uno con el origen del pedido que lo pidió, así que
 * Swagger UI siempre ejecuta contra el host desde el que se la está mirando:
 * {@code http://localhost:8080} en desarrollo, y el host real allí donde se
 * despliegue.
 *
 * <p>La alternativa —una lista fija con producción primero— tiene un problema
 * concreto: Swagger UI usa el primer servidor como destino del <em>Try it
 * out</em>, de modo que probar desde el entorno local dispararía pedidos
 * contra producción. Y al revés, poner {@code localhost} primero rompería la
 * documentación publicada.
 *
 * <p>Si un entorno necesita declarar su lista —para documentarle a un partner
 * contra qué hosts puede pegar—, se configura por properties sin tocar este
 * código:
 *
 * <pre>{@code
 * springdoc:
 *   open-api:
 *     servers:
 *       - url: https://api.edteam.example
 *         description: Producción
 * }</pre>
 *
 * <h2>La versión en la ruta</h2>
 * Las rutas del documento son las reales, {@code /v1/reservations} incluido:
 * se generan a partir de los mappings. El prefijo de versión vive ahí y no en
 * la URL del servidor, a diferencia de cuando el documento se escribía a mano.
 */
@Configuration
public class OpenApiConfiguration {

    /** Único tag del documento; los controllers lo referencian por nombre. */
    public static final String RESERVATIONS_TAG = "Reservas";

    @Bean
    public OpenAPI reservationsOpenApi() {
        return new OpenAPI()
                .info(apiInfo())
                .tags(List.of(new Tag()
                        .name(RESERVATIONS_TAG)
                        .description("Ciclo de vida de las reservas de vuelos.")));
    }

    private static Info apiInfo() {
        return new Info()
                .title("API de Reservas de Vuelos")
                .version("1.0.0")
                .description(DESCRIPTION)
                .contact(new Contact().name("Equipo de Reservas").email("reservas@edteam.example"))
                .license(new License().name("MIT").url("https://opensource.org/licenses/MIT"));
    }

    private static final String DESCRIPTION = """
            Permite crear, consultar, listar, actualizar y cancelar reservas de vuelos.
            Una reserva pertenece a un único usuario, tiene un itinerario (compuesto por
            segmentos ordenados) y al menos un pasajero.

            ## Principios del contrato

            - **El contrato es propio de la API.** Los esquemas no son el modelo de dominio
              ni el de persistencia: son una representación pensada para múltiples
              consumidores (web, mobile, partners). El modelo interno puede cambiar sin
              romper este contrato, y viceversa.
            - **Identificadores opacos.** Todo `id` es un `string` sin semántica para el
              cliente. Que hoy sea un entero en la base es un detalle de implementación.
            - **Versionado en la URL.** Los cambios incompatibles viajan en un `/v2`; dentro
              de `/v1` sólo se agregan campos opcionales. Los clientes deben ignorar los
              campos que no conozcan.
            - **Concurrencia con ETag.** La versión del recurso se expresa con el header
              `ETag` y se controla con `If-Match`. El número de versión interno no forma
              parte del cuerpo de ninguna representación.
            - **Importes como string.** Los montos se serializan en decimal exacto
              (`"1350.00"`) para evitar la pérdida de precisión de los flotantes IEEE en los
              clientes JavaScript.
            - **Errores con RFC 7807.** Todas las respuestas de error usan
              `application/problem+json`, con un campo `code` estable y legible por máquina
              para que el cliente no tenga que parsear textos.

            ## Idempotencia

            El alta exige un header `Idempotency-Key` (un UUID que genera el cliente). Clave
            nueva responde **201**; clave ya usada responde **200** con la reserva que se
            creó con ella, sin duplicar nada. La clave manda sobre el cuerpo: un reintento
            con la misma clave y contenido distinto devuelve igual la reserva original.

            ## Concurrencia optimista

            Las lecturas devuelven un `ETag`. Modificar o cancelar exige mandarlo en
            `If-Match`. Si la reserva cambió mientras tanto, la operación se rechaza con
            **409** y no escribe nada.
            """;
}
