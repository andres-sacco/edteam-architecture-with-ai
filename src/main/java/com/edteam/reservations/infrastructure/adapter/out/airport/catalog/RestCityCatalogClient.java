package com.edteam.reservations.infrastructure.adapter.out.airport.catalog;

import com.edteam.reservations.application.exception.AirportCatalogIntegrationException;
import com.edteam.reservations.application.exception.AirportCatalogUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Cliente HTTP de la API de catálogo, sobre {@link RestClient}.
 *
 * <p>Recibe el {@code RestClient} ya construido en lugar de armarlo acá: la
 * URL base, la credencial y la configuración del transporte se deciden en
 * {@code AdapterConfiguration}, y así esta clase se puede testear contra un
 * servidor simulado sin levantar el contexto de Spring.
 *
 * <h2>Por qué {@code exchange} y no {@code retrieve}</h2>
 * {@code retrieve()} convierte cualquier respuesta de error en una excepción
 * genérica de Spring y trata al 404 como falla. Acá el 404 es una respuesta de
 * negocio perfectamente normal —"esa ciudad no está en el catálogo"— y la
 * diferencia entre 4xx y 5xx cambia qué hacemos. Con {@code exchange()}
 * ninguna respuesta lanza sola y la clasificación queda escrita, no implícita.
 *
 * <h2>Clasificación de la respuesta</h2>
 * <table>
 *   <caption>Respuesta de la API a resultado</caption>
 *   <tr><th>Respuesta</th><th>Resultado</th><th>Por qué</th></tr>
 *   <tr><td>200 con cuerpo</td><td>{@code Optional} con la ciudad</td><td>—</td></tr>
 *   <tr><td>200 con cuerpo vacío</td><td>{@code Optional.empty()}</td>
 *       <td>es como este catálogo dice hoy "no la conozco" (ver abajo)</td></tr>
 *   <tr><td>200 con cuerpo ilegible o sin {@code code}</td><td>{@code AirportCatalogIntegrationException}</td>
 *       <td>hubo respuesta pero no cumple el contrato: tratarla como "existe" sería aceptar basura</td></tr>
 *   <tr><td>404</td><td>{@code Optional.empty()}</td><td>el catálogo contestó: no la conoce</td></tr>
 *   <tr><td>429</td><td>{@code AirportCatalogUnavailableException}</td>
 *       <td>es 4xx pero transitorio: nos están limitando, no nos equivocamos</td></tr>
 *   <tr><td>Otro 4xx (400, 401, 403, 422…)</td><td>{@code AirportCatalogIntegrationException}</td>
 *       <td>credencial, permisos o pedido mal armado: repetirlo no lo arregla</td></tr>
 *   <tr><td>5xx</td><td>{@code AirportCatalogUnavailableException}</td><td>el problema es del proveedor</td></tr>
 *   <tr><td>Error de conexión</td><td>{@code AirportCatalogUnavailableException}</td><td>ni siquiera hubo respuesta</td></tr>
 * </table>
 *
 * <h2>El 404 que no llega</h2>
 * El contrato publicado dice que un código desconocido devuelve 404, pero el
 * servicio devuelve hoy <strong>200 con {@code Content-Length: 0}</strong>
 * (verificado contra el {@code api-catalog} de {@code compose.yaml}: una ruta
 * inexistente sí da 404 con cuerpo, así que el 200 vacío es específicamente
 * cómo este handler contesta un código que no conoce).
 *
 * <p>Se aceptan las dos formas como "no existe". Es una decisión tomada a
 * conciencia: la alternativa —fallar ante el vacío— es más estricta, pero
 * contra este proveedor haría que cualquier código desconocido, incluido un
 * typo del cliente, terminara en un 5xx, y la API nunca podría contestar "esa
 * ciudad no existe", que es justamente lo que quien reserva necesita leer.
 *
 * <p>El riesgo asumido: si algún día ese cuerpo vacío pasa a significar un
 * defecto real del proveedor, lo vamos a reportar como ciudad inexistente. Por
 * eso cada vacío deja un WARN —el vacío no es normal, es un incumplimiento— y
 * hay un test contra el servicio real ({@code RestCityCatalogClientLiveTest})
 * que falla el día que empiecen a devolver 404, para volver a la lectura
 * estricta.
 *
 * <p>Lo que <strong>no</strong> se tolera es un cuerpo presente pero que no
 * cumple el contrato —ilegible o sin {@code code}—: ahí hubo intención de
 * responder y salió mal, así que es un defecto de integración y no un "no
 * existe".
 *
 * <h2>Lo que este cliente NO hace</h2>
 * No define timeouts ni reintenta, por decisión de diseño explícita del
 * pedido. Conviene tenerlo presente en operación: sin read timeout, una
 * llamada contra un proveedor que acepta la conexión y no contesta queda
 * colgada hasta que el sistema operativo corte, y el pedido de reserva que la
 * disparó queda colgado con ella. La clasificación de fallos de arriba es
 * justamente lo que permite agregar después —sin tocar esta clase— un
 * timeout en el {@code ClientHttpRequestFactory}, un reintento sólo para los
 * errores transitorios o un decorador que sirva el último valor cacheado
 * cuando el catálogo no responde.
 */
