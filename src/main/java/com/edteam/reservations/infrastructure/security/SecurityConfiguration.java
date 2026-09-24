package com.edteam.reservations.infrastructure.security;

import com.edteam.reservations.infrastructure.observability.SecurityMetrics;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Clock;
import java.util.List;

/**
 * La capa de seguridad del borde HTTP.
 *
 * <p>Es un adaptador, no una capa transversal: vive en
 * {@code infrastructure.security}, y ni el dominio ni la aplicación la
 * importan —lo verifica {@code HexagonalArchitectureTest}—. Lo que decide acá
 * es <b>quién puede llegar a un endpoint</b>. Lo que decide el dominio es
 * <b>quién puede ver o tocar una reserva concreta</b>, que es una regla de
 * negocio y por eso está en {@code ReservationAccessPolicy}.
 *
 * <h2>{@code denyAll()} por defecto</h2>
 * La última regla de la cadena niega todo. Es la diferencia entre una lista de
 * lo que está protegido y una lista de lo que está abierto: con la primera, un
 * endpoint nuevo nace desprotegido y nadie se entera hasta que alguien lo
 * encuentra; con la segunda, nace cerrado y el que lo publica tiene que decir
 * explícitamente para quién.
 *
 * <h2>Sin sesión y sin CSRF</h2>
 * El estado es el token, que viaja en un header que el navegador no adjunta
 * solo. Sin cookie de sesión no hay CSRF que prevenir: un formulario de un
 * sitio hostil no puede hacer que el navegador ponga un {@code Authorization}
 * que no tiene. Desactivar CSRF acá es correcto <em>porque</em> no hay sesión;
 * el día que aparezca una cookie, hay que volver a encenderlo.
 *
 * <h2>Superficie expuesta</h2>
 * <ul>
 *   <li><b>Swagger UI y el documento</b>: apagados por defecto
 *       ({@code springdoc.api-docs.enabled} y
 *       {@code springdoc.swagger-ui.enabled}), así que en producción no
 *       existen. Encendidos, se sirven sin token porque exigirlo los volvería
 *       inusables sin proteger nada: ver {@link #API_DOCS}.</li>
 *   <li><b>Actuator</b>: se mueve a un puerto de gestión propio
 *       ({@code management.server.port}), que no se publica hacia afuera. Sólo
 *       las sondas de liveness/readiness quedan sin autenticación, porque las
 *       consulta el orquestador y no dicen nada: un {@code UP} o un
 *       {@code DOWN}.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    /** Sondas del orquestador. No revelan nada y tienen que responder sin credencial. */
    private static final String[] PROBES = {"/actuator/health", "/actuator/health/**"};

    /**
     * El endpoint que raspa el recolector de métricas.
     *
     * <p>Se abre sólo con {@code reservations.security.metrics-scrape-open=true},
     * y el default es {@code false}.
     *
     * <h2>Por qué hace falta la propiedad</h2>
     * El §7.2 del diseño da por hecho que Prometheus «alcanza el puerto 9090 y
     * listo». No es así: la cadena de seguridad también cubre el contexto de
     * gestión, así que el scrape recibe un 401 y el target queda en
     * {@code DOWN} — verificado contra el contenedor del profile
     * {@code observability}, que es donde se descubrió.
     *
     * <p>Las alternativas eran peores. Un token en el {@code prometheus.yml}
     * es una credencial de larga vida escrita en un archivo del repositorio, y
     * además hay que rotarla; y abrirlo sin condición contradice la decisión
     * que este archivo ya tomó y documentó («que Actuator exija token en el
     * puerto de la aplicación es cinturón y tirantes»).
     *
     * <h2>Qué se está aceptando al encenderla</h2>
     * {@code /actuator/prometheus} revela volumetría de negocio —cuántas
     * reservas por minuto, qué porcentaje falla— y la superficie real de la
     * API, incluidos los endpoints que el contrato no documenta. Encenderla es
     * defendible <b>sólo</b> mientras el puerto de gestión no se publique,
     * que es la mitigación estructural que el {@code application.yml} ya
     * explica. En local, el puerto lo alcanza un contenedor de la red de
     * Docker y nada más.
     */
    private static final String[] METRICS_SCRAPE = {"/actuator/prometheus"};

    /**
     * El contrato y su interfaz.
     *
     * <p>Van sin token, y la razón es que ponérselo no protege nada: lo rompe.
     * Un pedido de navegación del navegador no puede llevar un header
     * {@code Authorization}, así que el 401 llegaría <em>antes</em> de que
     * exista la pantalla donde apretar «Authorize»; y la UI busca el documento
     * por XHR sin credencial, con lo que quedaría en «Failed to load API
     * definition». El resultado sería una UI que no se puede usar, que es otra
     * forma de no tenerla —pero con la falsa sensación de que está protegida—.
     *
     * <p>El control real es que <b>en producción estos endpoints no existen</b>:
     * {@code springdoc.api-docs.enabled} y {@code springdoc.swagger-ui.enabled}
     * están en {@code false} por defecto, así que responden 404 y no hay regla
     * de autorización que acertar. Es la mitigación que pide el modelo de
     * amenazas para T-08: apagarlos fuera de los entornos donde sirven, y
     * publicar el contrato desde el archivo versionado
     * ({@code docs/api/openapi.yaml}) y no desde el runtime.
     *
     * <p>Lo que la UI sí necesita para operar sigue cerrado: el botón «Try it
     * out» pega contra {@code /v1/**}, que exige token como cualquier otro
     * cliente.
     */
    private static final String[] API_DOCS = {"/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
            "/swagger-ui.html", "/swagger-ui/**"};

    private static final long HSTS_SECONDS = 31_536_000L;

    @Bean
    public SecurityFilterChain reservationsSecurityFilterChain(HttpSecurity http,
                                                               JwtDecoder jwtDecoder,
                                                               SecurityProperties properties,
                                                               ObjectMapper objectMapper,
                                                               Clock clock,
                                                               SecurityMetrics securityMetrics,
                                                               @Value("${reservations.security.metrics-scrape-open:false}")
                                                               boolean metricsScrapeOpen) throws Exception {
        if (metricsScrapeOpen) {
            log.atWarn()
                    .addKeyValue("event", "startup.wiring")
                    .addKeyValue("component", "security")
                    .addKeyValue("actuator.prometheus", "anonymous")
                    .log("El endpoint de métricas se sirve SIN token: sólo es aceptable "
                            + "mientras el puerto de gestión no se publique hacia afuera");
        }
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource(properties.cors())))
                .headers(headers -> headers
                        // El transporte lo termina el borde; el header se
                        // emite igual para que el navegador no vuelva a
                        // intentar por HTTP aunque alguien reescriba un link.
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(HSTS_SECONDS))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .contentTypeOptions(Customizer.withDefaults()))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(PROBES).permitAll()
                        .requestMatchers(METRICS_SCRAPE)
                        .access((authentication, context) -> new org.springframework.security.authorization
                                .AuthorizationDecision(metricsScrapeOpen
                                || (authentication.get() != null && authentication.get().isAuthenticated())))
                        // El preflight no lleva credencial por definición.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(API_DOCS).permitAll()
                        // Actuator: en producción vive en el puerto de
                        // gestión, que no se publica. Que en el puerto de la
                        // aplicación exija token igual es cinturón y
                        // tirantes: el día que alguien lo exponga por error,
                        // 'metrics' —que revela volumetría de negocio— no
                        // queda abierto. Las sondas se permiten arriba.
                        .requestMatchers("/actuator/**").authenticated()
                        .requestMatchers("/v1/**").authenticated()
                        // Todo lo que no esté nombrado arriba, incluido lo que
                        // se agregue mañana, está cerrado.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(new JwtActorConverter()))
                        .authenticationEntryPoint(
                                ProblemDetailAuthenticationHandlers.entryPoint(objectMapper, securityMetrics))
                        .accessDeniedHandler(ProblemDetailAuthenticationHandlers
                                .accessDeniedHandler(objectMapper, securityMetrics)))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(
                                ProblemDetailAuthenticationHandlers.entryPoint(objectMapper, securityMetrics))
                        .accessDeniedHandler(ProblemDetailAuthenticationHandlers
                                .accessDeniedHandler(objectMapper, securityMetrics)))
                // Después de la autenticación: así la cuota se cuenta por
                // identidad cuando la hay, y recién cae a la IP cuando no.
                .addFilterAfter(new RateLimitFilter(properties.rateLimit(), objectMapper, clock, securityMetrics),
                        BasicAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(SecurityProperties properties) {
        JwtDecoderFactory.requireSecureJwkSetUri(properties.jwt());
        return JwtDecoderFactory.create(properties.jwt());
    }

    /**
     * El filtro del correlation id, fuera de la cadena de seguridad y antes que
     * ella: un 401 también tiene que poder rastrearse.
     *
     * <p>{@code DispatcherType.ERROR} además de {@code REQUEST}: sin eso, un
     * 400 de parseo del contenedor o un 404 sin handler pasan por
     * {@code /error} —otro dispatch, con el MDC ya limpio— y salen sin id y
     * sin el header de respuesta (hallazgo 13). {@code OncePerRequestFilter}
     * no filtra el dispatch de error por defecto, y el registro no lo pedía.
     */
    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Integer.MIN_VALUE);
        registration.setDispatcherTypes(java.util.EnumSet.of(jakarta.servlet.DispatcherType.REQUEST,
                jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR));
        return registration;
    }

    /**
     * CORS por allowlist explícita.
     *
     * <p>Nunca {@code *}: la API se consume con credenciales, y el navegador
     * directamente rechaza la combinación de comodín con
     * {@code allow-credentials}. Más importante que la regla del navegador es
     * la razón: con comodín, cualquier página que el usuario abra puede
     * disparar pedidos a la API desde su sesión.
     */
    private static CorsConfigurationSource corsConfigurationSource(SecurityProperties.Cors properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!properties.enabled()) {
            log.info("CORS apagado: no hay 'reservations.security.cors.allowed-origins'");
            return source;
        }
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE,
                HttpHeaders.IF_MATCH, HttpHeaders.IF_NONE_MATCH, "Idempotency-Key",
                CorrelationIdFilter.HEADER));
        // Sin esto el frontend no ve el ETag y no puede mandar If-Match: el
        // navegador sólo expone seis headers de respuesta por defecto.
        // X-Degraded incluido: si el frontend no lo ve, no puede avisar que los
        // datos de catálogo están desactualizados, y el header no sirve de nada.
        configuration.setExposedHeaders(List.of(HttpHeaders.ETAG, HttpHeaders.LOCATION,
                HttpHeaders.RETRY_AFTER, CorrelationIdFilter.HEADER,
                com.edteam.reservations.infrastructure.adapter.in.rest.DegradationHeaderFilter.HEADER));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(properties.maxAge());

        source.registerCorsConfiguration("/v1/**", configuration);
        log.info("CORS habilitado para {}", properties.allowedOrigins());
        return source;
    }
}
