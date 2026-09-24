package com.edteam.reservations.observability;

import com.edteam.reservations.support.PublishedMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los dashboards también son código, y esto es lo que impide que se rompan en
 * silencio.
 *
 * <h2>Las tres formas en que un dashboard versionado falla sin avisar</h2>
 * <ol>
 *   <li><b>El datasource no existe.</b> Un panel apunta a un {@code uid} que en
 *       esta instalación no está y se abre vacío. Es lo que pasa cuando los
 *       datasources se provisionan sin {@code uid} fijo: Grafana genera uno
 *       aleatorio por instalación.</li>
 *   <li><b>La métrica no existe.</b> La consulta es válida, no da error, y
 *       devuelve vacío para siempre. Es el mismo fallo que tuvo la alerta del
 *       lag del outbox, que estaba escrita contra
 *       {@code reservations_outbox_lag} cuando la serie es
 *       {@code reservations_outbox_lag_seconds}.</li>
 *   <li><b>El archivo no se carga.</b> Un JSON inválido, o un {@code uid}
 *       repetido entre dos dashboards, y el provisioner descarta uno sin que
 *       nada falle.</li>
 * </ol>
 *
 * <p>Las tres se descubren mirando un panel vacío y preguntándose si está mal
 * el panel o si de verdad no pasó nada — que es exactamente la duda que un
 * tablero existe para no tener.
 *
 * <p>Lo que este test <b>no</b> cubre: que la consulta devuelva datos. Eso pide
 * la aplicación levantada y tráfico, y está en los comandos de verificación del
 * documento de implementación.
 */
