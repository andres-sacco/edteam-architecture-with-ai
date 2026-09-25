package com.edteam.reservations.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edteam.reservations.application.port.in.CancelReservationUseCase;
import com.edteam.reservations.application.port.in.CreateReservationUseCase;
import com.edteam.reservations.application.port.in.GetReservationUseCase;
import com.edteam.reservations.application.port.in.ListReservationsUseCase;
import com.edteam.reservations.application.port.in.ModifyReservationUseCase;
import com.edteam.reservations.infrastructure.adapter.in.rest.mapper.ReservationRestMapper;
import com.edteam.reservations.infrastructure.config.OpenApiConfiguration;
import com.edteam.reservations.infrastructure.security.SecurityConfiguration;
import com.edteam.reservations.support.WebSliceConfiguration;
import com.edteam.reservations.support.WithMockActor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regenera {@code docs/api/openapi.yaml} a partir del código.
 *
 * <p>El archivo versionado es la copia que leen los partners; la fuente de
 * verdad sigue siendo el código. Esto es lo que hace que regenerarlo sea un
 * comando y no «levantar la aplicación, apuntarle un curl y pegar el
 * resultado»:
 *
 * <pre>{@code
 * ./mvnw test -Dtest=OpenApiDocumentDumpTest -Dopenapi.dump=true
 * }</pre>
 *
 * <p>Apagado por defecto a propósito: un test que escribe en el repositorio no
 * puede correr en cada build. Que el documento generado y el código no se
 * separen lo verifica {@code OpenApiContractTest}, que sí corre siempre.
 */
@WebMvcTest(properties = {"reservations.security.rate-limit.enabled=false", "springdoc.api-docs.enabled=true"})
@Import({
    ReservationRestMapper.class,
    OpenApiConfiguration.class,
    SecurityConfiguration.class,
    WebSliceConfiguration.class
})
@ImportAutoConfiguration({
    SpringDocConfiguration.class,
    SpringDocConfigProperties.class,
    SpringDocWebMvcConfiguration.class
})
@WithMockActor
@EnabledIfSystemProperty(named = "openapi.dump", matches = "true")
@DisplayName("Regeneración del contrato versionado")
class OpenApiDocumentDumpTest {

    private static final Path TARGET = Path.of("docs", "api", "openapi.yaml");

    private static final String HEADER = """
            # Contrato de la API de Reservas de Vuelos (OpenAPI).
            #
            # Generado por springdoc a partir de los controllers, los DTOs y sus anotaciones
            # de Bean Validation. No editar a mano: la fuente de verdad es el codigo.
            #
            # Para regenerarlo:
            #   ./mvnw test -Dtest=OpenApiDocumentDumpTest -Dopenapi.dump=true

            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateReservationUseCase createReservation;

    @MockitoBean
    private GetReservationUseCase getReservation;

    @MockitoBean
    private ListReservationsUseCase listReservations;

    @MockitoBean
    private ModifyReservationUseCase modifyReservation;

    @MockitoBean
    private CancelReservationUseCase cancelReservation;

    @MockitoBean
    private ReservationVersionCache versionCache;

    @Test
    @DisplayName("escribe docs/api/openapi.yaml")
    void dumpsTheDocument() throws Exception {
        String yaml = mockMvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        Files.writeString(TARGET, HEADER + yaml);

        assertThat(Files.readString(TARGET)).contains("bearerAuth").contains("/v1/reservations");
    }
}
