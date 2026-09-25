#!/usr/bin/env python3
"""Resumen legible del escaneo de vulnerabilidades en dependencias.

    python3 scripts/ci-security-summary.py target/trivy-report.json
    python3 scripts/ci-security-summary.py target/dependency-check/dependency-check-report.json

QUÉ PROBLEMA RESUELVE

Los dos escáneres producen informes que no se leen desde el navegador: el JSON
de Trivy tiene una entrada por vulnerabilidad con veinte campos cada una, y el
HTML de Dependency-Check pesa varios megas y hay que bajar el artefacto de la
corrida, descomprimirlo y abrirlo. Esa fricción convierte al gate en un
semáforo: verde se ignora, rojo se reintenta.

Esto escribe en `$GITHUB_STEP_SUMMARY` —el panel que GitHub muestra ARRIBA de
la corrida, sin abrir ningún log ni bajar nada— qué dependencia es, qué CVE, qué
puntaje y, cuando se sabe, EN QUÉ VERSIÓN ESTÁ ARREGLADO. Ese último dato es el
que convierte el informe en una acción.

POR QUÉ UN SOLO SCRIPT PARA DOS FORMATOS

Son dos escáneres con dos calendarios —Trivy en cada pull request, OWASP
Dependency-Check todas las noches— pero una sola pregunta: qué hay que subir de
versión. Dos scripts que contestan lo mismo con dos tablas distintas se
desincronizan, y el que corre de noche es el que nadie mira. El formato se
detecta por la forma del JSON, no por el nombre del archivo.

POR QUÉ SÓLO SE CUENTA LA PEOR CVE POR DEPENDENCIA

Una dependencia vulnerable suele arrastrar diez o quince CVE de la misma
familia, y la acción es UNA: subir la versión. Listarlas todas hace que un solo
problema ocupe media pantalla y que los otros tres problemas no se vean. El
detalle completo está en los artefactos de la corrida, que para eso se suben.

SALIDA
Siempre 0. Este script informa; quien falla el build es el escáner, en el paso
anterior —'exit-code: 1' en trivy.yaml, 'failBuildOnCVSS' en el pom—. Si saliera
distinto de 0 estaría duplicando el motivo del fallo y, peor, podría enrojecer
una corrida verde por un JSON que no pudo parsear.
"""

import json
import os
import sys

# El umbral que hace fallar el build. En Trivy es 'severity: [HIGH, CRITICAL]'
# (trivy.yaml); en Dependency-Check es '<failBuildOnCVSS>7</failBuildOnCVSS>'
# (pom.xml). Son el mismo corte: 7.0 es el piso de "High" en CVSS v3.
#
# Está acá sólo para marcar en la tabla cuáles son las que rompen; cambiarlo
# acá no cambia ningún gate.
FAIL_ON_CVSS = 7.0
FAIL_ON_SEVERITY = ("HIGH", "CRITICAL")

ORDER = {"CRITICAL": 0, "HIGH": 1, "MEDIUM": 2, "LOW": 3, "": 4}
ICON = {"CRITICAL": "🟣", "HIGH": "🔴", "MEDIUM": "🟠", "LOW": "🟡"}


# =============================================================================
# Trivy
# =============================================================================

def trivy_score(vuln):
    """El puntaje CVSS que reporta Trivy, prefiriendo el del NVD.

    Trivy trae un puntaje POR FUENTE ('nvd', 'ghsa', 'redhat', …) y no siempre
    coinciden: el mismo CVE puede ser 9.8 para el NVD y 7.5 para Red Hat, que
    puntúa en el contexto de su distribución. Se prefiere el del NVD por ser el
    mismo marco de referencia que usa el umbral, y si no está se toma el más
    alto disponible — subestimar un puntaje es el error caro de los dos.
    """
    fuentes = vuln.get("CVSS") or {}
    if not isinstance(fuentes, dict):
        return 0.0

    def de(nodo):
        for campo in ("V40Score", "V3Score", "V2Score"):
            valor = nodo.get(campo)
            if isinstance(valor, (int, float)):
                return float(valor)
        return 0.0

    nvd = fuentes.get("nvd")
    if isinstance(nvd, dict):
        puntaje = de(nvd)
        if puntaje:
            return puntaje

    puntajes = [de(n) for n in fuentes.values() if isinstance(n, dict)]
    return max(puntajes) if puntajes else 0.0