@DisplayName("Dashboards de Grafana")
class GrafanaDashboardsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Path DASHBOARDS = Path.of("docker/grafana/dashboards");
    private static final Path DATASOURCES =
            Path.of("docker/grafana/provisioning/datasources/datasources.yml");
    private static final Path PROVIDER =
            Path.of("docker/grafana/provisioning/dashboards/dashboards.yml");

    private static Map<String, JsonNode> dashboards;
    private static Set<String> datasourceUids;

    @BeforeAll
    static void load() throws IOException {
        assertThat(DASHBOARDS)
                .withFailMessage("Los dashboards tienen que estar versionados junto al código")
                .isDirectory();

        dashboards = new LinkedHashMap<>();
        try (var files = Files.list(DASHBOARDS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                dashboards.put(file.getFileName().toString(), JSON.readTree(Files.readString(file)));
            }
        }
        assertThat(dashboards).as("dashboards encontrados").isNotEmpty();

        datasourceUids = declaredDatasourceUids();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> declaredDatasourceUids() throws IOException {
        Map<String, Object> document = new Yaml().load(Files.readString(DATASOURCES));
        List<Map<String, Object>> sources = (List<Map<String, Object>>) document.get("datasources");
        return sources.stream()
                .map(source -> String.valueOf(source.get("uid")))
                .collect(java.util.stream.Collectors.toSet());
    }

    static Stream<Map.Entry<String, JsonNode>> dashboards() {
        return dashboards.entrySet().stream();
    }

    @Test
    @DisplayName("los datasources se provisionan con uid fijo")
    void datasourcesHaveStableUids() {
        // Sin uid explícito, Grafana genera uno aleatorio en CADA instalación y
        // los paneles quedan apuntando a un datasource que no existe: el
        // dashboard se abre vacío en la máquina de otro y no en la de uno.
        assertThat(datasourceUids)
                .withFailMessage("Algún datasource se provisiona sin uid: %s", datasourceUids)
                .doesNotContain("null")
                .contains("prometheus", "loki", "tempo");
    }

    @Test
    @DisplayName("el provisioner lee la misma carpeta que el compose monta")
    void theProviderPathMatchesTheComposeMount() throws IOException {
        // Si las dos rutas se separan, el provisioner no encuentra nada y la
        // carpeta 'Reservas' aparece vacía, sin ningún error.
        String provider = Files.readString(PROVIDER);
        String compose = Files.readString(Path.of("compose.yaml"));

        assertThat(provider).contains("path: /var/lib/grafana/dashboards");
        assertThat(compose)
                .withFailMessage("El compose no monta docker/grafana/dashboards donde el provisioner lo busca")
                .contains("./docker/grafana/dashboards:/var/lib/grafana/dashboards");
    }

    @Test
    @DisplayName("la base de Grafana es efímera: si no, la contraseña del admin queda inerte")
    void grafanaDatabaseIsEphemeral() throws IOException {
        // GF_SECURITY_ADMIN_PASSWORD sólo se aplica cuando Grafana INICIALIZA
        // su grafana.db. Con un volumen persistente, el admin queda creado con
        // la contraseña del primer arranque y cambiar la variable después no
        // hace nada: el login falla sin ningún mensaje que lo explique.
        String compose = Files.readString(Path.of("compose.yaml"));

        assertThat(compose)
                .withFailMessage("Volvió el volumen persistente de Grafana: la credencial del "
                        + "admin va a quedar congelada en la del primer arranque")
                .doesNotContain("grafana-data:/var/lib/grafana");
        assertThat(compose).contains("GF_SECURITY_ADMIN_PASSWORD");
        // Y el formulario de login tiene que existir: con él apagado se entra a
        // mirar y no hay forma de iniciar sesión para explorar o guardar nada.
        assertThat(compose).contains("GF_AUTH_DISABLE_LOGIN_FORM: \"false\"");
    }

    @Test
    @DisplayName("ningún uid de dashboard está repetido")
    void dashboardUidsAreUnique() {
        // Dos dashboards con el mismo uid: el provisioner carga uno y descarta
        // el otro en silencio.
        List<String> uids = dashboards.values().stream()
                .map(dash -> dash.path("uid").asText())
                .toList();
        assertThat(uids).doesNotHaveDuplicates().doesNotContain("");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dashboards")
    @DisplayName("cada dashboard se identifica y se explica")
    void everyDashboardIsIdentified(Map.Entry<String, JsonNode> entry) {
        JsonNode dash = entry.getValue();
        assertThat(dash.path("uid").asText()).as("uid de %s", entry.getKey()).isNotBlank();
        assertThat(dash.path("title").asText()).as("title de %s", entry.getKey()).isNotBlank();
        // La descripción es lo que hace que un panel sirva a alguien que no lo
        // escribió: sin ella, un número sin contexto es un número.
        assertThat(dash.path("description").asText())
                .as("description de %s", entry.getKey())
                .hasSizeGreaterThan(40);
        assertThat(dash.path("panels")).as("paneles de %s", entry.getKey()).isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dashboards")
    @DisplayName("cada panel apunta a un datasource provisionado y se explica")
    void everyPanelPointsAtAProvisionedDatasource(Map.Entry<String, JsonNode> entry) {
        for (JsonNode panel : entry.getValue().path("panels")) {
            String title = panel.path("title").asText();
            if ("row".equals(panel.path("type").asText())) {
                continue;
            }
            String uid = panel.path("datasource").path("uid").asText();
            assertThat(uid)
                    .withFailMessage("El panel '%s' de %s apunta al datasource '%s', que no está "
                            + "provisionado. Provisionados: %s", title, entry.getKey(), uid, datasourceUids)
                    .isIn(datasourceUids);
            assertThat(panel.path("description").asText())
                    .withFailMessage("El panel '%s' de %s no explica qué decisión habilita: un número "
                            + "sin contexto no sirve a quien no escribió el panel", title, entry.getKey())
                    .hasSizeGreaterThan(40);
            assertThat(panel.path("targets"))
                    .withFailMessage("El panel '%s' de %s no tiene ninguna consulta", title, entry.getKey())
                    .isNotEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dashboards")
    @DisplayName("ninguna consulta nombra una serie que el código no publica")
    void everyMetricInAPanelExists(Map.Entry<String, JsonNode> entry) {
        List<String> unknown = new ArrayList<>();
        for (JsonNode panel : entry.getValue().path("panels")) {
            if ("row".equals(panel.path("type").asText())) {
                continue;
            }
            // Sólo las consultas de Prometheus: las de Loki nombran etiquetas
            // del esquema de log, no series.
            if (!"prometheus".equals(panel.path("datasource").path("uid").asText())) {
                continue;
            }
            for (JsonNode target : panel.path("targets")) {
                Matcher matcher = PublishedMetrics.REFERENCE.matcher(target.path("expr").asText());
                while (matcher.find()) {
                    if (!PublishedMetrics.NAMES.contains(matcher.group())) {
                        unknown.add(panel.path("title").asText() + " → " + matcher.group());
                    }
                }
            }
        }
        assertThat(unknown)
                .withFailMessage("En %s hay paneles que consultan series que el código no publica: %s.%n"
                        + "Una consulta así es válida, no da error y devuelve vacío PARA SIEMPRE. "
                        + "Ojo con los sufijos: los contadores llevan _total y los timers y los "
                        + "gauges con baseUnit llevan el de la unidad.", entry.getKey(), unknown)
                .isEmpty();
    }

    @Test
    @DisplayName("los paneles de logs consultan el servicio por su nombre real")
    void lokiPanelsQueryTheRightService() {
        // El valor sale del campo 'service' del esquema, que a su vez sale de
        // spring.application.name. Si se separan, el panel de logs queda vacío
        // y el de métricas no, que es el síntoma más confuso posible.
        List<String> lokiQueries = new ArrayList<>();
        for (JsonNode dash : dashboards.values()) {
            for (JsonNode panel : dash.path("panels")) {
                if (!"loki".equals(panel.path("datasource").path("uid").asText())) {
                    continue;
                }
                for (JsonNode target : panel.path("targets")) {
                    lokiQueries.add(target.path("expr").asText());
                }
            }
        }
        assertThat(lokiQueries).isNotEmpty();
        assertThat(lokiQueries).allSatisfy(query ->
                assertThat(query).contains("service=\"flight-reservations\""));
    }
}
