#!/usr/bin/env python3
"""Verifica que el análisis de Sonar haya medido algo, y lo resume en el panel.

    python3 scripts/ci-sonar-summary.py

Lee del entorno: SONAR_PROJECT_KEY, SONAR_HOST_URL, SONAR_TOKEN (opcional para
proyectos públicos), SONAR_BRANCH o SONAR_PULL_REQUEST, y REPO_DEFAULT_BRANCH.

QUÉ PROBLEMA RESUELVE

'sonar.qualitygate.wait=true' hace que el trabajo espere el veredicto del
quality gate y falle si sale rojo. Eso convierte al análisis en un gate de
verdad… siempre que el gate tenga algo que juzgar.

El perfil "Sonar way" juzga CÓDIGO NUEVO. Si Sonar no puede calcular qué es
nuevo —porque la rama que analizó está registrada como de vida corta contra una
baseline que no existe, o porque el análisis llegó degradado— el resultado es
"0 New Lines of Code" y TODAS las condiciones pasan por vacuidad. El panel dice
"Passed", el trabajo sale verde, y nadie midió nada.

Eso ya pasó en este repositorio: SonarCloud tenía 'master' como rama principal
—una rama que no existe en el repositorio— y registraba 'main' como rama de
vida corta apuntando a ella. La cobertura se subía perfecto (86,3%) y el gate no
evaluaba una sola línea.

Un verde que no significa nada es peor que un rojo: el rojo se arregla.

QUÉ VERIFICA, Y POR QUÉ CADA COSA

  A. Que la rama analizada sea la principal de Sonar, cuando se analizó la rama
     por defecto del repositorio. Si no lo es, el gate está juzgando un diff
     contra una baseline que puede no existir.
  B. Que exista la medida de cobertura. Es la comprobación literal de que el
     jacoco.xml llegó: si el artefacto no trajo el informe, o la ruta del pom
     no coincide, acá se ve.
  C. Que el análisis no haya dejado warnings. Un warning de Sonar casi siempre
     significa "analicé, pero degradado" — y un análisis degradado presentado
     como un gate que pasa es exactamente el modo de falla que este script
     existe para cerrar.

SALIDA
0 si el análisis midió lo que tenía que medir; 1 si no. A diferencia de los
otros dos scripts de resumen, ÉSTE SÍ FALLA: no está informando sobre un gate
que ya corrió, está verificando que ese gate haya sido un gate.
"""

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

TIMEOUT = 30

METRICAS = [
    "coverage",
    "line_coverage",
    "branch_coverage",
    "ncloc",
    "tests",
    "new_coverage",
    "new_lines",
    "duplicated_lines_density",
]


def pedir(host, ruta, parametros, token):
    url = "{}/api/{}?{}".format(host.rstrip("/"), ruta, urllib.parse.urlencode(parametros))
    pedido = urllib.request.Request(url)
    # El token va en la cabecera, no en la query: una URL termina en los logs
    # de cualquier proxy que haya en el medio, y este proceso corre en un
    # runner compartido.
    if token:
        pedido.add_header("Authorization", "Bearer {}".format(token))
    with urllib.request.urlopen(pedido, timeout=TIMEOUT) as respuesta:
        return json.load(respuesta)


def valor(medida):
    """El valor de una medida, que Sonar devuelve de dos formas distintas.

    Las métricas de código nuevo no traen 'value' sino 'period'/'periods': el
    mismo JSON, dos formas según la métrica y la versión del servidor. Leer
    sólo 'value' haría que toda la columna de código nuevo apareciera vacía.
    """
    if "value" in medida:
        return medida["value"]
    periodo = medida.get("period")
    if isinstance(periodo, dict) and "value" in periodo:
        return periodo["value"]
    periodos = medida.get("periods") or []
    if periodos and "value" in periodos[0]:
        return periodos[0]["value"]
    return None


