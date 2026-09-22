# 10 — Implementación de la caché sobre los cuellos de botella detectados

**Etapa:** Implementación

**Salida esperada:** Caché implementada en los puntos priorizados, con su estrategia de invalidación y tests

---

## Rol

Actúa como desarrollador backend Java/Spring Boot con foco en performance, implementando la caché sobre los cuellos de botella que ya salieron de un análisis previo.

## Contexto

Existe un proyecto Maven con **Java 21 + Spring Boot 3.5** y arquitectura hexagonal (un solo módulo, separación por paquetes):

```
com.edteam.reservations
├── domain          # model, event, exception — sin Spring, sin JPA, sin HTTP
├── application     # port/in (casos de uso + comandos), port/out, service, query, outbox, exception
└── infrastructure  # adapter/in/rest, adapter/in/scheduling,
                    # adapter/out/persistence, adapter/out/airport,
                    # adapter/out/notification, adapter/out/outbox, config
```

El sistema expone `GET /v1/reservations/{id}`, `GET /v1/reservations`, `POST /v1/reservations`, `PUT /v1/reservations/{id}` y `DELETE /v1/reservations/{id}`, consumidos por múltiples frontends distintos con muchos usuarios concurrentes. La persistencia es PostgreSQL con Flyway, las reservas usan **locking optimista** y el controller ya expone la versión como `ETag`, exigiendo `If-Match` en las modificaciones: una lectura desactualizada deriva en un `409` al modificar.

**Ya hay un precedente de caching en el repositorio**: `CachingAirportCatalog` decora a `AirportCatalogPort` con un `ConcurrentHashMap` de TTL configurable (`reservations.airport-catalog.cache-ttl`, 30m por defecto), cachea también las respuestas negativas y se cablea en `AdapterConfiguration#airportCatalogPort`. Se eligió decorador —y no `@Cacheable`— para que la decisión quede explícita en el grafo de dependencias, sea testeable sin levantar Spring y pueda reemplazarse por una caché distribuida sin tocar el adaptador de origen. Hoy esa caché es **local al proceso**: con más de una instancia, cada una tiene la suya.

La entrada de este prompt es la **lista priorizada de cuellos de botella del paso anterior** (endpoint o dependencia | motivo de la elección). Ese documento dice *qué* conviene cachear y por qué; este paso lo lleva al código.

## Tarea

1. Tomar la lista priorizada y convertirla en un plan de implementación: para cada punto, qué se cachea exactamente, con qué clave, con qué TTL, dónde vive el código y qué prioridad tiene.
2. Implementar la caché en los puntos priorizados, respetando el patrón ya establecido: decoradores sobre los puertos de salida en `infrastructure`, cableados en `config`, sin que el caso de uso sepa que existe una caché.
3. Definir e implementar la **estrategia de invalidación** de cada entrada: qué operación de escritura invalida qué clave, y qué pasa con las listas paginadas cuando cambia una sola reserva. Dejar explícito qué se invalida de forma activa y qué se deja expirar por TTL.
4. Resolver la interacción con el **locking optimista**: una lectura servida desde caché no puede hacer que un cliente mande un `If-Match` viejo y coma un `409` evitable. Decidir y justificar si la lectura cacheada participa del flujo de modificación o si ese flujo va siempre al origen.
5. Aprovechar el `ETag` que ya se emite: soportar `If-None-Match` en las lecturas para responder `304 Not Modified` y ahorrar payload, y definir los headers `Cache-Control` de cada endpoint según qué tan sensible es lo que devuelve.
6. Dejar la caché **observable**: hits, misses, tamaño y evictions expuestos por Actuator/Micrometer, para poder verificar que efectivamente sirve y detectar cuándo deja de servir.
7. Escribir los tests: que el hit no vaya al origen, que el TTL expire, que la invalidación efectivamente borre la clave, que una escritura no deje servir datos viejos y que nada sensible termine en la caché.
8. Registrar la decisión en un ADR en `docs/adr/` (qué se cachea, qué no, y por qué) y actualizar el `README.md`.

## Restricciones

- Respetar el orden de prioridad de la lista de entrada: primero lo que más tráfico o más latencia ahorra, no lo más fácil de implementar.
- **Free tier de un proveedor de Redis administrado, con memoria limitada**: la caché tiene que caber. Acotar qué entra, con qué tamaño estimado por entrada y con qué política de evicción; justificar cada TTL en vez de elegirlo por costumbre.
- **No cachear datos sensibles** de pasajeros ni de pago, en ninguna capa: ni en Redis, ni en memoria del proceso, ni en headers `Cache-Control` que permitan a un proxy o al browser guardar la respuesta. La aplicación todavía **no tiene capa de seguridad** —no hay autenticación ni autorización—, así que cualquier entrada cacheada hoy es legible por cualquiera que alcance el endpoint: esa es la razón de la restricción, no una formalidad.
- La caché es un detalle de infraestructura: **el dominio y la aplicación no importan Redis ni Spring Cache**. Los puertos no cambian de firma por haber agregado una caché detrás.
- `HexagonalArchitectureTest` (ArchUnit) tiene que seguir en verde; si hace falta, agregar la regla que impida que la caché se filtre hacia adentro.
- **La aplicación tiene que seguir arrancando y los tests corriendo sin Redis disponible**, igual que hoy arranca sin el catálogo externo: la caché distribuida se activa por configuración y hay un fallback en memoria.
- Una caché caída **no puede tumbar el servicio**: un fallo al leer o escribir en Redis degrada a ir al origen, no propaga el error al cliente.
- No romper el contrato OpenAPI ya acordado: si aparecen `304` o nuevos headers, tienen que quedar reflejados en el documento generado.
- Coherencia con las decisiones ya tomadas: el cliente del catálogo no define timeouts ni reintenta, y las operaciones no idempotentes no se reintentan.
- Los tests existentes tienen que seguir pasando; si alguno deja de tener sentido bajo el nuevo esquema, adaptarlo y justificar el cambio.

## Formato de salida

1. **Tabla de trazabilidad**: cuello de botella | qué se cachea | clave | TTL | invalidación | archivos afectados | prioridad.
2. **Lista de archivos a crear o modificar**, agrupados por paquete, con el contenido completo de cada uno (decoradores, configuración, propiedades, cambios en el controller, tests, ADR).
3. **Puntos de la lista que no se cachean en este paso**, con el motivo (memoria, sensibilidad de los datos, baja tasa de aciertos esperada) y qué haría falta para revisarlo.
4. **Comandos de verificación** del build y de los tests.