def leer_trivy(report):
    """Una fila por paquete vulnerable, con su peor CVE.

    Trivy agrupa por 'Result' (un target por ecosistema). Sobre un SBOM de un
    proyecto Maven hay uno solo, pero se recorren todos igual: el día que el
    mismo script mire una imagen de contenedor, ahí aparecen los paquetes del
    sistema operativo en un Result aparte.
    """
    por_paquete = {}
    total_cves = 0

    for resultado in report.get("Results") or []:
        for vuln in resultado.get("Vulnerabilities") or []:
            total_cves += 1
            nombre = vuln.get("PkgName") or "?"
            version = vuln.get("InstalledVersion") or ""
            clave = "{}@{}".format(nombre, version) if version else nombre

            severidad = (vuln.get("Severity") or "").upper()
            fila = {
                "name": clave,
                "cve": vuln.get("VulnerabilityID") or "?",
                "url": vuln.get("PrimaryURL") or "",
                "score": trivy_score(vuln),
                "severity": severidad if severidad in ORDER else "",
                # La versión que lo arregla. Vacía cuando todavía no hay
                # parche, y eso también es información: cambia la respuesta de
                # "subí la versión" a "mitigá o aceptalo por escrito".
                "fix": vuln.get("FixedVersion") or "",
                "count": 1,
            }

            previa = por_paquete.get(clave)
            if previa is None:
                por_paquete[clave] = fila
            else:
                previa["count"] += 1
                # Peor = mayor severidad y, a igual severidad, mayor puntaje.
                if (ORDER.get(fila["severity"], 4), -fila["score"]) < \
                   (ORDER.get(previa["severity"], 4), -previa["score"]):
                    fila["count"] = previa["count"]
                    por_paquete[clave] = fila
                elif not previa["fix"] and fila["fix"]:
                    previa["fix"] = fila["fix"]

    # Trivy no dice cuántos paquetes miró salvo que se le pida la lista
    # completa, que multiplicaría el tamaño del informe por veinte. Se informa
    # lo que sí se sabe.
    return list(por_paquete.values()), total_cves, None


# =============================================================================
# OWASP Dependency-Check
# =============================================================================

def dc_score(vuln):
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


def dc_severity(vuln, score):
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


def leer_dependency_check(report):
    rows = []
    total_cves = 0
    for dep in report.get("dependencies", []):
        vulns = dep.get("vulnerabilities") or []
        if not vulns:
            continue
        total_cves += len(vulns)
        worst = max(vulns, key=dc_score)
        score = dc_score(worst)
        cve = worst.get("name") or "?"
        rows.append({
            "name": dep.get("fileName") or "?",
            "cve": cve,
            "url": "https://nvd.nist.gov/vuln/detail/{}".format(cve),
            "score": score,
            "severity": dc_severity(worst, score),
            # Dependency-Check no reporta la versión que arregla: lo que sabe
            # es qué jar es vulnerable, no cuál es la salida.
            "fix": "",
            "count": len(vulns),
        })
    return rows, total_cves, len(report.get("dependencies", []))


# =============================================================================

