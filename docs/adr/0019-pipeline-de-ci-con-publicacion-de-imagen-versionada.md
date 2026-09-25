# 0019 — Pipeline de CI que prueba, mide y publica una imagen identificable — y no despliega

- **Estado:** Aceptado
- **Fecha:** 2026-09-25
- **Prompt origen:** [24 — Pipeline de CI](../prompts/24-pipeline-ci-cd.md)
- **Auditoría:** [`docs/ci/audit.md`](../ci/audit.md) ([25](../prompts/25-auditoria-del-pipeline.md))
- **Construye la imagen de:** [0018 — Imagen de contenedor multietapa](0018-imagen-de-contenedor-multietapa.md)
- **Custodiado por:** `.github/workflows/ci-cd.yml`

## Contexto

No existía ningún workflow: no había directorio `.github/`. **Nadie corría los
tests salvo a mano**, nada medía cobertura, nada analizaba el código y nada
cruzaba el árbol de dependencias contra vulnerabilidades conocidas. El
[Dockerfile](0018-imagen-de-contenedor-multietapa.md) existía desde el paso
anterior y se construía a mano.

La suite ya tenía la forma que define la del pipeline: `./mvnw test` son ~650
tests sin Docker y sin red, en ~13 s; `./mvnw verify` agrega ~148 tests de
integración que levantan PostgreSQL y RabbitMQ con Testcontainers y **sí
necesitan Docker**. Dos tests custodian invariantes y están en surefire, o sea
en el ciclo corto: `HexagonalArchitectureTest` y `OpenApiContractTest`.

Y había una brecha conocida: `OpenApiContractTest` compara el documento contra
el **código**, no contra `docs/api/openapi.yaml`. El archivo que leen los
partners podía quedar atrasado sin que ningún test fallara.

## Decisión

**Seis trabajos, un grafo donde nada se publica sin tests verdes, y un alcance
que termina en el artefacto publicado.**

```
unit ─────────┬──▶ image   (construye siempre; publica sólo desde main)
              │
integration ──┴──▶ sonar   (análisis y quality gate)

lint                     ┐ no esperan a nadie
security-scan            ┘ y nadie los espera
```

1. **El alcance excluye el despliegue, a propósito.** Dónde y cómo se ejecuta la
   imagen es otro problema, con otro ciclo de vida y otros permisos: mezclarlo
   haría que cada cambio en la estrategia de ejecución tocara el archivo que
   prueba el código. Lo que este pipeline garantiza es que **la imagen publicada
   es exactamente la de un commit cuyos tests pasaron**, y que se puede saber de
   qué commit salió.
2. **`image` y `sonar` declaran `needs: [unit, integration]`.** Un trabajo con
   `needs` no arranca si alguno de los que espera falló: no hay forma de publicar
   una imagen de un commit cuyos tests no pasaron.
3. **Ni un solo `continue-on-error` en el archivo.** Convierte un paso rojo en un
   trabajo verde: el grafo sigue y la imagen se publica igual. Un pipeline así es
   peor que no tener pipeline, porque además da una señal de éxito.
4. **Dónde frena cada gate está dicho.** El quality gate de Sonar y el umbral de
   vulnerabilidades **no** bloquean la publicación —ni el análisis estático ni la
   base del NVD tienen por qué demorar un artefacto cuyos 798 tests ya pasaron, y
   la caída de un servicio externo no debería impedir publicar—. Frenan en el
   **pull request**, declarados como *required status checks*: ahí una decisión
   de calidad o de seguridad se discute y se arregla antes de entrar a `main`.
5. **La brecha del contrato se cierra en el pipeline**: un paso regenera
   `docs/api/openapi.yaml` y falla si `git diff` no está limpio, con el diff
   impreso en el resumen y el comando de arreglo al lado
   ([0007](0007-contrato-openapi-generado-desde-el-codigo.md)).
6. **Registro: `ghcr.io`, autenticado con el `GITHUB_TOKEN` efímero** de la
   corrida. No hay credencial de larga duración que crear, rotar ni revocar, y el
   token muere cuando termina el trabajo. El único secreto del workflow es
   `SONAR_TOKEN`.
7. **Etiquetado: `sha-<12>`, la versión del `pom` y `main`. Nunca `latest`.** Una
   etiqueta móvil no responde la única pregunta que importa durante un incidente:
   qué código está corriendo. Y la referencia inequívoca no es una etiqueta sino
   el **digest**, que se lee del metadata del propio push —no de un `inspect`
   posterior, que volvería a resolver una etiqueta.
8. **Las acciones de terceros se referencian por SHA de commit**, no por etiqueta:
   una etiqueta la puede reapuntar quien tenga push en ese repositorio, y la
   acción corre en el mismo runner que el código. Los permisos del token se
   declaran por trabajo y al mínimo (`contents: read` global; `packages: write`
   sólo en el que publica).
