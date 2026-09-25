#!/usr/bin/env python3
"""Resumen legible de una corrida de tests, para el panel del workflow.

    python3 scripts/ci-test-summary.py target/surefire-reports [target/failsafe-reports ...]

QUÉ PROBLEMA RESUELVE

El log de un `mvn verify` de este repositorio son ~800 tests y varios miles de
líneas. Cuando uno falla, la información que hace falta —cuál, y por qué— está
ahí adentro, y encontrarla cuesta abrir el log completo, esperar a que el
navegador lo renderice y buscar. Eso es suficiente fricción para que la
reacción por defecto sea «rerun» en vez de «leer».

Esto escribe en `$GITHUB_STEP_SUMMARY` —el panel que GitHub muestra ARRIBA de
la corrida, sin abrir ningún log— una tabla con el estado de cada suite y, para
lo que falló, el mensaje de la aserción.

LOS DOS GUARDIANES

`HexagonalArchitectureTest` y `OpenApiContractTest` tienen tratamiento aparte,
al principio del resumen y con el mensaje completo. No son tests más
importantes por capricho: son los dos que custodian invariantes que ningún otro
test detecta y que, rotos, no se manifiestan como un error sino como una
erosión. Una dependencia de infraestructura que se filtró al dominio compila y
pasa todos los demás tests; un endpoint que ya no coincide con el contrato
publicado también. Si el resumen los entierra entre otras ochenta suites, el
build igual queda rojo pero nadie entiende por qué, y la salida más rápida pasa
a ser borrar la regla.

Además emite `::error::`, que es lo que hace que GitHub ponga la anotación en
la pestaña de la corrida y en el PR.

SALIDA
Siempre 0. Este script informa; quien falla el build es Maven, en el paso
anterior. Si saliera distinto de 0 estaría duplicando el motivo del fallo y,
peor, podría enrojecer una corrida verde por un XML malformado.
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET

# Las clases cuyo fallo se destaca arriba de todo, con el mensaje entero.
GUARDS = {
    "HexagonalArchitectureTest": (
        "ArchUnit: la infraestructura se filtró hacia adentro. "
        "El dominio o la capa de aplicación están importando algo que no pueden ver."
    ),
    "OpenApiContractTest": (
        "El documento OpenAPI que genera springdoc dejó de describir esta API "
        "(endpoints, códigos de estado o esquemas de seguridad)."
    ),
}

MAX_MSG_LINES = 25


def parse(path):
    """Lee un TEST-*.xml de surefire/failsafe y devuelve (clase, totales, fallos)."""
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return None

    suite = root.get("name") or os.path.basename(path)
    simple = suite.rsplit(".", 1)[-1]

    # Se cuentan los <testcase> y NO los atributos del <testsuite> raíz.
    # Motivo concreto: para una clase con @Nested —hay once en este
    # repositorio— surefire escribe tests="0" en la raíz y mete los casos
    # reales en <testsuite> anidados. Confiar en el atributo de la raíz reporta
    # 595 de los 803 casos que la suite corre, y deja 208 tests fuera del
    # resumen: entre ellos, TODO ReservationControllerTest (44). Un resumen que
    # no muestra un test no muestra que ese test falló.
    cases = list(root.iter("testcase"))
    totals = {
        "tests": len(cases),
        "failures": sum(1 for c in cases if c.find("failure") is not None),
        "errors": sum(1 for c in cases if c.find("error") is not None),
        "skipped": sum(1 for c in cases if c.find("skipped") is not None),
        "time": float(root.get("time") or 0.0),
    }

    failures = []
    for case in cases:
        for kind in ("failure", "error"):
            node = case.find(kind)
            if node is None:
                continue
            body = (node.get("message") or node.text or "").strip()
            lines = body.splitlines()[:MAX_MSG_LINES]
            failures.append(
                {
                    "test": case.get("name") or "?",
                    "classname": case.get("classname") or suite,
                    "kind": kind,
                    "message": "\n".join(lines).strip(),
                    "truncated": len(body.splitlines()) > MAX_MSG_LINES,
                }
            )
    return simple, suite, totals, failures


def main(dirs):
    suites = []
    for d in dirs:
        for f in sorted(glob.glob(os.path.join(d, "TEST-*.xml"))):
            parsed = parse(f)
            if parsed:
                suites.append(parsed)

    out = []
    if not suites:
        out.append("## Tests\n\nNo se encontró ningún XML de resultados en: "
                   + ", ".join(f"`{d}`" for d in dirs) + "\n")
        write(out)
        return

    agg = {k: 0 for k in ("tests", "failures", "errors", "skipped")}
    total_time = 0.0
    for _, _, t, _ in suites:
        for k in agg:
            agg[k] += t[k]
        total_time += t["time"]

    broken = [s for s in suites if s[3]]
    ok = agg["failures"] == 0 and agg["errors"] == 0

    out.append("## {} Tests — {} ejecutados, {} fallas, {} errores, {} salteados ({:.0f} s)\n".format(
        "✅" if ok else "❌", agg["tests"], agg["failures"], agg["errors"], agg["skipped"], total_time))

    # --- Guardianes, primero y completos ------------------------------------
    for simple, fqn, _, failures in broken:
        if simple not in GUARDS:
            continue
        out.append("\n### 🛑 `{}`\n".format(simple))
        out.append("\n> {}\n".format(GUARDS[simple]))
        for f in failures:
            out.append("\n**{}**\n".format(f["test"]))
            out.append("\n```text\n{}{}\n```\n".format(
                f["message"] or "(sin mensaje)",
                "\n… (mensaje recortado; el completo está en el log y en el artefacto)" if f["truncated"] else ""))
            first = (f["message"].splitlines() or ["falló"])[0]
            print("::error title={}::{}".format(simple, sanitize(first)))

    # --- El resto de lo que falló -------------------------------------------
    others = [s for s in broken if s[0] not in GUARDS]
    if others:
        out.append("\n### Otras suites en rojo\n")
        for simple, fqn, _, failures in others:
            out.append("\n<details><summary><code>{}</code> — {} caso(s)</summary>\n\n".format(
                simple, len(failures)))
            for f in failures:
                out.append("**{}** ({})\n\n```text\n{}\n```\n\n".format(
                    f["test"], f["kind"], f["message"] or "(sin mensaje)"))
                print("::error title={}::{} — {}".format(
                    simple, f["test"], sanitize((f["message"].splitlines() or [""])[0])))
            out.append("</details>\n")

    # --- Tabla de todas las suites ------------------------------------------
    out.append("\n<details><summary>Detalle por suite ({})</summary>\n\n".format(len(suites)))
    out.append("| | Suite | Tests | Fallas | Errores | Salteados | Tiempo |\n")
    out.append("|---|---|---:|---:|---:|---:|---:|\n")
    for simple, fqn, t, failures in sorted(suites, key=lambda s: (not s[3], s[0])):
        out.append("| {} | `{}` | {} | {} | {} | {} | {:.1f} s |\n".format(
            "❌" if failures else "✅", simple,
            t["tests"], t["failures"], t["errors"], t["skipped"], t["time"]))
    out.append("\n</details>\n")

    write(out)


def sanitize(text):
    """Las anotaciones son de una línea: los saltos y los '::' rompen el comando."""
    return text.replace("\r", " ").replace("\n", " ").replace("::", ":").strip()[:400]


def write(lines):
    body = "".join(lines)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(body)
    else:
        # Fuera de Actions (corrida local) se imprime, que es lo útil ahí.
        sys.stdout.write(body)


if __name__ == "__main__":
    main(sys.argv[1:] or ["target/surefire-reports"])
