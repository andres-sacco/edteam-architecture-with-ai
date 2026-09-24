package com.edteam.reservations.infrastructure.adapter.out.airport.catalog;

import com.edteam.reservations.application.exception.AirportCatalogException;
import com.edteam.reservations.application.exception.AirportCatalogIntegrationException;
import com.edteam.reservations.application.exception.AirportCatalogThrottledException;
import com.edteam.reservations.application.exception.AirportCatalogUnavailableException;
import com.edteam.reservations.infrastructure.adapter.out.airport.CachingAirportCatalog;
import com.edteam.reservations.infrastructure.logging.LogFields;
import com.edteam.reservations.infrastructure.logging.LogSanitizer;
import com.edteam.reservations.infrastructure.logging.Throwables;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.time.Duration;
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
 * No reintenta ni define timeouts, y eso es a propósito: esta clase traduce
 * una respuesta HTTP a un resultado, y nada más. Las tres decisiones de
 * resiliencia viven afuera, y cada una se apoya en la clasificación de arriba
 * sin volver a mirar un código de estado:
 *
 * <ul>
 *   <li><b>Timeouts</b> → en el {@code ClientHttpRequestFactory} que arma
 *       {@code AdapterConfiguration} (500 ms de conexión, 2 s de lectura). Un
 *       vencimiento llega acá como {@code ResourceAccessException}, o sea
 *       como fallo transitorio, que es lo que es.</li>
 *   <li><b>Reintentos</b> → {@link RetryingCityCatalogClient}, que envuelve a
 *       esta clase y repite sólo lo transitorio, con espera exponencial y
 *       jitter.</li>
 *   <li><b>Último valor conocido ante una caída</b> →
 *       {@code CachingAirportCatalog}, más arriba todavía.</li>
 * </ul>
 *
 * <p>Que estén separadas es lo que permite testear la traducción HTTP contra
 * un servidor simulado sin esperar backoffs, y cambiar la política de
 * reintentos sin tocar una sola línea de clasificación.
 */