def main(path):
    try:
        with open(path, encoding="utf-8") as fh:
            report = json.load(fh)
    except (OSError, json.JSONDecodeError) as exc:
        write(["## Seguridad de dependencias\n\nNo se pudo leer `{}`: {}\n".format(path, exc)])
        return

    # El formato se detecta por la forma del JSON y no por el nombre del
    # archivo: un informe renombrado seguiría parseándose bien, y un archivo
    # con el nombre correcto pero vacío da un mensaje claro en vez de "cero
    # vulnerabilidades", que es exactamente la mentira que hay que evitar.
    if "Results" in report or report.get("SchemaVersion"):
        escaner = "Trivy"
        rows, total_cves, escaneadas = leer_trivy(report)
    elif "dependencies" in report:
        escaner = "OWASP Dependency-Check"
        rows, total_cves, escaneadas = leer_dependency_check(report)
    else:
        write(["## Seguridad de dependencias\n\n`{}` no tiene la forma de un informe "
               "de Trivy ni de Dependency-Check. No se resume nada para no dar por "
               "bueno lo que no se pudo leer.\n".format(path)])
        return

    rows.sort(key=lambda r: (ORDER.get(r["severity"], 4), -r["score"]))
    blocking = [r for r in rows
                if r["score"] >= FAIL_ON_CVSS or r["severity"] in FAIL_ON_SEVERITY]

    if not rows:
        alcance = ("{} dependencias analizadas, ninguna".format(escaneadas)
                   if escaneadas else "Ninguna dependencia")
        write(["## ✅ Seguridad de dependencias — {}\n\n{} con vulnerabilidades "
               "conocidas.\n".format(escaner, alcance)])
        return

    out = []
    out.append("## {} Seguridad de dependencias — {}\n".format(
        "❌" if blocking else "⚠️", escaner))
    out.append("\n{} dependencia(s) con {} CVE conocida(s); {} {} el umbral "
               "y {} fallar el build.\n".format(
                   len(rows), total_cves, len(blocking),
                   "supera" if len(blocking) == 1 else "superan",
                   "hace" if len(blocking) == 1 else "hacen"))
    out.append("\n| | Dependencia | Peor CVE | CVSS | Arreglado en | CVE totales |\n")
    out.append("|---|---|---|---:|---|---:|\n")
    for r in rows:
        enlace = "[{}]({})".format(r["cve"], r["url"]) if r["url"] else "`{}`".format(r["cve"])
        out.append("| {} | `{}` | {} | {:.1f} | {} | {} |\n".format(
            ICON.get(r["severity"], "⚪"), r["name"], enlace, r["score"],
            "`{}`".format(r["fix"]) if r["fix"] else "—", r["count"]))

    for r in blocking:
        # Una anotación por dependencia bloqueante: GitHub las muestra arriba
        # de la corrida, antes del log.
        print("::error title=Dependencia vulnerable::{} — {} (CVSS {:.1f}){}".format(
            r["name"], r["cve"], r["score"],
            " — arreglado en {}".format(r["fix"]) if r["fix"] else ""))

    if blocking:
        if escaner == "Trivy":
            out.append(
                "\n**Qué hacer.** Subir la versión de la dependencia es la respuesta por "
                "defecto; la columna *Arreglado en* dice a cuál. Si es transitiva, fijarla "
                "con `<dependencyManagement>`. Si no aplica —la ruta vulnerable no se "
                "ejercita— anotarlo en `.trivyignore` **con su motivo y su fecha**, nunca "
                "sacando `HIGH` de `trivy.yaml`, que apaga el gate para todo lo demás.\n"
                "\nPara reproducirlo en tu máquina:\n"
                "\n```bash\n./mvnw cyclonedx:makeAggregateBom && trivy sbom target/bom.json\n```\n"
                "\nEl SBOM y el informe completo están en los artefactos de esta corrida.\n")
        else:
            out.append(
                "\n**Qué hacer.** Subir la versión de la dependencia es la respuesta por "
                "defecto; si es transitiva, fijarla con `<dependencyManagement>`. Si es un "
                "falso positivo —Dependency-Check identifica por huella y los produce más "
                "seguido que Trivy— suprimirlo en `owasp-suppressions.xml` **con su motivo y "
                "su fecha**, nunca bajando `failBuildOnCVSS`, que apaga el gate para todo lo "
                "demás.\n"
                "\nEl detalle por CVE, con la evidencia de por qué identificó cada jar como "
                "lo identificó, está en el informe HTML de los artefactos de esta corrida.\n")

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
    main(sys.argv[1] if len(sys.argv) > 1 else "target/trivy-report.json")
