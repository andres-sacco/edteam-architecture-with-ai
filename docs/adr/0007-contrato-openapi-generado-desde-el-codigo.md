# 0007 — El contrato OpenAPI se genera desde el código y se versiona en el repositorio

- **Estado:** Aceptado
- **Fecha:** 2026-09-25
- **Prompt origen:** [06 — Contrato de la API (OpenAPI 3.0)](../prompts/06-contrato-openapi.md)
- **Llevado al código en:** [07 — Adaptador REST de entrada](../prompts/07-adaptador-rest-entrada.md)
- **Detalle:** [`docs/api/README.md`](../api/README.md)
- **Custodiado por:** `OpenApiContractTest` (surefire) y el paso *«Verificar que `docs/api/openapi.yaml` no quedó viejo»* del workflow

> Redactado retroactivamente el 2026-09-25. El número refleja el orden en que se
> tomó la decisión, no el orden en que se escribió el documento.

## Contexto

La API la iban a consumir frontends propios y potencialmente partners externos:
hacía falta un contrato publicable, estable y que no expusiera el modelo interno.
El primer documento OpenAPI se escribió a mano, antes de que existiera un solo
controller.

Después de implementar el adaptador REST había **dos documentos**: el YAML
escrito a mano y lo que springdoc generaba del código. Nada garantizaba que
dijeran lo mismo, y de hecho no lo decían — el archivo declaraba OpenAPI 3.0.3
porque alguien lo había convertido a mano desde el 3.1 que springdoc emite por
defecto. Un contrato que hay que sincronizar a mano es un contrato que está
viejo; la única duda es desde cuándo.

## Decisión

**El código es la fuente de verdad del contrato. El archivo publicable se genera
desde él con un comando, se versiona en `docs/api/openapi.yaml`, y el pipeline
falla si quedó atrasado.**

1. **Lo genera springdoc** a partir de los mappings de los controllers, los tipos
   de los DTOs, sus anotaciones de Bean Validation y las `@Operation` / `@Schema`.
   Lo que el código no puede deducir —título, versión, licencia, la explicación de
   la idempotencia y de la concurrencia— vive en `OpenApiConfiguration`, que es
   código igual.
2. **La versión del formato está fijada en `openapi_3_0`.** No es un capricho de
   compatibilidad: es lo que elimina el paso de conversión manual que hacía que el
   runtime y la copia publicada dijeran cosas distintas.
3. **El archivo versionado se regenera con un comando**
   (`./mvnw test -Dtest=OpenApiDocumentDumpTest -Dopenapi.dump=true`) y ese test
   está **apagado por defecto**: un test que escribe en el repositorio no puede
   correr en cada build.
4. **Dos guardianes, y verifican cosas distintas.** `OpenApiContractTest` compara
   el documento generado contra el código en los dos sentidos —rutas registradas,
   códigos de estado que devuelve el `@RestControllerAdvice`, esquemas de
   seguridad— y corre en cada build. El paso del workflow regenera el archivo y
   falla si `git diff` no está limpio: eso es lo que impide que
   `docs/api/openapi.yaml` se separe del código sin que nadie se entere.
5. **Los esquemas son DTOs del adaptador, no el agregado.** `ReservationResponse`,
   `ItineraryRequest` y compañía viven en `infrastructure.adapter.in.rest.dto` y
   se mapean a mano. El contrato puede evolucionar sin arrastrar al modelo, que es
   la mitad de la razón por la que existe [0001](0001-arquitectura-hexagonal-en-un-modulo.md).
6. **El cuerpo de error es uniforme y es `ProblemDetail` (RFC 7807)** en las cinco
   operaciones, sin stack traces ni mensajes internos.

### Alternativas descartadas

