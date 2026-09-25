# Auditoría del pipeline

> Salida del prompt [25 — Auditoría del pipeline](../prompts/25-auditoria-del-pipeline.md).
> Entradas: el [workflow del prompt 24](../../.github/workflows/ci-cd.yml), el `Dockerfile` de 22
> auditado en 23, y los cambios que el pipeline introdujo en el `pom.xml`.

Auditoría sobre las dos cosas que pide la consigna: el workflow escrito y el comportamiento real
que tiene al ejecutarse. Todas las referencias a `L<n>` son líneas de
`.github/workflows/ci-cd.yml` salvo que diga `pom.xml`.

**Cuatro números que salen de medir y no de leer, y que ordenan todo lo que sigue:**

- **`main` no está protegida y el workflow nunca corrió.** `protected = false`, cero rulesets,
  `total_count = 0` corridas. Los dos gates que el archivo declara como *required status checks*
  (L26-27, L320-321, L440-441) no frenan absolutamente nada, y ninguna de las cifras de tiempo que
  el workflow declara en sus comentarios fue medida alguna vez.
- **La cobertura está bien medida: 89,8 % de líneas.** Contra 67,2 % midiendo sólo los unitarios.
  Los dos agentes de JaCoCo y el `merge` del pom funcionan: son **22,6 puntos** que un pipeline mal
  armado habría perdido. Es el único de los tres números nuevos que hoy dice la verdad.
- **El resumen de seguridad no distingue "limpio" de "vacío".** Con un informe de cero
  dependencias imprime `✅ … ninguna con vulnerabilidades conocidas`.
- **Dos archivos que el pipeline necesita no están versionados.** `owasp-suppressions.xml` y
  `scripts/ci-security-summary.py` figuran como `??` en `git status`: el checkout de CI no los va a
  tener y el trabajo de seguridad no puede terminar en verde ni en rojo por un motivo de seguridad.

Índice:

- §1 [Tabla de hallazgos](#1-tabla-de-hallazgos)
- §2 [Cómo se detecta cada uno](#2-cómo-se-detecta-cada-uno)
- §3 [Medición de tiempos](#3-medición-de-tiempos)
- §4 [Trazabilidad del artefacto](#4-trazabilidad-del-artefacto)
- §5 [Estado real de cada gate](#5-estado-real-de-cada-gate)
- §6 [Mitigación propuesta](#6-mitigación-propuesta)
- §7 [Lo que se verificó y está bien](#7-lo-que-se-verificó-y-está-bien)
- §8 [Limitaciones asumidas](#8-limitaciones-asumidas)
- §9 [Orden de remediación](#9-orden-de-remediación)

---

## 1. Tabla de hallazgos

| # | Hallazgo | Categoría | Evidencia | Impacto | Severidad |
|---|---|---|---|---|---|
| **H1** | Ningún gate frena nada: `main` no está protegida, no hay required status checks y el workflow nunca corrió | gates | `GET /repos/.../branches/main` → `protected = false`; `GET /repos/.../rulesets` → `[]`; `GET /repos/.../actions/runs` → `total_count = 0`. Contra L26-27, L320-321, L440-441 | El quality gate y `failBuildOnCVSS` no bloquean la publicación (decisión declarada) **ni el merge** (configuración inexistente): no frenan en ningún lado. Peor: sin protección de rama, código sin un solo test ejecutado entra a `main` por push directo | **Crítica** |
| **H2** | El pipeline referencia dos archivos que no están en el control de versiones | tests | `git ls-files --error-unmatch owasp-suppressions.xml` y `… scripts/ci-security-summary.py` fallan; los consume `pom.xml:650` y L515. Medido: `python3 scripts/NO-EXISTE.py` → exit 2 | En el primer checkout limpio el paso de resumen (`always()`, L514-515) sale 2 y deja `security-scan` en rojo en todas las corridas, por un archivo faltante y no por una CVE. El gate de seguridad nace inutilizable | **Alta** |
| **H3** | El pre-pull de imágenes de Testcontainers descarta el código de salida | tests | L260-263: `docker pull … & docker pull … & wait`. Medido: `bash -e -c '( exit 7 ) & wait'` → `wait` devuelve **0**, el paso queda verde | Un `docker pull` fallido (rate limit de Docker Hub, etiqueta movida) pasa inadvertido y reaparece minutos después como timeout de arranque de Testcontainers: exactamente el fallo intermitente que el comentario L256-259 dice prevenir, y el que se responde con «rerun» | **Alta** |
| **H4** | El resumen de seguridad informa verde cuando no se escaneó nada | métricas | Medido: `ci-security-summary.py` con `{"dependencies": []}` → `## ✅ Seguridad de dependencias / 0 dependencias analizadas, ninguna con vulnerabilidades conocidas.`, exit 0 (`scripts/ci-security-summary.py:116-119`) | Un escaneo que no resolvió el árbol produce el mismo panel que un escaneo limpio. Es el número que alguien cita en una reunión para decir que no hay vulnerabilidades | **Alta** |
| **H5** | El gate de datos sensibles sale 0 cuando no tiene nada que revisar | gates | `scripts/pii-log-gate.sh:43-52`. Medido: directorio vacío → `exit 0`; directorio inexistente → `exit 0` | `./mvnw verify -DskipTests`, o quitar `redirectTestOutputToFile` del pom, apaga el gate **en verde**. Un gate que se desactiva sin dejar rastro no es un gate | **Alta** |
| **H6** | Las cachés están mal invalidadas y se pisan entre sí | tiempo | L479: `key: nvd-${{ runner.os }}-${{ github.run_id }}` — `run_id` es único por corrida, la clave primaria **nunca** acierta. El comentario L470-475 dice que la clave "lleva la fecha del día". Además la ruta cacheada (L478, dentro de `~/.m2/repository`) se solapa con la caché de `setup-java` (L459-463) | La caché del NVD escribe una entrada nueva de gigabytes en **cada** corrida, la de Maven arrastra la base del NVD adentro, y las dos compiten por el cupo de 10 GB con la caché `type=gha` de buildx (L659-660, ~500 MB `mode=max`). El desalojo mutuo convierte «con caché» en «en frío» sin avisar | **Alta** |
| **H7** | `security-scan` no se saltea en un PR desde un fork: falla | secretos | L442-449: el trabajo no tiene `if:`, a diferencia de `sonar` (L331-333). Sin secretos, `NVD_API_KEY` llega vacío y muere en L494-497 | El PR externo recibe un check rojo que su autor no puede arreglar. La asimetría con `sonar`, que sí se saltea limpiamente, muestra que es un olvido y no una decisión | **Media** |
| **H8** | De las tres etiquetas de la imagen, dos son móviles | versionado | L653-666 etiqueta `sha-<12>`, `${POM_VERSION}` y `main`. `POM_VERSION` es **`0.0.1-SNAPSHOT`** (verificado sobre `pom.xml`) y no cambia nunca | El comentario L626-630 se enorgullece de no publicar `latest`, pero `0.0.1-SNAPSHOT` es funcionalmente `latest`: se reapunta en cada push a `main`. Sólo `sha-<12>` responde de qué commit salió un artefacto | **Media** |
| **H9** | Cuando el gate de PII falla, el panel de la corrida dice que todo salió bien | observabilidad del pipeline | `pii-log-gate.sh:97` sale 1 sin escribir en `$GITHUB_STEP_SUMMARY` ni emitir `::error::`. El paso siguiente (L274-276, `always()`) escribe — medido sobre la corrida real — `## ✅ Tests — 803 ejecutados, 0 fallas, 0 errores` | Trabajo rojo, panel verde y ninguna anotación. El motivo real está sepultado en el log de un build de 798 tests. Es la forma exacta en que un pipeline deja de leerse | **Media** |
| **H10** | Un fallo en `main` no le llega a nadie | observabilidad del pipeline | No hay ningún paso de notificación en las 704 líneas del archivo; y como `main` no está protegida (H1), tampoco hay un PR donde el check rojo sea visible | El único aviso es el mail por defecto de GitHub al autor del commit. Un `main` roto puede quedar roto sin que el equipo se entere | **Media** |
| **H11** | Cada commit de una rama con PR abierto corre el pipeline dos veces | tiempo | `on.push.branches` incluye `modulo*/clase*` (L52-55) y `on.pull_request` apunta a `main` (L68-70). El grupo de `concurrency` lleva `github.ref` (L79), y `refs/heads/modulo2/clase4` ≠ `refs/pull/N/merge`: no se unifican | `unit`, `integration`, `sonar` y `security-scan` se duplican por commit — incluido el trabajo de 30 minutos de timeout. Duplica los minutos de runner y la contención en cola justo cuando el autor está iterando | **Media-baja** |
| **H12** | Las acciones están fijadas por SHA pero nada las va a actualizar nunca | dependencias del workflow | Las 17 referencias `uses:` están pinneadas y las 7 distintas resuelven al tag que declara el comentario (verificado contra la API de GitHub, 17/17 OK). Pero `.github/` sólo contiene `workflows/`: no hay `dependabot.yml` ni `renovate.json` | Un pin sin mecanismo de actualización es un congelamiento: la corrección de una vulnerabilidad en `actions/checkout` no llega nunca. El riesgo se invierte, no desaparece | **Baja** |
| **H13** | El `LABEL` de origen del Dockerfile apunta a otro repositorio | versionado | `Dockerfile:279`: `org.opencontainers.image.source="https://github.com/edteam/flight-reservations"`. En el pipeline lo pisa el `--label` de la CLI (verificado sobre la imagen construida: sale `andres-sacco/edteam-architecture-with-ai`) | La trazabilidad correcta depende de que el build pase por el workflow. Un `docker build` a mano produce una imagen que declara un origen falso, y en ghcr.io ese label es lo que ata el paquete al repositorio | **Baja** |
| **H14** | El conteo de tests del resumen incluye los salteados | métricas | `scripts/ci-test-summary.py:130-131` suma los `<testcase>` sin descontar `skipped`. Medido sobre la corrida real: el panel dice `803 ejecutados … 5 salteados`; Maven imprime `650` + `148` = **798** ejecutados | Lo muestra aparte, así que no oculta nada, pero el número grande —el que se cita— no es el de tests que corrieron | **Baja** |

---

## 2. Cómo se detecta cada uno

Todas las pruebas corren con el repositorio, Docker y la capa gratuita de GitHub, SonarQube Cloud
y el NVD. Las marcadas **[ejecutada]** ya se corrieron en esta auditoría y su resultado está en §1.

| # | Prueba concreta | Si está bien | Si está mal |
|---|---|---|---|
| H1 | `curl -s https://api.github.com/repos/andres-sacco/edteam-architecture-with-ai/branches/main \| jq .protected` y, con token, `gh api repos/:owner/:repo/branches/main/protection/required_status_checks`. Después: abrir un PR que baje la cobertura de un archivo nuevo y ver si el botón de merge se bloquea **[ejecutada]** | `protected: true` y los cuatro checks listados como required; el merge queda bloqueado | `protected: false`, `[]` en rulesets, el merge disponible con los checks en rojo — **lo observado** |
| H2 | `git stash -u && git clone . /tmp/limpio && cd /tmp/limpio && ls owasp-suppressions.xml scripts/ci-security-summary.py`; después correr el paso de resumen tal cual lo invoca L515 **[ejecutada]** | Los dos archivos existen en el clon y el paso sale 0 | `No such file`; el paso sale 2 y el trabajo queda rojo — **lo observado** |
| H3 | `bash -e -c '( exit 7 ) & ( exit 0 ) & wait; echo "sigo vivo: $?"'` **[ejecutada]**. En CI: cambiar temporalmente una etiqueta del pre-pull por una inexistente (`postgres:99-noexiste`) y empujar | El paso falla inmediatamente con «manifest unknown» | El paso queda verde y el trabajo muere ~10 min después con un timeout de Testcontainers — **lo observado** |
| H4 | `echo '{"dependencies": []}' > /tmp/r.json && python3 scripts/ci-security-summary.py /tmp/r.json` **[ejecutada]** | El resumen marca que no se escaneó nada y el paso lo señala como anomalía | `## ✅ … 0 dependencias analizadas, ninguna con vulnerabilidades conocidas.` — **lo observado** |
| H4 (extremo a extremo) | Agregar `commons-collections:commons-collections:3.2.1` (CVE-2015-6420, CVSS 9.8) al `pom.xml`, empujar y mirar `security-scan` | El trabajo pasa a rojo y el panel nombra la dependencia, el CVE y el puntaje | El trabajo sigue verde, o falla por el archivo de supresiones faltante (H2) antes de llegar a evaluar nada |
| H5 | `sh scripts/pii-log-gate.sh /tmp/vacio` y `./mvnw verify -DskipTests` **[ejecutada la primera]** | El gate distingue «revisé y no hay nada» de «no tuve nada que revisar» y falla en el segundo caso | `exit 0` en los dos: el gate se apaga sin dejar rastro — **lo observado** |
| H5 (mutación) | Agregar un `log.info("correo: juan.perez@example.com")` en un camino que la suite ejercite y correr `./mvnw verify` | El gate falla y nombra el patrón `email` y la línea | El build queda verde |
| H6 | Correr el pipeline tres veces seguidas y listar `gh cache list --limit 50`; medir el tamaño de la entrada de `setup-java` en la primera corrida de `security-scan` contra la del trabajo `unit` | Una sola entrada `nvd-…` que se renueva por día; la entrada de Maven ronda los 400 MB en todos los trabajos | Tres entradas `nvd-…` distintas; la de Maven del trabajo de seguridad pesa gigabytes; la caché `type=gha` de buildx desaparece por desalojo y el build de la imagen vuelve a tardar ~3 min |
| H7 | Abrir un PR desde un fork y mirar la matriz de trabajos y sus logs | `security-scan` se saltea limpiamente, igual que `sonar`; ningún trabajo llega a un secreto | `security-scan` en rojo con «Falta el secreto NVD_API_KEY» |
| H8 | Después de dos push a `main`: `docker buildx imagetools inspect ghcr.io/andres-sacco/flight-reservations:0.0.1-SNAPSHOT --format '{{.Manifest.Digest}}'` antes y después | El digest asociado a una etiqueta de versión no cambia entre releases distintos | El mismo digest cambió: la «versión semántica» es un puntero móvil |
| H9 | Introducir a propósito el `log.info` con el correo de la prueba de H5, empujar, y abrir **sólo** la pestaña *Summary* de la corrida, sin entrar al log | El panel dice que falló el gate de PII y qué patrón matcheó | El panel dice `✅ Tests — 803 ejecutados, 0 fallas` y el trabajo está rojo — **medido localmente** |
| H9 (guardianes) | Romper una regla de `HexagonalArchitectureTest` (importar `org.springframework` desde el dominio) y mirar sólo el panel **[verificado por lectura de `ci-test-summary.py:133-145`]** | El guardián aparece arriba de todo, con el mensaje completo y una anotación `::error::` | — este camino funciona; ver §7 |
| H10 | Romper un test, empujar a `main`, y esperar sin mirar GitHub | Llega un aviso por el canal del equipo | No llega nada más que el mail por defecto al autor del commit |
| H11 | Abrir un PR desde `modulo2/clase4` y contar las corridas que dispara un solo `git push` | Una corrida por commit | Dos: una por `push` y otra por `pull_request`, con los cuatro trabajos duplicados |
| H12 | `ls .github/dependabot.yml renovate.json` **[ejecutada]**; y comparar el SHA pinneado de `actions/checkout` contra el último `v5` publicado dentro de tres meses | Existe la configuración y hay PR automáticos que mueven los SHA | No existe ninguna de las dos: los SHA quedan donde están para siempre — **lo observado** |
| H13 | `docker build -t prueba . && docker image inspect prueba --format '{{index .Config.Labels "org.opencontainers.image.source"}}'` **[ejecutada, con `--label` de la CLI]** | El label apunta a este repositorio con o sin el pipeline | Sin el `--label` de la CLI, apunta a `github.com/edteam/flight-reservations` |
| H14 | `./mvnw verify \| grep "Tests run:"` contra el encabezado de `ci-test-summary.py` **[ejecutada]** | Los dos números coinciden | `798` contra `803` — **lo observado** |

---

## 3. Medición de tiempos

**El presupuesto declarado nunca se contrastó contra nada: el workflow tiene cero corridas**
(`total_count = 0`). Las cifras que aparecen en sus comentarios son estimaciones de escritorio. Lo
que sigue separa lo medido de lo declarado en vez de mezclarlos.

Lo medido se corrió en esta máquina, no en un runner de GitHub: `ubuntu-24.04` tiene 4 vCPU
compartidas y estos números son un **piso**, no una predicción. Sirven para lo que sirven: fijar el
orden de magnitud de cada etapa y dejar una línea de base contra la cual comparar la primera
corrida real.

| Etapa | En frío (CI) | Con caché (CI) | Presupuesto declarado | Medido local (caliente) |
|---|---|---|---|---|
| `unit` → `./mvnw test` (650 tests) | sin medir | sin medir | «~2 minutos» (L115) | **11,1 s** |
| `unit` → regenerar y comparar el contrato OpenAPI | sin medir | sin medir | no declarado | **5,2 s** |
| `unit` → restaurar caché de Maven | sin medir | sin medir | ~90 s frío / ~15 s caliente (L140-141) | n/a |
| `integration` → `./mvnw verify` (798 tests + PII + JaCoCo) | sin medir | sin medir | «~4 minutos» (L115, L292) | **46,4 s** |
| `integration` → pre-pull de Postgres y RabbitMQ | sin medir | sin medir | «unos segundos» (L234) | ya en caché local |
| `sonar` → análisis + espera del quality gate | sin medir | sin medir | 30-90 s de espera (L413) + ~20 s de analizador (L362) | **no medible** sin `SONAR_TOKEN` |
| `security-scan` → `dependency-check:check` | sin medir | sin medir | base entera la primera vez (timeout de 30 min, L445-447); «~2 minutos» después | **no medible** sin `NVD_API_KEY` |
| `image` → `docker buildx build` | sin medir | sin medir | «~2:50 en frío a ~40 s» con caché (L650) | **2 m 58 s** sin caché de capas — coincide con lo declarado en frío |
| **Camino completo `push` → `main`** | **sin medir** | **sin medir** | implícito: `max(unit, integration) + image` ≈ 6 min | — |

Tres observaciones sobre el tiempo que no dependen de medir en CI:

1. **El camino crítico está bien pensado.** `security-scan` arranca en `t=0` sin `needs`, `image` y
   `sonar` corren en paralelo, y `sonar` reutiliza las clases compiladas por `integration` en vez de
   recompilar (L289-313). La topología no es el problema.
2. **H6 se come el beneficio.** Con las cachés desalojándose entre sí, «con caché» tiende a «en
   frío» corrida tras corrida, y el único síntoma es que el pipeline se pone lento de a poco.
3. **H11 duplica todo.** Con un PR abierto, el presupuesto real por commit es el doble del
   declarado, incluido el trabajo con 30 minutos de timeout.

---

## 4. Trazabilidad del artefacto

**Qué imagen se publicó: ninguna.** El workflow registra `total_count = 0` corridas, así que nunca
llegó a ejecutarse el `docker buildx build --push` de L665-669. No hay digest que resolver ni
etiquetas OCI que contrastar contra un commit: la cadena entre artefacto y commit todavía no se
puede verificar porque todavía no existe.

Lo que sí se verificó es que **el mecanismo funciona**, reproduciendo localmente los argumentos
exactos del paso de build (L651-669) sin `--push`:

| Qué | Cómo se comprueba | Resultado obtenido |
|---|---|---|
| El build reproduce lo que hace el pipeline | `docker buildx build --file Dockerfile --label org.opencontainers.image.revision=$(git rev-parse HEAD) … --provenance=false --metadata-file /tmp/m.json .` | `EXIT=0` en 2 m 58 s |
| El digest sale del push y no de una consulta posterior | `jq -r '."containerimage.digest"' /tmp/m.json` | `sha256:27f9f3f88934162ddd48560acdf92aa3337bb5fa555fa7546a1a69cb2ac7dcb4` — la clave existe y el mecanismo de L685-687 es válido |
| **Imagen → commit** | `docker image inspect <img> --format '{{index .Config.Labels "org.opencontainers.image.revision"}}'` | `29d0322526b5ecf11d063858d2bc4bae35ca50a1` = `git rev-parse HEAD`. ✅ |
| El label de origen queda bien pese al del Dockerfile | `docker image inspect <img> --format '{{index .Config.Labels "org.opencontainers.image.source"}}'` | `https://github.com/andres-sacco/edteam-architecture-with-ai` — el `--label` de la CLI pisa al `LABEL` del Dockerfile, como el comentario L632-637 afirma. ✅ (pero ver H13) |
| **Commit → imagen** | `docker buildx imagetools inspect ghcr.io/andres-sacco/flight-reservations:sha-$(git rev-parse --short=12 HEAD)` | **no verificable**: nada publicado |
| La referencia para consumir es inmutable | `docker buildx imagetools inspect <repo>@<digest>` | El digest funciona por construcción; el problema es que **dos de las tres etiquetas publicadas son móviles** (H8) |

Conclusión del alcance que fija la consigna — *«poder afirmar que la imagen publicada salió de un
commit cuyos tests pasaron, y demostrar de qué commit salió»*:

- **«Salió de un commit cuyos tests pasaron»: sí, por construcción.** `image` declara
  `needs: [unit, integration]` (L540) y en Actions un trabajo con `needs` no arranca si alguno falló.
  No hay `continue-on-error` en el archivo. Esta mitad está bien resuelta.
- **«Demostrar de qué commit salió»: sí, vía `revision` y la etiqueta `sha-<12>`.** Verificado sobre
  la imagen construida. La contaminación es que junto a esa etiqueta se publican dos punteros
  móviles, uno de ellos disfrazado de versión semántica.

---

## 5. Estado real de cada gate

| Control | ¿Puede dejar la corrida en rojo? | ¿Qué frena exactamente? | Cómo se comprobó |
|---|---|---|---|
| Tests unitarios (650) | **Sí** | Frena `image` y `sonar` vía `needs` (L540, L324). **No frena el merge** (H1) ni el push directo a `main` | `./mvnw test` ejecutado: 650 tests, `BUILD SUCCESS`; `needs` leído en L540 |
| Tests de integración (148, Testcontainers) | **Sí** | Ídem. Corren de verdad: el pipeline usa `verify`, no `test` (L272) | `./mvnw verify` ejecutado: `Tests run: 148` por failsafe además de los 650 de surefire |
| `HexagonalArchitectureTest` (ArchUnit) | **Sí** | Está en surefire, corre en `unit`, y su fallo se destaca arriba del panel con `::error::` | Ejecutado dentro de los 650 (`Arquitectura hexagonal`, 18 tests); tratamiento especial en `ci-test-summary.py:45-53,133-145` |
| `OpenApiContractTest` | **Sí** | Ídem | Ejecutado dentro de los 650; mismo tratamiento especial |
| Deriva de `docs/api/openapi.yaml` | **Sí** | Falla el trabajo `unit`, con el comando de arreglo en el panel (L170-193) | **Prueba de mutación**: se adulteró el archivo en el índice de git, se corrió el paso tal cual, y el `git diff --quiet` detectó la deriva. El gate funciona |
| Gate de datos sensibles (PII) | **Sí, pero se apaga solo** | Falla `integration` cuando encuentra un patrón. Sale **0** si no hay salida capturada (H5), y su motivo no llega al panel (H9) | Ejecutado: `pii-gate: revisando 39 archivo(s), 1343 línea(s) propias… sin coincidencias`. Y `sh pii-log-gate.sh /tmp/vacio` → exit 0 |
| Cobertura (JaCoCo, unitarios + integración) | No es un gate por sí misma | Alimenta al quality gate de Sonar | Medido: **89,8 %** de líneas mezclado vs **67,2 %** sólo unitarios. Funciona |
| Quality gate de SonarQube Cloud | **Sí, en principio** (`-Dsonar.qualitygate.wait=true`, L415) | **Nada.** No bloquea `image` (decisión declarada) y no bloquea el merge (H1) | Bandera leída en L415; ausencia de protección de rama verificada por API. Nunca se ejecutó |
| OWASP Dependency-Check | **Hoy no**: falla antes por H2 | **Nada.** Mismo caso que Sonar, más el archivo faltante | `failBuildOnCVSS=7` en `pom.xml:648`; `owasp-suppressions.xml` sin versionar; el paso de resumen sale 2 |
| Publicación sólo desde `main` | **Sí** | `if` del paso de login (L600) y `--push` condicionado (L665-666) | Leído; coherente en los tres lugares (`if` del job L541-543, login, y build) |

---

## 6. Mitigación propuesta

| # | Mitigación |
|---|---|
| **H1** | Proteger `main` con un ruleset: los cuatro trabajos como required status checks, PR obligatorio y push directo prohibido. Hasta que eso exista, ningún otro arreglo de esta lista cambia nada en la práctica. |
| **H2** | `git add owasp-suppressions.xml scripts/ci-security-summary.py` y commitear. Después, un chequeo en el propio workflow que verifique la existencia de lo que va a invocar antes de invocarlo. |
| **H3** | Guardar los PID y hacer `wait $pid` por cada uno, o directamente dos `docker pull` secuenciales: la paralelización ahorra segundos y cuesta la detección del fallo. |
| **H4** | Que el resumen falle —o al menos marque anomalía— cuando el informe trae cero dependencias: un proyecto Spring Boot resuelve más de cien, y un piso de plausibilidad convierte «no escaneé nada» en rojo en vez de en ✅. |
| **H5** | Invertir el default: sin archivos de salida que revisar, el gate falla y dice por qué. Que el gate esté apagado tiene que costar un cambio explícito y visible, no una omisión. |
| **H6** | Clave de caché del NVD con la fecha (`nvd-${{ runner.os }}-${{ steps.fecha.outputs.hoy }}`), que es lo que el comentario ya describe; y mover el directorio de datos fuera de `~/.m2/repository` (`<dataDirectory>` del plugin) para que las dos cachés dejen de solaparse. |
| **H7** | Darle a `security-scan` el mismo `if` que tiene `sonar` (L331-333): saltearse limpio en PR de forks en vez de morir por un secreto que GitHub nunca va a entregar. |
| **H8** | Publicar una etiqueta de versión sólo cuando haya una versión real (un tag de git), y no derivarla de un `-SNAPSHOT` del pom que no cambia nunca. Mientras tanto, que la referencia recomendada en el panel sea el digest — como ya lo es (L699-703). |
| **H9** | Que `pii-log-gate.sh` escriba su hallazgo en `$GITHUB_STEP_SUMMARY` y emita `::error::`, como ya hacen los dos scripts de Python; y que el resumen de tests no muestre ✅ cuando el trabajo va a terminar rojo por otro paso. |
| **H10** | Un paso `if: failure() && github.ref == 'refs/heads/main'` que avise por el canal del equipo con el enlace a la corrida y el nombre del trabajo que falló. |
| **H11** | Restringir el disparador `push` a `main` y dejar que las ramas de trabajo se cubran por el evento `pull_request`; o unificar el grupo de `concurrency` por número de PR cuando exista. |
| **H12** | Agregar `.github/dependabot.yml` con `package-ecosystem: github-actions`: mantiene los SHA pinneados y abre el PR que los mueve, que es lo que hace sostenible el pin. |
| **H13** | Corregir el `LABEL` del Dockerfile para que apunte a este repositorio, y dejar el `--label` del pipeline como refuerzo y no como única línea de defensa. |
| **H14** | Restar los `skipped` del total en el encabezado, o titular «casos» en vez de «ejecutados». |

---

## 7. Lo que se verificó y está bien

Un informe que sólo lista lo que falla no permite saber qué se miró. Esto se probó y salió bien:

- **La cobertura mide lo que dice medir.** Los dos agentes de JaCoCo, el `merge` y el informe único
  funcionan (`pom.xml:546-606`). Medido con la CLI de JaCoCo sobre los tres `.exec` de una corrida
  real: unitarios 67,2 % de líneas, integración 75,0 %, **mezclado 89,8 %**. Los 22,6 puntos de
  diferencia son exactamente lo que se habría perdido midiendo una sola fase.
- **Corre `verify`, no `test`.** Los 148 tests de integración se ejecutan (`Tests run: 148` por
  failsafe), y `sonar.junit.reportPaths` nombra los dos directorios de reportes (`pom.xml:113`), así
  que el conteo que va a ver Sonar incluye a los dos.
- **El gate de deriva del contrato OpenAPI no es decorativo.** `OpenApiDocumentDumpTest` existe,
  está correctamente condicionado con `@EnabledIfSystemProperty(named = "openapi.dump")`, el
  encabezado que escribe no lleva timestamp —así que no genera diffs espurios— y la prueba de
  mutación confirmó que detecta un contrato versionado viejo.
- **Las 17 referencias a acciones están fijadas por SHA y los 7 SHA distintos son correctos.**
  Verificado uno por uno contra `api.github.com/repos/<acción>/git/ref/tags/<tag>`: 17/17 coinciden
  con el tag que declara el comentario al lado. Ninguna acción por rama ni por etiqueta móvil.
- **No hay secretos expuestos.** Ningún valor en texto plano en el YAML; `SONAR_TOKEN` y
  `NVD_API_KEY` entran por `env` y no interpolados en el `run` ni por `-D` (L379-390, L484-490,
  `pom.xml:660`); `docker login` usa `--password-stdin` (L607); no hay `set -x` ni ningún `echo` de
  un secreto; `.env` no está versionado (`.gitignore:3-5`) y `.dockerignore` lo excluye de la
  imagen (`.dockerignore:27-28`). Los artefactos que se suben son XML de tests, `target/classes` y
  el informe de dependencias: ninguno lleva configuración con credenciales.
- **No hay error tragado por configuración.** Cero `continue-on-error` en las 704 líneas, y los seis
  `if: always()` están todos en pasos de reporte o de subida de artefactos, que es donde
  corresponde. El único código de salida que se pierde lo pierde un `wait` (H3), no un `if`.
- **Los permisos del token están acotados.** `permissions: contents: read` a nivel workflow (L86-87)
  y repetido por trabajo; `packages: write` aparece una sola vez, en `image` (L556), que es el único
  que publica.
- **Un PR desde un fork no llega a los secretos.** Lo garantiza GitHub, y además `sonar` se saltea
  explícitamente (L331-333) y el login al registro está condicionado a `push` sobre `main` (L600).
  La excepción es `security-scan` (H7).
- **Los dos guardianes tienen tratamiento destacado en el panel.** `ci-test-summary.py:45-53,133-145`
  los saca arriba del resumen, con el mensaje completo y una anotación `::error::`. El camino de
  «romper un guardián y leer el motivo sin abrir el log» funciona; el que no funciona es el del
  gate de PII (H9).
- **El resumen de tests cuenta los `@Nested` correctamente.** Suma los `<testcase>` en vez de leer
  el atributo de la raíz, que en este repositorio dejaría 208 casos fuera del informe.

---

## 8. Limitaciones asumidas

No son hallazgos: son decisiones, y están documentadas en el lugar donde se toman.

| Limitación | Por qué no es un hallazgo |
|---|---|
| Los `*IT` no corren dentro del `docker build` (`-DskipTests`, `Dockerfile:68,87`) | Decisión del prompt 22, auditada en 23. Los 148 tests corren en el trabajo `integration` sobre el mismo commit, y `image` no arranca si ese trabajo falló. La imagen sigue saliendo de un commit probado |
| `sonar` y `security-scan` no bloquean la publicación de la imagen (L18-31, L437-441) | Decisión declarada y razonable: la caída de sonarcloud.io o del NVD no debería impedir publicar un artefacto cuyos tests pasaron. **Deja de ser limitación y pasa a ser H1 sólo porque tampoco bloquean el merge**: si estuvieran declarados como required status checks, esta fila se sostendría sola |
| `sonar` se saltea en PR desde forks (L325-333) | Correcto y bien explicado: GitHub no entrega secretos a un fork, y un 401 sin sentido es peor que un trabajo salteado. El análisis ocurre igual al mergear |
| `skipTestScope` en su default (`pom.xml:616-621`) | Una CVE en Testcontainers o JUnit no entra a la imagen. Marcarlas rojas sería ruido, y el ruido es el modo real en que un gate de seguridad se rompe |
| El pipeline no despliega | Alcance explícito del prompt 24: termina en la imagen publicada |
| `packages: write` alcanza a todos los pasos de `image`, incluida `docker/setup-buildx-action` | Inherente al modelo de permisos por trabajo de Actions. Se mitiga con lo que ya está hecho: el permiso es el mínimo, la acción está pinneada por SHA, y el login sólo ocurre en `push` a `main` |
| `--provenance=false` (L639-644) | Decisión con un costo declarado: no hay atestación SLSA, a cambio de que el digest publicado sea el de la imagen y no el de un índice OCI. Es la elección correcta para el objetivo de trazabilidad de este pipeline |
| No se cancelan las corridas de `main` (L76-80) | Deliberado: matar una corrida a mitad de un push al registro deja una etiqueta apuntando a una imagen incompleta |
| `MAVEN_ARGS` se aplica dos veces | Maven 3.9.9 honra la variable de entorno por su cuenta (verificado: `MAVEN_ARGS='--bandera-inexistente' ./mvnw -v` → `Unrecognized option`), y el workflow además la interpola en cada `run`. Con `-B -ntp --errors` es idempotente y no cambia nada; vale saberlo antes de poner ahí una bandera que no lo sea |

---

## 9. Orden de remediación

El criterio, en este orden: **primero lo que hace que el resto sirva de algo**, después **lo que
produce un verde que miente**, después **lo que rompe la confianza en el pipeline** y por último
**lo que cuesta tiempo o prolijidad**. Un gate arreglado que no frena nada sigue sin frenar nada,
por eso H1 va primero aunque no sea un defecto del YAML; y una métrica mal medida va antes que un
pipeline lento porque la primera se cita en una reunión.

| Orden | Hallazgos | Por qué acá |
|---|---|---|
| **1** | **H1** — proteger `main` con required status checks | Es el multiplicador: sin esto, arreglar los gates no cambia el comportamiento de nadie. Es además el arreglo más barato de la lista — configuración, no código |
| **2** | **H2** — versionar los dos archivos faltantes | El trabajo de seguridad hoy no puede dar un veredicto. Un `git add` |
| **3** | **H4, H5** — las dos métricas que informan verde sin haber medido | Producen la señal más peligrosa: un número tranquilizador que nadie va a volver a cuestionar. H5 además apaga un gate de datos personales sin dejar rastro |
| **4** | **H3** — el `wait` que descarta el fallo | Un paso que no puede fallar en un pipeline que presume de no tener `continue-on-error`, y cuyo síntoma —el fallo intermitente— es lo que enseña a desconfiar de la suite |
| **5** | **H9, H10** — que el motivo de un fallo se lea, y que alguien se entere | Un pipeline cuyos fallos cuestan cuatro mil líneas de log se responde con «rerun». Es el modo en que deja de usarse sin que nadie lo apague |
| **6** | **H7** — el trabajo que no se saltea en un fork | Rompe la contribución externa con un error que el autor no puede arreglar. Una línea |
| **7** | **H6, H11** — las cachés que se desalojan y el doble disparo | Degradan de a poco: el pipeline se vuelve lento sin un evento que lo señale, y un pipeline lento se esquiva. Pesa más de lo que parece, pero después de lo que directamente miente |
| **8** | **H8, H13** — las etiquetas móviles y el label de origen equivocado | Se pagan durante un incidente, que es cuando ya es tarde, pero la mitad inmutable de la cadena (`sha-<12>` + `revision`) ya funciona |
| **9** | **H12, H14** — el pin sin actualización y el conteo con salteados | Deuda, no riesgo presente. H12 se resuelve con un archivo de diez líneas y conviene hacerlo junto con cualquier otro cambio al workflow |