public class RestCityCatalogClient implements CityCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(RestCityCatalogClient.class);

    /** Recorte del cuerpo de error que va al log: alcanza para diagnosticar y no inunda. */
    private static final int MAX_ERROR_BODY = 512;

    /**
     * Latencia y resultado de <b>cada</b> llamada al catálogo.
     *
     * <p>Hasta acá sólo se medía el itinerario entero
     * ({@code reservations.catalog.fanout}), que con 8 ciudades en paralelo no
     * distingue «una tardó 6 s» de «las ocho tardaron 700 ms». Son dos
     * problemas distintos: el primero es el proveedor, el segundo es nuestro
     * presupuesto. Esa es la decisión que esta métrica habilita y la otra no.
     *
     * <p>La etiqueta es {@code outcome} y nada más. El código IATA <b>no</b> es
     * etiqueta: son ~9.000 valores posibles y los elige el cliente en el cuerpo
     * del pedido, o sea cardinalidad controlada por un tercero. Qué ciudad
     * falló lo responde el log, que sí lo lleva como campo.
     */
    public static final String CALL_DURATION = "reservations.catalog.call";

    /**
     * El valor de {@code dependency} en el log.
     *
     * <p>Se toma de {@code CachingAirportCatalog} y no se escribe a mano: es el
     * mismo nombre que sale en el header {@code X-Degraded} y en las etiquetas
     * de {@code reservations.degraded.*} y {@code reservations.requests.degraded}.
     * Dos nombres para la misma dependencia rompen el cruce entre el panel y el
     * log, que es justamente lo que el esquema viene a habilitar —y fue lo que
     * pasó: el ejemplo del diseño decía {@code api-catalog} y el código ya venía
     * publicando {@code airport-catalog}—.
     */
    private static final String DEPENDENCY = CachingAirportCatalog.DEPENDENCY;

    /** Plantilla, no la URI concreta: es el mismo criterio que la ruta HTTP de entrada. */
    private static final String OPERATION = "GET /city/{code}";

    private final RestClient restClient;
    private final MeterRegistry registry;

    public RestCityCatalogClient(RestClient restClient, MeterRegistry registry) {
        this.restClient = Objects.requireNonNull(restClient, "El RestClient es obligatorio");
        this.registry = Objects.requireNonNull(registry, "El registro de métricas es obligatorio");
    }

    /** Sin métricas: es el que usan los tests de la traducción HTTP. */
    public RestCityCatalogClient(RestClient restClient) {
        this(restClient, new SimpleMeterRegistry());
    }

    @Override
    public Optional<CatalogCity> findByCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("El código a consultar es obligatorio");
        }

        long startedAt = System.nanoTime();
        try {
            Optional<CatalogCity> city = restClient.get()
                    .uri("/city/{code}", code)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> classify(code, response));
            String outcome = city.isPresent() ? "found" : "absent";
            record(outcome, startedAt);
            // El camino feliz vive en DEBUG y no en INFO: con hasta 8 ciudades
            // por POST, un INFO por consulta son 40.000 líneas por día para
            // decir lo que el timer de arriba dice mejor y agregado. Cuando
            // una falla, la línea sí se escribe.
            call(log.atDebug(), code, outcome).log("Catálogo consultado");
            return city;
        } catch (ResourceAccessException e) {
            // No hubo respuesta: conexión rechazada, DNS, socket cortado. Es
            // WARN porque hay fallback y el circuito lo cuenta: el usuario
            // recibe su reserva y nadie tiene que levantarse.
            record("unavailable", startedAt);
            call(log.atWarn(), code, "unavailable")
                    .addKeyValue(LogFields.EXCEPTION_CLASS, Throwables.rootClassOf(e))
                    .addKeyValue(LogFields.REASON, Throwables.reasonOf(e))
                    .log("No se pudo contactar al catálogo");
            throw new AirportCatalogUnavailableException(
                    "No se pudo contactar al catálogo para consultar '%s'".formatted(code), e);
        } catch (AirportCatalogException e) {
            // Ya clasificada y ya logueada en classify(): se deja pasar sin
            // escribir una segunda línea del mismo hecho. Una sola línea por
            // llamada es también lo que hace que el techo de severidad por
            // escenario sea acotable.
            record(outcomeOf(e), startedAt);
            throw e;
        } catch (RestClientException e) {
            // Falla del cliente que no es de red: por ejemplo, no hay converter
            // para la respuesta. El mensaje de Jackson incrusta un fragmento
            // del cuerpo del proveedor, así que pasa por el redactor: es el
            // hallazgo 5 de la auditoría, que dejaba abierto justo el vector
            // que LogSanitizer existe para cerrar.
            record("integration", startedAt);
            call(log.atError(), code, "integration")
                    .addKeyValue(LogFields.EXCEPTION_CLASS, Throwables.rootClassOf(e))
                    .addKeyValue(LogFields.REASON, Throwables.reasonOf(e))
                    .log("Fallo del cliente HTTP consultando el catálogo");
            throw new AirportCatalogIntegrationException(
                    "Fallo consultando el catálogo para '%s'".formatted(code), e);
        }
    }

    /**
     * Los campos comunes de {@code event=catalog.call}.
     *
     * <p>Están en un solo lugar para que las diez llamadas de esta clase no
     * puedan salir con juegos distintos de campos, que es exactamente lo que
     * la auditoría encontró: {@code cityCode} viajaba adentro del texto en las
     * catorce líneas del catálogo, con un formato de mensaje por línea.
     */
    private static LoggingEventBuilder call(LoggingEventBuilder event, String code, String outcome) {
        return event.addKeyValue(LogFields.EVENT, LogFields.CATALOG_CALL)
                .addKeyValue(LogFields.DEPENDENCY, DEPENDENCY)
                .addKeyValue(LogFields.OPERATION, OPERATION)
                .addKeyValue(LogFields.CITY_CODE, code)
                .addKeyValue(LogFields.OUTCOME, outcome);
    }

    private void record(String outcome, long startedAt) {
        Timer.builder(CALL_DURATION)
                .tags(Tags.of(LogFields.OUTCOME, outcome))
                .description("Latencia y resultado de una consulta al catálogo, por llamada")
                .register(registry)
                .record(Duration.ofNanos(System.nanoTime() - startedAt));
    }

    private static String outcomeOf(AirportCatalogException e) {
        if (e instanceof AirportCatalogThrottledException) {
            return "throttled";
        }
        return e instanceof AirportCatalogIntegrationException ? "integration" : "unavailable";
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
            call(log.atDebug(), code, "absent")
                    .addKeyValue(LogFields.HTTP_STATUS, status.value())
                    .log("El catálogo no conoce el código");
            return Optional.empty();
        }

        String body = errorBody(response);

        // 429 es 4xx, pero no es un defecto nuestro: es el proveedor pidiendo
        // que bajemos el ritmo. Se lanza un tipo propio para que el
        // clasificador pueda decidir lo que la tabla del diseño dice y antes
        // no se podía expresar: SÍ cuenta para el circuito —que es lo que de
        // verdad frena el tráfico— y NO se reintenta, porque insistir es
        // desobedecer al proveedor y empeorar su saturación.
        if (status.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            call(log.atWarn(), code, "throttled")
                    .addKeyValue(LogFields.HTTP_STATUS, status.value())
                    .addKeyValue(LogFields.REASON, body)
                    .log("El catálogo nos está limitando");
            throw new AirportCatalogThrottledException(
                    "El catálogo rechazó la consulta de '%s' por exceso de pedidos".formatted(code));
        }

        if (status.is5xxServerError()) {
            call(log.atWarn(), code, "unavailable")
                    .addKeyValue(LogFields.HTTP_STATUS, status.value())
                    .addKeyValue(LogFields.REASON, body)
                    .log("El catálogo respondió con error de servidor");
            throw new AirportCatalogUnavailableException(
                    "El catálogo respondió %d al consultar '%s'".formatted(status.value(), code));
        }

        if (status.is4xxClientError()) {
            // Acá se separa lo que la auditoría encontró mezclado (hallazgo 27).
            //
            // 401 y 403 son una credencial: no hay reintento que lo arregle,
            // ningún POST ni PUT puede completarse y hace falta una persona
            // ahora. Eso es ERROR, y detrás tiene la alerta 1.
            //
            // 400, 409, 422 y compañía son contrato o un código mal armado:
            // repetible, sin dueño humano inmediato, y se mira por TASA. Eso
            // es WARN. Con hasta 8 ciudades por POST, un desajuste sistemático
            // de contrato en ERROR eran 40.000 ERROR por día en el escenario
            // de volumen del propio diseño, y un ERROR que aparece cien veces
            // por hora deja de significar algo.
            boolean credential = status.value() == HttpStatus.UNAUTHORIZED.value()
                    || status.value() == HttpStatus.FORBIDDEN.value();
            call(credential ? log.atError() : log.atWarn(), code, "integration")
                    .addKeyValue(LogFields.HTTP_STATUS, status.value())
                    .addKeyValue(LogFields.REASON, body)
                    .addKeyValue("integration.kind", credential ? "credential" : "contract")
                    .log(credential
                            ? "El catálogo rechazó la credencial"
                            : "El catálogo rechazó la consulta por contrato");
            throw new AirportCatalogIntegrationException(
                    "El catálogo rechazó la consulta de '%s' con estado %d".formatted(code, status.value()));
        }

        // 1xx/3xx acá no tienen sentido: el cliente sigue redirecciones por su cuenta.
        call(log.atError(), code, "integration")
                .addKeyValue(LogFields.HTTP_STATUS, status.value())
                .addKeyValue(LogFields.REASON, body)
                .addKeyValue("integration.kind", "unexpected_status")
                .log("Respuesta inesperada del catálogo");
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
    private Optional<CatalogCity> readCity(String code, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        CatalogCity city;
        try {
            city = response.bodyTo(CatalogCity.class);
        } catch (RestClientException e) {
            // Sin `e` como causa del log: el mensaje de Jackson incrusta el
            // fragmento del cuerpo que no pudo parsear, y ese cuerpo lo elige
            // el proveedor. Va redactado y saneado, como el resto.
            call(log.atError(), code, "integration")
                    .addKeyValue(LogFields.HTTP_STATUS, HttpStatus.OK.value())
                    .addKeyValue(LogFields.EXCEPTION_CLASS, Throwables.rootClassOf(e))
                    .addKeyValue(LogFields.REASON, Throwables.reasonOf(e))
                    .addKeyValue("integration.kind", "unreadable_body")
                    .log("El catálogo devolvió 200 con un cuerpo ilegible");
            throw new AirportCatalogIntegrationException(
                    "El catálogo devolvió un cuerpo ilegible para '%s'".formatted(code), e);
        }

        if (city == null) {
            // Se interpreta como "no la conozco", que es lo que este catálogo
            // quiere decir. Queda en WARN igual: el contrato exige un 404 y
            // mientras eso no se cumpla estamos leyendo una convención, no una
            // respuesta explícita.
            call(log.atWarn(), code, "absent")
                    .addKeyValue(LogFields.HTTP_STATUS, HttpStatus.OK.value())
                    .addKeyValue(LogFields.REASON, "cuerpo vacío; el contrato exige 404")
                    .log("El catálogo respondió sin cuerpo: se toma como inexistente");
            return Optional.empty();
        }

        if (city.code() == null || city.code().isBlank()) {
            call(log.atWarn(), code, "integration")
                    .addKeyValue(LogFields.HTTP_STATUS, HttpStatus.OK.value())
                    .addKeyValue("integration.kind", "contract")
                    .log("El catálogo devolvió un cuerpo sin 'code'");
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
    /**
     * Cuerpo de error del proveedor, listo para loguear.
     *
     * <p>Truncar no alcanzaba. En un log de texto el separador de registros es
     * el salto de línea, así que un cuerpo con {@code \n} no agrega una línea
     * a nuestro registro: agrega registros enteros. Un proveedor comprometido
     * —o simplemente uno que devuelve un HTML de error— puede fabricar
     * entradas que parezcan nuestras, justo en el lugar donde después se busca
     * evidencia. {@link LogSanitizer} neutraliza los caracteres de control
     * además de acotar el largo.
     */
    private static String errorBody(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            // Redactado además de saneado: un cuerpo de error de un tercero
            // puede traer un email o un token que nos devuelve tal cual el que
            // le mandamos, y el saneado sólo neutraliza los caracteres de
            // control. El truncado sigue estando, y sigue siendo una defensa
            // de costo tanto como de legibilidad.
            return Throwables.redact(
                    LogSanitizer.sanitize(response.bodyTo(String.class), MAX_ERROR_BODY));
        } catch (RestClientException e) {
            return "<cuerpo ilegible>";
        }
    }
}