def main():
    clave = os.environ.get("SONAR_PROJECT_KEY", "")
    host = os.environ.get("SONAR_HOST_URL", "https://sonarcloud.io")
    token = os.environ.get("SONAR_TOKEN", "")
    rama = os.environ.get("SONAR_BRANCH", "")
    pr = os.environ.get("SONAR_PULL_REQUEST", "")
    rama_por_defecto = os.environ.get("REPO_DEFAULT_BRANCH", "main")

    if not clave:
        return fallar(["Falta `SONAR_PROJECT_KEY`. Sin la clave del proyecto no hay nada que consultar."])

    # En un pull request el análisis no va a una rama sino al PR, y las
    # métricas se piden con otro parámetro. Mezclarlos devuelve las de la rama
    # base, que es un número real de otro commit — la peor clase de error.
    alcance = {"pullRequest": pr} if pr else {"branch": rama}

    try:
        medidas_raw = pedir(host, "measures/component",
                            dict(component=clave, metricKeys=",".join(METRICAS), **alcance), token)
        estado = pedir(host, "ce/analysis_status", dict(component=clave, **alcance), token)
        ramas = pedir(host, "project_branches/list", dict(project=clave), token) if not pr else None
    except (urllib.error.URLError, json.JSONDecodeError, OSError) as exc:
        return fallar(["No se pudo consultar la API de Sonar (`{}`): {}".format(host, exc),
                       "",
                       "El análisis puede haber estado bien; lo que falló es la verificación. "
                       "Este script sale 1 igual: un chequeo que no se pudo hacer no es un chequeo que pasó."])

    medidas = {m["metric"]: valor(m) for m in medidas_raw.get("component", {}).get("measures", [])}
    advertencias = estado.get("component", {}).get("warnings", []) or []

    problemas = []

    # --- A. ¿La rama analizada es la principal? -------------------------------
    principal = None
    if ramas:
        for b in ramas.get("branches", []):
            if b.get("isMain"):
                principal = b.get("name")
        if rama == rama_por_defecto and principal and principal != rama:
            problemas.append(
                "**Sonar no considera `{rama}` la rama principal: para él lo es `{principal}`.**\n\n"
                "Eso registra a `{rama}` como rama de vida corta y hace que el quality gate juzgue "
                "sólo el *diff* contra `{principal}`. Si esa rama no existe en el repositorio, el diff "
                "es vacío, **todas las condiciones pasan por vacuidad** y el gate deja de frenar "
                "cualquier cosa.\n\n"
                "Se arregla en SonarQube Cloud, no acá:\n\n"
                "```bash\n"
                "# 1. Borrar la rama de vida corta que ocupa el nombre\n"
                "curl -u \"$SONAR_TOKEN:\" -X POST \\\n"
                "  '{host}/api/project_branches/delete?project={clave}&branch={rama}'\n"
                "\n"
                "# 2. Renombrar la rama principal\n"
                "curl -u \"$SONAR_TOKEN:\" -X POST \\\n"
                "  '{host}/api/project_branches/rename?project={clave}&name={rama}'\n"
                "```\n\n"
                "Después, volver a correr este workflow sobre `{rama}`."
                .format(rama=rama, principal=principal, host=host.rstrip("/"), clave=clave))

    # --- B. ¿Llegó la cobertura? ---------------------------------------------
    if medidas.get("coverage") is None:
        problemas.append(
            "**Sonar no tiene ninguna medida de cobertura para este análisis.**\n\n"
            "Sonar no mide cobertura: la importa. Que no esté significa que no leyó el "
            "`jacoco.xml` — o no llegó en el artefacto `sonar-input`, o la ruta de "
            "`<sonar.coverage.jacoco.xmlReportPaths>` en el `pom.xml` no es donde JaCoCo lo "
            "escribió. Verificable con:\n\n"
            "```bash\n./mvnw verify && ls -la target/site/jacoco-merged/jacoco.xml\n```")

    # --- C. ¿El análisis salió limpio? ---------------------------------------
    for advertencia in advertencias:
        texto = advertencia if isinstance(advertencia, str) else advertencia.get("message", str(advertencia))
        # Sonar manda HTML adentro de sus warnings; acá sólo estorba.
        texto = texto.replace("<br>", " ")
        for etiqueta in ('<a href="', '" rel="noopener noreferrer" target="_blank">', "</a>"):
            texto = texto.replace(etiqueta, " ")
        problemas.append("**Sonar dejó un warning de análisis:** {}".format(" ".join(texto.split())))

    escribir(panel(clave, rama or "PR #{}".format(pr), medidas, problemas))
    return 1 if problemas else 0


def panel(clave, alcance, medidas, problemas):
    out = []
    out.append("## {} SonarQube Cloud — `{}`\n".format("❌" if problemas else "📊", alcance))

    def fila(etiqueta, metrica, sufijo=""):
        v = medidas.get(metrica)
        return "| {} | {} |\n".format(etiqueta, "—" if v is None else "{}{}".format(v, sufijo))

    out.append("\n| | |\n|---|---:|\n")
    out.append(fila("Cobertura", "coverage", " %"))
    out.append(fila("Cobertura de líneas", "line_coverage", " %"))
    out.append(fila("Cobertura de ramas", "branch_coverage", " %"))
    out.append(fila("Tests", "tests"))
    out.append(fila("Líneas de código", "ncloc"))
    out.append(fila("Líneas nuevas", "new_lines"))
    out.append(fila("Cobertura del código nuevo", "new_coverage", " %"))
    out.append(fila("Duplicación", "duplicated_lines_density", " %"))

    if problemas:
        out.append("\n### El análisis corrió, pero no midió lo que tenía que medir\n")
        for p in problemas:
            out.append("\n{}\n".format(p))
            print("::error title=Análisis de Sonar degradado::{}".format(
                " ".join(p.replace("*", "").split("\n")[0].split())[:400]))
    return out


def fallar(lineas):
    escribir(["## ❌ SonarQube Cloud\n\n"] + ["{}\n".format(l) for l in lineas])
    print("::error title=Verificación de Sonar::{}".format(lineas[0][:400]))
    return 1


def escribir(lineas):
    cuerpo = "".join(lineas)
    ruta = os.environ.get("GITHUB_STEP_SUMMARY")
    if ruta:
        with open(ruta, "a", encoding="utf-8") as fh:
            fh.write(cuerpo)
    else:
        sys.stdout.write(cuerpo)


if __name__ == "__main__":
    sys.exit(main())
