package com.edteam.reservations.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.edteam.reservations.support.PublishedMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Las alertas están en el repositorio, y esto verifica que sean alertas.
 *
 * <h2>Qué se comprueba y por qué</h2>
 * El hallazgo 21 de la auditoría: una de las seis alertas del diseño
 * <b>no se podía escribir</b>. Su métrica no existía y su segunda condición
 * —«20 veces la línea de base del día anterior»— necesitaba una <i>recording
 * rule</i> que el diseño nunca definía. Una alerta que no se puede evaluar es
 * un panel con formato de alerta, y no se descubre hasta que alguien la
 * necesita.
 *
 * <p>Lo que este test sostiene es el contrato mínimo del §6: cada regla tiene
 * una métrica, un umbral, una ventana, una severidad, y —lo que la hace útil—
 * una <b>acción esperada</b>. El criterio del diseño es que si la respuesta a
 * «¿qué hace quien la recibe?» es «hay que investigar», no es una alerta.
 *
 * <p>No reemplaza a {@code promtool}, que es lo que valida la sintaxis de
 * PromQL de verdad; el comando está en el README y corre contra el contenedor
 * del profile {@code observability}. Esto es lo que corre en cada build y lo
 * que impide que una regla nueva entre sin su acción.
 */
@DisplayName("Reglas de alerta")
class AlertRulesTest {

    private static final Path RULES = Path.of("docker/prometheus/rules/reservations.yml");

    /** Las severidades del §6: P1 despierta a alguien, P2 abre un ticket. */
    private static final Set<String> SEVERITIES = Set.of("P1", "P2");

    private static List<Map<String, Object>> alerts;
    private static List<Map<String, Object>> recordings;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void parseTheRules() throws IOException {
        assertThat(RULES)
                .withFailMessage("Las alertas tienen que estar versionadas junto al código")
                .exists();
        Map<String, Object> document = new Yaml().load(Files.readString(RULES));
        List<Map<String, Object>> groups = (List<Map<String, Object>>) document.get("groups");

        alerts = new ArrayList<>();
        recordings = new ArrayList<>();
        for (Map<String, Object> group : groups) {
            for (Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
                if (rule.containsKey("alert")) {
                    alerts.add(rule);
                } else {
                    recordings.add(rule);
                }
            }
        }
    }

    static Stream<Map<String, Object>> alerts() {
        return alerts.stream();
    }