public class RestCityCatalogClient implements CityCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(RestCityCatalogClient.class);

    /** Recorte del cuerpo de error que va al log: alcanza para diagnosticar y no inunda. */
    private static final int MAX_ERROR_BODY = 512;

    private final RestClient restClient;

    public RestCityCatalogClient(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient, "El RestClient es obligatorio");
    }

    @Override
    public Optional<CatalogCity> findByCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("El código a consultar es obligatorio");
        }

        try {
            return restClient.get()
                    .uri("/city/{code}", code)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> classify(code, response));
        } catch (ResourceAccessException e) {
            // No hubo respuesta: conexión rechazada, DNS, socket cortado.
            log.warn("No se pudo contactar al catálogo para {}: {}", code, e.getMessage());
            throw new AirportCatalogUnavailableException(
                    "No se pudo contactar al catálogo para consultar '%s'".formatted(code), e);
        } catch (RestClientException e) {
            // Falla del cliente que no es de red: por ejemplo, no hay converter para la respuesta.
            log.error("Fallo del cliente HTTP consultando el catálogo para {}", code, e);
            throw new AirportCatalogIntegrationException(
                    "Fallo consultando el catálogo para '%s'".formatted(code), e);
        }
    }

    /**
     * Traduce la respuesta cruda al resultado del cliente.
     *
     * <p>Declara {@code IOException} porque leer el estado ya puede fallar; el
     * {@code RestClient} la envuelve en {@code ResourceAccessException}, que es
     * donde {@link #findByCode(String)} la clasifica como falla transitoria.
     */
    private Optional<CatalogCity> classify(String code, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();

        if (status.is2xxSuccessful()) {
            return readCity(code, response);
        }

        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            log.debug("El catálogo no conoce el código {}", code);
            return Optional.empty();
        }

        String body = errorBody(response);

        // 429 es 4xx, pero no es un defecto nuestro: es el proveedor pidiendo que bajemos el ritmo.
        if (status.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            log.warn("El catálogo nos está limitando (429) al consultar {}: {}", code, body);
            throw new AirportCatalogUnavailableException(
                    "El catálogo rechazó la consulta de '%s' por exceso de pedidos".formatted(code));
        }

        if (status.is5xxServerError()) {
            log.warn("El catálogo respondió {} al consultar {}: {}", status.value(), code, body);
            throw new AirportCatalogUnavailableException(
                    "El catálogo respondió %d al consultar '%s'".formatted(status.value(), code));
        }

        if (status.is4xxClientError()) {
            // Se loguea como error porque hay que cambiar algo: credencial, permisos o el pedido.
            log.error("El catálogo rechazó la consulta de {} con {}: {}", code, status.value(), body);
            throw new AirportCatalogIntegrationException(
                    "El catálogo rechazó la consulta de '%s' con estado %d".formatted(code, status.value()));
        }

        // 1xx/3xx acá no tienen sentido: el cliente sigue redirecciones por su cuenta.
        log.error("Respuesta inesperada {} del catálogo al consultar {}: {}", status.value(), code, body);
        throw new AirportCatalogIntegrationException(
                "Respuesta inesperada %d del catálogo al consultar '%s'".formatted(status.value(), code));
    }

    /**
     * Lee el cuerpo de un 2xx.
     *
     * <p>Cuerpo vacío es "no la conozco": así contesta hoy el catálogo a un
     * código desconocido. Cuerpo presente pero que no cumple el contrato
     * —ilegible o sin {@code code}— es otra cosa y ahí sí se falla: es la
     * diferencia entre un dato que no está y una integración rota.
     */
    private static Optional<CatalogCity> readCity(String code, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        CatalogCity city;
        try {
            city = response.bodyTo(CatalogCity.class);
        } catch (RestClientException e) {
            log.error("El catálogo devolvió 200 con un cuerpo ilegible para {}", code, e);
            throw new AirportCatalogIntegrationException(
                    "El catálogo devolvió un cuerpo ilegible para '%s'".formatted(code), e);
        }

        if (city == null) {
            // Se interpreta como "no la conozco", que es lo que este catálogo
            // quiere decir. Queda en WARN igual: el contrato exige un 404 y
            // mientras eso no se cumpla estamos leyendo una convención, no una
            // respuesta explícita.
            log.warn("El catálogo respondió {} sin cuerpo para {}: se toma como inexistente (el contrato exige 404)",
                    HttpStatus.OK.value(), code);
            return Optional.empty();
        }

        if (city.code() == null || city.code().isBlank()) {
            log.error("El catálogo devolvió 200 con un cuerpo sin 'code' para {}", code);
            throw new AirportCatalogIntegrationException(
                    "El catálogo devolvió una respuesta sin 'code' para '%s'".formatted(code));
        }
        return Optional.of(city);
    }

    /**
     * Extracto del cuerpo de error, sólo para el log.
     *
     * <p>No se deserializa contra un esquema: el contrato publicado tipa los
     * errores como {@code CityDTO}, que evidentemente no es lo que devuelven,
     * así que cualquier mapeo sería una suposición. Nunca sale hacia el cliente
     * de nuestra API: puede traer detalles internos del proveedor.
     */
    private static String errorBody(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            String body = response.bodyTo(String.class);
            if (body == null || body.isBlank()) {
                return "<sin cuerpo>";
            }
            return body.length() > MAX_ERROR_BODY ? body.substring(0, MAX_ERROR_BODY) + "…" : body;
        } catch (RestClientException e) {
            return "<cuerpo ilegible>";
        }
    }
}
