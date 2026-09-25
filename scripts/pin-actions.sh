#!/usr/bin/env bash
# Resuelve a SHA las acciones de .github/workflows/*.yml.
#
#   ./scripts/pin-actions.sh            # reescribe los archivos
#   ./scripts/pin-actions.sh --check    # sólo verifica (para el propio CI)
#
# POR QUÉ SE FIJA POR SHA Y NO POR ETIQUETA
# Una etiqueta de git es un puntero móvil. 'uses: alguien/accion@v4' resuelve a
# lo que ESE repositorio diga que es v4 en el momento en que corre el workflow,
# y quien tenga permiso de push ahí puede reapuntarla en cualquier momento. La
# acción corre en el mismo runner que el checkout del código y, en los trabajos
# que despliegan, con acceso al token OIDC: reapuntar una etiqueta es ejecución
# de código arbitrario dentro del pipeline. Un SHA no se puede reapuntar.
#
# El comentario al lado del SHA no es decorativo: es lo único que deja leer qué
# versión es, y es lo que este script usa para resolverla.
#
# Con las acciones ya fijadas, el uso normal es "--check" desde el propio CI.
#
# Formato esperado en el YAML:
#     uses: owner/repo@<sha40>   # vX.Y.Z — comentario libre
set -euo pipefail

CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

command -v gh >/dev/null || { echo "hace falta la CLI 'gh' autenticada"; exit 1; }

rc=0
for file in .github/workflows/*.yml .github/workflows/*.yaml; do
    [ -e "$file" ] || continue

    # owner/repo, sha actual y versión del comentario, de cada línea 'uses:'.
    while IFS=$'\t' read -r repo current version; do
        [ -n "${repo:-}" ] || continue

        resolved="$(gh api "repos/${repo}/git/ref/tags/${version}" --jq '.object.sha' 2>/dev/null || true)"
        # Una etiqueta anotada apunta a un objeto 'tag', no al commit: hay que
        # desreferenciarla o se fija el SHA del tag y 'uses' no lo acepta.
        type="$(gh api "repos/${repo}/git/ref/tags/${version}" --jq '.object.type' 2>/dev/null || true)"
        if [ "$type" = "tag" ]; then
            resolved="$(gh api "repos/${repo}/git/tags/${resolved}" --jq '.object.sha')"
        fi

        if [ -z "$resolved" ]; then
            echo "✗ ${repo}@${version}: no se pudo resolver"
            rc=1
            continue
        fi

        if [ "$current" = "$resolved" ]; then
            echo "✓ ${repo}@${version} ya fijado"
            continue
        fi

        if [ "$CHECK" = "1" ]; then
            echo "✗ ${repo}@${version}: el archivo dice ${current}, debería ser ${resolved}"
            rc=1
        else
            # Se reemplaza sólo en la línea de ESE repo, para no pisar otra
            # acción que tuviera el mismo SHA de marcador.
            sed -i -E "s#(uses: ${repo//\//\\/})@[0-9a-f]{40}#\1@${resolved}#" "$file"
            echo "→ ${repo}@${version} = ${resolved}"
        fi
    done < <(grep -oE 'uses: [^@]+@[0-9a-f]{40} # v[0-9][^ ]*' "$file" \
             | sed -E 's/uses: ([^@]+)@([0-9a-f]{40}) # (v[^ ]+)/\1\t\2\t\3/' \
             | sort -u)
done

exit $rc