9. **La cobertura se mide sobre las dos fases.** JaCoCo instrumenta unitarios e
   integración —son los de integración los que ejercitan los adaptadores contra
   PostgreSQL y RabbitMQ reales— y Sonar la importa: sin un `jacoco.xml` que
   leer, el análisis publica 0,0 %, que es peor que no publicar nada porque es un
   número que parece medido.
10. **El umbral de vulnerabilidades es `failBuildOnCVSS 7`** y la salida correcta
    ante un falso positivo es suprimirlo **por escrito** en
    `owasp-suppressions.xml`, con su motivo y su fecha. La salida incorrecta es
    subir el número.

### Alternativas descartadas

| Alternativa | Por qué se descartó | Qué la volvería a poner sobre la mesa |
|---|---|---|
| **Incluir el despliegue en el mismo workflow** | Une dos ciclos de vida y dos conjuntos de permisos: el archivo que prueba el código terminaría cambiando cada vez que cambia la estrategia de ejecución, y necesitaría credenciales del entorno productivo | Un entorno de destino definido; y aun así, como un workflow aparte disparado por la publicación |
| **Publicar también `latest`** | Durante un incidente no dice qué código está corriendo, y le da a cualquier consumidor una referencia que se mueve bajo sus pies | Nada; quien quiera un puntero móvil tiene `main`, que al menos nombra la rama |
| **Un PAT de larga duración para el registro** | Una credencial que alguien tiene que crear, guardar y rotar, y que sobrevive a la corrida. El `GITHUB_TOKEN` efímero hace lo mismo sin existir después | Publicar en un registro que no sea el de GitHub |
| **Docker Hub como registro** | Su capa gratuita tiene límites de descarga por IP que afectan justamente a los runners y a los entornos; `ghcr.io` no los tiene para paquetes del mismo repositorio, y hereda la visibilidad y los permisos que ya existen | Un consumidor externo que exija Docker Hub |
| **Acciones referenciadas por etiqueta (`@v4`)** | Una etiqueta se puede reapuntar a otro commit, y la acción corre en el mismo runner que el código y los secretos | Nada; hay un script (`scripts/pin-actions.sh`) para mantener los SHA |
| **Correr los tests de integración adentro del build de la imagen** | Necesitan Docker, y anidar Docker dentro del build es un problema que no hace falta tener | Nada |
| **Gates de calidad bloqueando la publicación** | Una caída de `sonarcloud.io` o del NVD impediría publicar un artefacto ya probado. Se eligió que frenen en el pull request, que es donde una discusión de calidad tiene sentido | Un requisito de cumplimiento que exija el escaneo antes de publicar |
| **Un solo trabajo secuencial** | Más simple y más lento: la falla más frecuente —un test unitario, una regla de ArchUnit— se vería a los ~4 minutos en lugar de a los ~2 | Runners pagos donde compilar dos veces cueste dinero |

## Consecuencias

### A favor

- **No se puede publicar una imagen de un commit cuyos tests no pasaron**, y no
  por disciplina: por la forma del grafo.
- **De una imagen publicada se llega al commit** por la etiqueta `sha-` y por los
  labels OCI, y de un commit a su imagen por el digest del resumen de la corrida.
- **`docs/api/openapi.yaml` no puede quedar viejo** sin dejar el pipeline en rojo.
- **Existe un número de cobertura y existe un umbral de vulnerabilidades**, los
  dos medidos sobre lo que realmente va a la imagen: dependencias resueltas
  —transitivas incluidas— y las dos fases de test.
- **Un pull request de un fork es seguro sin que este archivo haga nada**: GitHub
  le da un token de sólo lectura y sin acceso a los secretos, y el paso que
  publica ni siquiera corre.

### En contra, y asumido

- **El pipeline no despliega, así que el despliegue sigue siendo manual.** Es una
  decisión de alcance, no un olvido, y significa que hoy no hay trazabilidad
  automática entre una imagen publicada y lo que está corriendo en algún lado.
- **Un gate de calidad rojo no impide publicar la imagen.** Depende de que los
  *required status checks* estén configurados en la protección de rama, que es
  configuración del repositorio y **no vive en este archivo**: alguien puede
  desactivarlos sin que ningún commit lo registre.
- **Compilar dos veces** —`unit` e `integration` en paralelo— cuesta minutos de
  runner. Se paga porque en un repositorio público son gratis y porque acorta el
  tiempo hasta el primer veredicto; en un repositorio privado hay que rehacer la
  cuenta.
- **Los SHA de las acciones hay que mantenerlos a mano.** Un SHA clavado no
  recibe arreglos de seguridad solo, igual que la base de la imagen.
- **`dependency-check` depende del NVD**, que es lento y a veces falla; sin
  `NVD_API_KEY` la descarga inicial tarda entre treinta y sesenta minutos. Es el
  trabajo más largo del archivo y por eso arranca primero.
- **Las supresiones de vulnerabilidades son un archivo que envejece.** Cada
  entrada de `owasp-suppressions.xml` es una decisión que hay que revisar, y
  nada obliga a revisarla.
