# Contrato de la API

El contrato OpenAPI **se genera a partir del código**. No hay archivo que
mantener: lo arma springdoc con los mappings de los controllers, los tipos de
los DTOs, sus anotaciones de Bean Validation y las `@Operation` / `@Schema` que
los describen.

| Recurso | URL | Estado |
|---|---|---|
| Documento (JSON) | `GET /v3/api-docs` | Apagado por defecto (`API_DOCS_ENABLED`) |
| Documento (YAML) | `GET /v3/api-docs.yaml` | Ídem |
| Swagger UI | `GET /swagger-ui/index.html` | Apagada por defecto (`SWAGGER_UI_ENABLED`) |

```bash
curl http://localhost:8080/v3/api-docs.yaml
```

## Por qué se apagan en lugar de pedirles un token

El documento es el mapa completo de la API: qué rutas existen, qué campos acepta cada
una y qué cotas tiene cada campo. Publicarlo abierto le ahorra la mitad del trabajo a
quien la esté explorando. Swagger UI es peor todavía, porque además **ejecuta pedidos
reales** desde el navegador. Así que hay que cerrarlos — la cuestión es cómo.

**Pedirles un token no sirve: los rompe.** Un pedido de navegación del navegador no
puede llevar un header `Authorization`, así que el 401 llegaría *antes* de que exista la
pantalla donde apretar *Authorize*; y la UI busca el documento por XHR sin credencial,
con lo que quedaría en «Failed to load API definition». El resultado es una UI que no se
puede usar, con la falsa sensación de estar protegida.

**El control es el interruptor.** `springdoc.api-docs.enabled` y
`springdoc.swagger-ui.enabled` están en `false` por defecto: en producción estos
endpoints no existen —responden 404— y no hay regla de autorización que acertar. Se
encienden en local y en sandbox (`API_DOCS_ENABLED=true`, `SWAGGER_UI_ENABLED=true`), y
ahí se sirven sin credencial, que es la única forma de que se puedan usar.

**Lo que la UI ejecuta sigue cerrado.** El botón *Try it out* pega contra `/v1/**` como
cualquier otro cliente: sin un Bearer cargado en *Authorize*, la respuesta es 401. Que la
UI sea alcanzable no abre la API, y eso lo verifica `ReservationApiIT`.

El documento que leen los partners tampoco sale del runtime: sale del archivo versionado
en [`openapi.yaml`](openapi.yaml), que se regenera desde el código con un comando.

## Regenerar el archivo versionado

```bash
./mvnw test -Dtest=OpenApiDocumentDumpTest -Dopenapi.dump=true
```

Antes había que levantar la aplicación, apuntarle un `curl` y convertir el resultado de
OpenAPI 3.1 a 3.0 a mano. La conversión ya no hace falta:
`springdoc.api-docs.version` está fijada en `openapi_3_0`, así que el archivo se
escribe tal cual sale y el runtime y la copia publicada dejan de poder decir cosas
distintas.

Que el documento generado y el código no se separen lo verifica
`OpenApiContractTest`, que sí corre en cada build: compara las operaciones contra las
rutas registradas en los dos sentidos y los códigos de estado contra los que el
adaptador realmente devuelve.

Los metadatos que el código no puede deducir —título, versión, licencia, la
explicación de la idempotencia y de la concurrencia— están en
[`OpenApiConfiguration`](../../src/main/java/com/edteam/reservations/infrastructure/config/OpenApiConfiguration.java).

## Contra qué host ejecuta Swagger UI

Contra el host desde el que se la está mirando. El documento no declara una
lista fija de `servers`: sin ella, springdoc completa uno con el origen del
pedido, así que en desarrollo el *Try it out* pega contra
`http://localhost:8080` y en cualquier despliegue, contra ese despliegue.

Es deliberado. Swagger UI ejecuta contra el **primer** servidor del documento,
de modo que una lista encabezada por producción convierte el botón "Try it
out" del entorno local en pedidos reales contra producción. Y al revés, poner
`localhost` primero rompería la documentación publicada.

Un entorno que necesite publicar su lista de servidores la declara por
configuración, sin tocar código (hay un ejemplo comentado en
[`application.yml`](../../src/main/resources/application.yml)):

```yaml
springdoc:
  open-api:
    servers:
      - url: https://api.edteam.example
        description: Producción
```

## Lo que hay que saber si se toca

Generar la documentación elimina el riesgo de que el archivo quede viejo, pero
deja otro en su lugar: **lo generado sólo es tan bueno como las anotaciones**.
Un endpoint sin `@Operation` igual aparece, vacío. Un código de estado que el
`@RestControllerAdvice` devuelve pero que nadie declaró no aparece en ningún
lado. Nada de eso rompe el build por sí solo.

Por eso `OpenApiContractTest` compara el documento contra tres fuentes que no
son las anotaciones:

| Verificación | Qué detecta |
|---|---|
| Operaciones del documento ↔ rutas registradas en Spring | Un endpoint sin documentar, o documentado y no implementado |
| Códigos de estado declarados ↔ los que ejercita `ReservationControllerTest` | Un 409 que la API devuelve y el documento no menciona |
| Esquema `Problem` ↔ el cuerpo de un error real | Que el esquema de error y lo que sale por el cable se separen |
| Parámetros de consulta del listado, uno por uno | Que el objeto de parámetros se documente como un blob sin expandir |
| `operationId` por operación | Nombres ambiguos en los clientes generados |
| `minItems` de las colecciones obligatorias | Que el documento autorice listas vacías que la API rechaza con 400 |
| El servidor declarado es el host que pidió el documento | Que el *Try it out* apunte a producción desde el entorno local |

Ninguno de los dos últimos casos es hipotético:

- El listado recibe un `@ModelAttribute`, y sin `@ParameterObject` springdoc lo
  documenta como un único parámetro llamado `params` de tipo objeto. El
  documento pasa cualquier validación de OpenAPI y es, igual, inservible.
- Con `@NotEmpty` sola, las colecciones obligatorias salen documentadas como
  `minItems: 0`: el documento dice que una reserva sin pasajeros es válida
  cuando la API la rechaza con 400. El mínimo tiene que declararse en
  `@Size(min = 1, ...)`, porque el generador deriva `minItems` de las
  restricciones de validación y pisa lo que diga `@ArraySchema`.

## Sobre el esquema de error

Las respuestas de error son `ProblemDetail` de Spring (RFC 7807), que guarda
las extensiones en un mapa: documentarlo directamente daría un esquema sin
propiedades. Por eso existe
[`ApiProblem`](../../src/main/java/com/edteam/reservations/infrastructure/adapter/in/rest/dto/ApiProblem.java),
un record que no se serializa nunca y que sólo sirve para que el generador
tenga algo concreto que describir. Que coincida con lo que realmente sale lo
verifica el test contra un error de verdad.

## Historia

El contrato se diseñó primero, a mano, en el prompt
[`06-contrato-openapi.md`](../prompts/06-contrato-openapi.md); los endpoints se
implementaron contra él en
[`07-adaptador-rest-entrada.md`](../prompts/07-adaptador-rest-entrada.md). El
paso siguiente reemplazó el archivo escrito a mano por la generación dinámica
con springdoc. El diseño previo no se perdió: las descripciones, los ejemplos y
los códigos de estado que tenía el YAML viven ahora en las anotaciones.
