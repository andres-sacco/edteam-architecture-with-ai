# Prompts

Registro cronológico de los prompts usados para diseñar y construir el sistema de reservas de vuelos.

Cada archivo documenta **un prompt** con su estructura completa (Rol / Contexto / Tarea / Restricciones / Formato de salida), de modo que el proceso de diseño asistido por IA sea auditable y reproducible.

## Índice

| # | Prompt | Etapa | Salida esperada |
|---|--------|-------|-----------------|
| 01 | [Extracción de requerimientos](01-extraccion-de-requerimientos.md) | Análisis | Tabla de requerimientos funcionales y no funcionales |
| 02 | [Layers vs. Hexagonal](02-comparacion-layers-vs-hexagonal.md) | Decisión arquitectónica | Tabla comparativa + recomendación |
| 03 | [Esqueleto del proyecto](03-esqueleto-proyecto-hexagonal.md) | Implementación | Proyecto Maven (Java 21 + Spring Boot) |
| 04 | [Modelo de datos 3FN](04-modelo-de-datos-3fn.md) | Modelado | DER descrito + script DDL |
| 05 | [Persistencia y dominio](05-persistencia-y-dominio.md) | Implementación | Dominio actualizado, JPA, mappers, adapter, migración |
| 06 | [Contrato de la API (OpenAPI)](06-contrato-openapi.md) | Diseño de la API | Especificación OpenAPI 3.0 en YAML |
| 07 | [Adaptador REST de entrada](07-adaptador-rest-entrada.md) | Implementación | Controllers, DTOs, mappers, manejo de errores y tests |
| 08 | [Adaptador REST hacia servicio externo](08-adaptador-rest-servicio-externo.md) | Implementación | Interfaz + implementación con manejo de fallos |
| 09 | [Cuellos de botella para caché](09-cuellos-de-botella-cache.md) | Performance | Lista priorizada de endpoints a cachear |
| 10 | [Auditoría de seguridad (STRIDE)](10-auditoria-de-seguridad-stride.md) | Seguridad | Tabla amenaza / STRIDE / mitigación |
| 11 | [Remediación de seguridad](11-remediacion-de-seguridad.md) | Implementación | Mitigaciones en el código + matriz de trazabilidad |

## Convenciones

- **Nombre de archivo:** `NN-slug-descriptivo.md`, numerado según el orden de ejecución.
- **Estructura interna:** todo prompt mantiene las cinco secciones (Rol, Contexto, Tarea, Restricciones, Formato de salida). El contexto se repite en cada prompt de forma intencional: cada uno debe poder ejecutarse de manera autónoma en una sesión nueva.
- **Trazabilidad:** si un prompt derivó en una decisión arquitectónica, se enlaza el ADR correspondiente en `docs/adr/` desde la sección _Notas_.
- **Inmutabilidad:** los prompts no se editan retroactivamente. Si hay que reformular uno, se agrega un nuevo archivo con el siguiente número y se referencia al anterior.

## Relación con el resto del repositorio

```
.
├── docs/
│   ├── prompts/   <- este directorio: prompts usados en el proceso
│   ├── adr/       <- decisiones de arquitectura (Architecture Decision Records)
│   └── api/       <- cómo se genera el contrato de la API y qué lo verifica
└── ...            <- código fuente (proyecto Maven)
                   #  el contrato OpenAPI se genera desde el código con
                   #  springdoc y se publica en /v3/api-docs
```
