package com.edteam.reservations.infrastructure.security;

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
                                                               Clock clock) throws Exception {
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
                        .authenticationEntryPoint(ProblemDetailAuthenticationHandlers.entryPoint(objectMapper))
                        .accessDeniedHandler(
                                ProblemDetailAuthenticationHandlers.accessDeniedHandler(objectMapper)))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(ProblemDetailAuthenticationHandlers.entryPoint(objectMapper))
                        .accessDeniedHandler(
                                ProblemDetailAuthenticationHandlers.accessDeniedHandler(objectMapper)))
                // Después de la autenticación: así la cuota se cuenta por
                // identidad cuando la hay, y recién cae a la IP cuando no.
                .addFilterAfter(new RateLimitFilter(properties.rateLimit(), objectMapper, clock),
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
     */
    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Integer.MIN_VALUE);
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
