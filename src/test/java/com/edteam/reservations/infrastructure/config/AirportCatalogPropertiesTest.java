package com.edteam.reservations.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Configuración del catálogo de ciudades")
class AirportCatalogPropertiesTest {

    private static AirportCatalogProperties withBaseUrl(String baseUrl) {
        return new AirportCatalogProperties(null, null, null, null, null, null, baseUrl, null, null);
    }

    @Test
    @DisplayName("exige HTTPS hacia el proveedor: la API key viaja en un header")
    void requiresHttpsForRemoteCatalogs() {
        assertThat(withBaseUrl("https://catalog.example/api").usesSecureTransport()).isTrue();
        assertThat(withBaseUrl("http://catalog.example/api").usesSecureTransport())
                .as("por http:// la credencial se lee en el camino, y con ella se consulta en nuestro nombre")
                .isFalse();
    }

    @Test
    @DisplayName("localhost queda exento: es el contenedor de al lado en la máquina de desarrollo")
    void allowsLocalhostOverPlainHttp() {
        // Exigirle un certificado sólo lograría que alguien apague la
        // verificación entera para poder trabajar.
        assertThat(withBaseUrl("http://localhost:6070/api").usesSecureTransport()).isTrue();
        assertThat(withBaseUrl("http://127.0.0.1:6070/api").usesSecureTransport()).isTrue();
    }

    @Test
    @DisplayName("sin catálogo remoto no hay transporte que asegurar")
    void theInMemoryStubNeedsNoTransport() {
        assertThat(withBaseUrl("").usesSecureTransport()).isTrue();
        assertThat(withBaseUrl(null).usesSecureTransport()).isTrue();
        assertThat(withBaseUrl("").hasRemoteCatalog()).isFalse();
    }
}
