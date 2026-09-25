#!/usr/bin/env python3
"""Resumen legible del informe de OWASP Dependency-Check.

    python3 scripts/ci-security-summary.py target/dependency-check/dependency-check-report.json

QUÉ PROBLEMA RESUELVE

El informe que produce Dependency-Check es un HTML de varios megas. Para
leerlo hay que bajar el artefacto de la corrida, descomprimirlo y abrirlo en un
navegador. Esa fricción convierte al gate en un semáforo: verde se ignora, rojo
se reintenta.

Esto escribe en `$GITHUB_STEP_SUMMARY` —el panel que GitHub muestra ARRIBA de
la corrida, sin abrir ningún log ni bajar nada— qué dependencia es, qué CVE, y
qué puntaje. Con eso alcanza para decidir si se sube una versión o si hay que
mirar el HTML.

POR QUÉ SÓLO SE CUENTA LA PEOR CVE POR DEPENDENCIA

Una dependencia vulnerable suele arrastrar diez o quince CVE de la misma
familia, y la acción es UNA: subir la versión. Listarlas todas hace que un solo
problema ocupe media pantalla y que los otros tres problemas no se vean. El
detalle completo está en el HTML, que para eso se sube como artefacto.

SALIDA
Siempre 0. Este script informa; quien falla el build es
'dependency-check:check' con 'failBuildOnCVSS', en el paso anterior. Si saliera
distinto de 0 estaría duplicando el motivo del fallo y, peor, podría enrojecer
una corrida verde por un JSON que no pudo parsear.
"""

import json
import os
import sys

# El umbral que hace fallar el build, declarado en el pom
# (<failBuildOnCVSS>7</failBuildOnCVSS>). Está acá sólo para marcar en la tabla
# cuáles son las que rompen; cambiarlo acá no cambia el gate.
FAIL_ON_CVSS = 7.0

ORDER = {"CRITICAL": 0, "HIGH": 1, "MEDIUM": 2, "LOW": 3, "": 4}
ICON = {"CRITICAL": "🟣", "HIGH": "🔴", "MEDIUM": "🟠", "LOW": "🟡"}


def score_of(vuln):
    """El puntaje CVSS, prefiriendo v3 sobre v2.

    Un mismo CVE tiene puntajes distintos en las dos versiones del estándar y
    'failBuildOnCVSS' compara contra el más alto disponible. Leer sólo v2
    subestimaría casi todo lo publicado después de 2016.
    """
    for key in ("cvssv4", "cvssv3"):
        node = vuln.get(key) or {}
        for field in ("baseScore", "cvssData"):
            value = node.get(field)
            if isinstance(value, dict):
                value = value.get("baseScore")
            if isinstance(value, (int, float)):
                return float(value)
    v2 = vuln.get("cvssv2") or {}
    if isinstance(v2.get("score"), (int, float)):
        return float(v2["score"])
    return 0.0


def severity_of(vuln, score):
    raw = (vuln.get("severity") or "").upper()
    # 'raw and' no es redundante: ORDER tiene una clave "" para ordenar lo
    # desconocido al final, así que sin esa guarda una CVE sin campo
    # 'severity' cortaría acá y volvería "" en vez de derivarse del puntaje.
    # El síntoma es engañoso: una CVSS 7.5 se mostraría como severidad
    # desconocida y al final de la tabla, justo debajo de las que sí importan.
    if raw and raw in ORDER:
        return raw
    # Algunas fuentes (OSSINDEX, RETIREJS) no traen 'severity': se deriva del
    # puntaje con los cortes de CVSS v3.
    if score >= 9.0:
        return "CRITICAL"
    if score >= 7.0:
        return "HIGH"
    if score >= 4.0:
        return "MEDIUM"
    return "LOW" if score > 0 else ""


def main(path):
    out = []
    try:
        with open(path, encoding="utf-8") as fh:
            report = json.load(fh)
    except (OSError, json.JSONDecodeError) as exc:
        write(["## Seguridad de dependencias\n\nNo se pudo leer `{}`: {}\n".format(path, exc)])
        return

    # Una fila por dependencia vulnerable, con su peor CVE.
    rows = []
    total_cves = 0
    for dep in report.get("dependencies", []):
        vulns = dep.get("vulnerabilities") or []
        if not vulns:
            continue
        total_cves += len(vulns)
        worst = max(vulns, key=score_of)
        score = score_of(worst)
        rows.append({
            "name": dep.get("fileName") or "?",
            "cve": worst.get("name") or "?",
            "score": score,
            "severity": severity_of(worst, score),
            "count": len(vulns),
        })

    rows.sort(key=lambda r: (ORDER.get(r["severity"], 4), -r["score"]))
    blocking = [r for r in rows if r["score"] >= FAIL_ON_CVSS]

    scanned = len(report.get("dependencies", []))
    if not rows:
        write(["## ✅ Seguridad de dependencias\n\n"
               "{} dependencias analizadas, ninguna con vulnerabilidades conocidas.\n".format(scanned)])
        return

    out.append("## {} Seguridad de dependencias — {} dependencia(s) con {} CVE conocida(s)\n".format(
        "❌" if blocking else "⚠️", len(rows), total_cves))
    out.append("\n{} de ellas superan el umbral CVSS ≥ {} y hacen fallar el build.\n".format(
        len(blocking), FAIL_ON_CVSS))
    out.append("\n| | Dependencia | Peor CVE | CVSS | CVE totales |\n")
    out.append("|---|---|---|---:|---:|\n")
    for r in rows:
        out.append("| {} | `{}` | [{}](https://nvd.nist.gov/vuln/detail/{}) | {:.1f} | {} |\n".format(
            ICON.get(r["severity"], "⚪"), r["name"], r["cve"], r["cve"], r["score"], r["count"]))

    for r in blocking:
        print("::error title=Dependencia vulnerable::{} — {} (CVSS {:.1f})".format(
            r["name"], r["cve"], r["score"]))

    if blocking:
        out.append(
            "\n**Qué hacer.** Subir la versión de la dependencia es la respuesta por defecto; "
            "si es transitiva, fijarla con `<dependencyManagement>`. Si es un falso positivo, "
            "suprimirlo en `owasp-suppressions.xml` **con su motivo y su fecha** — nunca bajando "
            "`failBuildOnCVSS`, que apaga el gate para todo lo demás.\n"
            "\nEl detalle por CVE está en el informe HTML, en los artefactos de esta corrida.\n")

    write(out)


def write(lines):
    body = "".join(lines)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(body)
    else:
        sys.stdout.write(body)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1
         else "target/dependency-check/dependency-check-report.json")
