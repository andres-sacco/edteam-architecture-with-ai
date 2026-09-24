#!/bin/sh
# Gate de datos sensibles sobre la salida capturada de la suite.
#
# Corre como paso de `verify` (ver el exec-maven-plugin del pom.xml) y también
# a mano:
#
#   ./mvnw verify                      # lo corre solo, al final
#   ./scripts/pii-log-gate.sh target   # sobre una corrida anterior
#
# QUÉ HACE
# Grepea los archivos de salida que surefire y failsafe escriben
# —`target/*-reports/*-output.txt`, gracias a `redirectTestOutputToFile`—
# contra el juego de patrones prohibidos. Una sola coincidencia falla el build.
#
# POR QUÉ HACE FALTA, ADEMÁS DE LOS TESTS
# Los tests con `ListAppender` afirman sobre los registros que UNA prueba
# provoca. Esto mira TODO lo que la suite escribió, incluido lo que ninguna
# aserción está mirando: el arranque del contexto, los caminos de error que
# nadie provocó a propósito, y cualquier línea que se agregue mañana. La
# auditoría reprodujo la fuga con un grep exactamente así; la diferencia entre
# esa inspección y esto es que esto falla el build.
#
# LO QUE NO CUBRE, Y POR QUÉ
#
# 1. Cubre lo que sabemos reconocer. Un dato personal con un formato que no
#    está en la lista pasa sin que nadie se entere: es una red, no una
#    garantía. Lo que de verdad cierra el vector es que el camino por defecto
#    —`Throwables.redact`, `PiiMasker`, `LogSanitizer`— sea el seguro.
#
# 2. Sólo mira NUESTROS registros. Con el DEBUG de Spring y de Hibernate
#    encendido, el framework escribe entidades JPA enteras y el cuerpo
#    serializado de cada respuesta (`Writing [ReservationResponse[...]]`, con
#    el email adentro, porque el contrato de la API expone el email como
#    `userId`). Eso es dato personal en un log y NO se arregla escribiendo
#    mejor nuestras líneas: se arregla no encendiendo esos paquetes en
#    producción, que es lo que `logging.level` de `application.yml` ya fija.
#    La exclusión está acá, explícita, en vez de ser una omisión.
set -eu

REPORTS="${1:-target}"
FOUND=0

if [ ! -d "$REPORTS" ]; then
    echo "pii-gate: no hay '$REPORTS'; corré './mvnw verify' primero." >&2
    exit 0
fi

OUTPUTS=$(find "$REPORTS" -name '*-output.txt' -path '*-reports/*' 2>/dev/null || true)
if [ -z "$OUTPUTS" ]; then
    echo "pii-gate: no se capturó salida de tests (¿redirectTestOutputToFile?). Nada que revisar." >&2
    exit 0
fi

# Los registros de loggers de terceros: ver el punto 2 de arriba. Se filtran por
# el campo `logger` del propio esquema JSON, que es exactamente el dato que el
# esquema existe para poder filtrar.
FOREIGN='"logger":"(o\.|io\.|org\.|com\.zaxxer|com\.github|net\.|jakarta\.|ch\.qos|_org\.)'

FILES=$(mktemp)
# shellcheck disable=SC2086
cat $OUTPUTS 2>/dev/null | grep -vE "$FOREIGN" > "$FILES" || true
trap 'rm -f "$FILES"' EXIT

echo "pii-gate: revisando $(echo "$OUTPUTS" | wc -l | tr -d ' ') archivo(s), $(wc -l < "$FILES" | tr -d ' ') línea(s) propias…"

# nombre|patrón, uno por línea. Se leen con `read` y NO con un `for`: un `for`
# sobre una variable parte los patrones en cada espacio, y `[- ]` tiene uno
# adentro — el patrón del documento quedaba roto en dos y no matcheaba nada,
# que es la peor forma de que un gate falle: en verde.
check() {
    name="$1"
    pattern="$2"
    hits=$(grep -nE "$pattern" "$FILES" 2>/dev/null || true)
    if [ -n "$hits" ]; then
        FOUND=1
        echo ""
        echo "pii-gate: FALLA — patrón '$name' encontrado en la salida de la suite:"
        echo "$hits" | cut -c1-240 | head -5
        total=$(echo "$hits" | wc -l | tr -d ' ')
        [ "$total" -gt 5 ] && echo "  … y $((total - 5)) coincidencia(s) más"
    fi
}

check email       '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'
check jwt         'eyJ[A-Za-z0-9_-]{10,}'
check authorization '[Aa]uthorization[[:space:]]*[:=][[:space:]]*Bearer[[:space:]]+[^[:space:]]+'
check pii-key     'ZGV2LW9ubHk'
check passenger-name '(P[eé]rez|D[ií]az)'
check document    'DNI[- ]?[0-9]{7,9}'
check pg-detail   '[Dd]etail:[[:space:]]*Key[[:space:]]*\([^)]*\)[[:space:]]*=[[:space:]]*\([^)]+\)'

if [ "$FOUND" -ne 0 ]; then
    echo ""
    echo "Un dato sensible en un log es un dato replicado a un sistema indexado, con"
    echo "otra retención y otro perímetro que la base. Se arregla en el ORIGEN —que el"
    echo "mensaje no lo lleve— y no filtrando en el recolector."
    exit 1
fi

echo "pii-gate: sin coincidencias."