    @Test
    @DisplayName("hay al menos las seis alertas del diseño")
    void theDesignedAlertsAreThere() {
        assertThat(alerts).hasSizeGreaterThanOrEqualTo(6);
        assertThat(alerts)
                .extracting(rule -> rule.get("alert"))
                .contains(
                        "CatalogIntegrationBroken",
                        "WritesFailing",
                        "CreateReservationSlow",
                        "OutboxLagging",
                        "DeadNotifications",
                        "CredentialPressure",
                        // Las tres que el diseño no tenía y la auditoría pidió:
                        // las dos formas de perder notificaciones en silencio
                        // (hallazgo 19) y la métrica ciega (hallazgo 15).
                        "DevelopmentSecretsInUse",
                        "MessagingPublisherDisabled",
                        "OutboxMetricsBlind");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("alerts")
    @DisplayName("cada alerta tiene métrica, umbral, ventana, severidad y acción")
    void everyAlertIsComplete(Map<String, Object> rule) {
        String name = String.valueOf(rule.get("alert"));

        assertThat(rule.get("expr"))
                .withFailMessage("La alerta '%s' no tiene expresión", name)
                .isNotNull();
        // Un umbral: una comparación. Sin esto es un panel, no una alerta.
        assertThat(String.valueOf(rule.get("expr")))
                .withFailMessage("La alerta '%s' no compara contra ningún umbral", name)
                .containsPattern("[<>=]");
        assertThat(rule.get("for"))
                .withFailMessage(
                        "La alerta '%s' no declara ventana: una alerta sin 'for' " + "dispara con un pico de un scrape",
                        name)
                .isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> labels = (Map<String, Object>) rule.get("labels");
        assertThat(labels)
                .withFailMessage("La alerta '%s' no tiene etiquetas", name)
                .isNotNull();
        assertThat(String.valueOf(labels.get("severity")))
                .withFailMessage(
                        "La alerta '%s' tiene una severidad fuera de {P1, P2}: %s", name, labels.get("severity"))
                .isIn(SEVERITIES);

        @SuppressWarnings("unchecked")
        Map<String, Object> annotations = (Map<String, Object>) rule.get("annotations");
        assertThat(annotations)
                .withFailMessage("La alerta '%s' no tiene anotaciones", name)
                .isNotNull();
        // Las dos preguntas que el §6 exige responder para que una alerta
        // exista: qué está roto para el usuario, y qué hace quien la recibe.
        assertThat(annotations).containsKeys("summary", "impact", "action");
        assertThat(String.valueOf(annotations.get("action")))
                .withFailMessage("La acción de '%s' es demasiado corta para ser accionable", name)
                .hasSizeGreaterThan(40);
    }

    @Test
    @DisplayName("la alerta de credenciales se apoya en una recording rule que existe")
    void theCredentialAlertHasItsBaseline() {
        // El hallazgo 21, cerrado: la comparación contra la línea de base del
        // día anterior necesita una serie grabada, porque PromQL no tiene un
        // operador que compare una serie consigo misma desplazada.
        String expression = alerts.stream()
                .filter(rule -> "CredentialPressure".equals(rule.get("alert")))
                .map(rule -> String.valueOf(rule.get("expr")))
                .findFirst()
                .orElseThrow();

        assertThat(expression).contains("offset 1d");
        List<String> recorded = recordings.stream()
                .map(rule -> String.valueOf(rule.get("record")))
                .toList();
        assertThat(recorded)
                .withFailMessage("La alerta usa %s y ninguna recording rule la define", expression)
                .contains("reservations:auth_failures:rate10m");
        assertThat(expression).contains("reservations:auth_failures:rate10m");
    }

    @Test
    @DisplayName("la alerta de escrituras no cuenta el 500 del catálogo dos veces")
    void theWriteFailureAlertDoesNotDoublePage() {
        // El hallazgo 20: el 500 con AIRPORT_CATALOG_ERROR entra al numerador
        // de esta alerta Y dispara la alerta 1. Dos P1 a la vez por la misma
        // causa es la forma más rápida de que la segunda se empiece a ignorar.
        String expression = alerts.stream()
                .filter(rule -> "WritesFailing".equals(rule.get("alert")))
                .map(rule -> String.valueOf(rule.get("expr")))
                .findFirst()
                .orElseThrow();

        assertThat(expression)
                .withFailMessage("La alerta 2 tiene que apoyarse en reservations_operations_total, "
                        + "que separa las causas, y no en el status HTTP crudo")
                .contains("reservations_operations_total");
    }

    @Test
    @DisplayName("ninguna expresión usa una métrica que el código no publica")
    void everyMetricInARuleExists() {
        // El otro lado del hallazgo 17: una regla escrita contra un nombre que
        // el código no publica devuelve vacío y no dispara nunca, y eso se
        // descubre el día del incidente. Ya pasó una vez, con el sufijo de la
        // unidad del lag del outbox.
        //
        // La lista de series vive en PublishedMetrics y la comparten este test
        // y el de los dashboards: son los dos consumidores de los mismos
        // nombres, y tenerla dos veces es tenerla desalineada.
        List<String> unknown = new ArrayList<>();
        for (Map<String, Object> rule : alerts) {
            java.util.regex.Matcher matcher = PublishedMetrics.REFERENCE.matcher(String.valueOf(rule.get("expr")));
            while (matcher.find()) {
                if (!PublishedMetrics.NAMES.contains(matcher.group())) {
                    unknown.add(rule.get("alert") + " → " + matcher.group());
                }
            }
        }
        assertThat(unknown)
                .withFailMessage("Estas reglas usan métricas que el código no publica: %s", unknown)
                .isEmpty();
    }
}
