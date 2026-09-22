package com.edteam.reservations.infrastructure.adapter.out.airport.catalog;

import com.edteam.reservations.application.exception.AirportCatalogUnavailableException;
import com.edteam.reservations.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogAirportCatalog")
class CatalogAirportCatalogTest {

    @Mock
    private CityCatalogClient client;

    @Test
    @DisplayName("hay ciudad: el aeropuerto existe")
    void existsWhenCatalogAnswers() {
        when(client.findByCode("EZE")).thenReturn(Optional.of(new CatalogCity("EZE", "Ezeiza")));

        assertThat(new CatalogAirportCatalog(client).exists(TestFixtures.EZE)).isTrue();
    }

    @Test
    @DisplayName("404 del catálogo: el aeropuerto no existe")
    void doesNotExistWhenCatalogReturnsEmpty() {
        when(client.findByCode("EZE")).thenReturn(Optional.empty());

        assertThat(new CatalogAirportCatalog(client).exists(TestFixtures.EZE)).isFalse();
    }

    /**
     * Lo importante del adaptador: no convierte una caída del proveedor en
     * "aeropuerto desconocido". Si esto cambiara, el sistema empezaría a
     * rechazar reservas válidas cada vez que el catálogo se cae.
     */
    @Test
    @DisplayName("catálogo caído: propaga el fallo, no responde 'no existe'")
    void propagatesFailure() {
        when(client.findByCode("EZE")).thenThrow(new AirportCatalogUnavailableException("caído"));

        assertThatThrownBy(() -> new CatalogAirportCatalog(client).exists(TestFixtures.EZE))
                .isInstanceOf(AirportCatalogUnavailableException.class);
    }

    @Test
    @DisplayName("código nulo: no llama al catálogo")
    void doesNotCallCatalogForNullCode() {
        assertThat(new CatalogAirportCatalog(client).exists(null)).isFalse();

        verifyNoInteractions(client);
    }
}