| Alternativa | Por qué se descartó | Qué la volvería a poner sobre la mesa |
|---|---|---|
| **Contract-first: el YAML es la fuente y genera el código** | Es la opción correcta cuando el contrato se negocia antes de implementarlo, con varios equipos en la mesa. Acá el contrato lo escribe el mismo equipo que lo implementa, y el generador introduce un paso de build más y clases que nadie puede tocar. El riesgo que evita —que el código se desvíe del contrato— lo cubre `OpenApiContractTest` a un costo mucho menor | Que un partner externo empiece a negociar cambios del contrato antes de que existan; ahí el orden se invierte y conviene |
| **Mantener el YAML a mano y nada más** | Es lo que había, y ya estaba desactualizado: distinta versión de formato, distintos códigos de estado. Un contrato sin verificación automática es documentación | Nada |
| **No versionar ningún archivo y publicar sólo `/v3/api-docs`** | Obligaría a tener el runtime levantado para leer el contrato, y a exponer el endpoint en producción, que es justo lo que [0003](0003-autenticacion-autorizacion-y-datos-sensibles.md) §5 cerró. Además no hay diff que revisar en un pull request | Un portal de partners que publique el documento desde un artefacto de CI; ahí el archivo del repositorio deja de ser el canal, pero sigue siendo el insumo |
| **Dejar OpenAPI 3.1, el default de springdoc** | Varias herramientas de partners todavía no lo leen, y la conversión a 3.0 a mano fue exactamente el origen de la divergencia | Que las herramientas del ecosistema de los consumidores lo soporten; el cambio es una línea de `application.yml` y una regeneración |
| **Serializar el agregado de dominio directamente** | Ata el contrato al modelo: renombrar un campo interno rompe a todos los clientes, y cualquier campo nuevo del dominio se publica sin querer | Nada |

## Consecuencias

### A favor

- **El contrato no puede quedar viejo sin que el build lo diga.** Es verificable:
  cambiar un código de estado en el `@RestControllerAdvice` sin regenerar el
  archivo deja el trabajo `unit` en rojo, con el diff impreso en el resumen del
  workflow y el comando de arreglo al lado.
- **El archivo tiene diff revisable**, porque `writer-with-order-by-keys: true`
  hace que dos generaciones del mismo código produzcan bytes idénticos. Un cambio
  de contrato se discute en el pull request donde ocurre.
- **Cambiar la forma de una respuesta no toca el dominio.** Los `304`, el header
  `X-Correlation-Id` y el `X-Degraded` se agregaron en el adaptador sin que
  `Reservation` se enterara.

### En contra, y asumido

- **El contrato está escrito en anotaciones.** Un `@Schema(description = …)` es
  menos legible que el YAML que produce, y la descripción de una operación queda
  a diez líneas de su handler. Es el costo de tener una sola fuente.
- **Hay un paso manual que hay que acordarse de hacer**, y su red es el pipeline.
  Quien cambie el contrato y no regenere el archivo se entera en CI, no en su
  máquina.
- **springdoc queda clavado en la línea 2.8.x.** La 3.x apunta a Spring Boot 4 y
  no arranca con este parent: el upgrade de Boot y el de springdoc pasan a ser el
  mismo trabajo.
- **`OpenApiContractTest` no compara contra el archivo publicado**, sólo contra el
  código. Esa brecha la cubre el paso del workflow, que es un gate del pipeline y
  no un test: quien corra `./mvnw verify` en su máquina no la ve.
- **El interruptor del documento y de Swagger UI quedó por defecto en `true`**
  (`API_DOCS_ENABLED`, `SWAGGER_UI_ENABLED` en `application.yml`), para que
  `compose.yaml` y el arranque desde el IDE funcionen sin `.env`.
  [0003](0003-autenticacion-autorizacion-y-datos-sensibles.md) §5 y
  [`docs/api/README.md`](../api/README.md) describen la decisión —apagados salvo
  en local y sandbox—, y la decisión no cambia: lo que hay que hacer es que el
  entorno defina la variable. **Un despliegue que no la defina expone el mapa
  completo de la API**, y ése es el riesgo aceptado, registrado acá para que
  quien opere el despliegue lo sepa.
