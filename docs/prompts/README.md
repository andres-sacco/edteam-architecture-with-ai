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
│   └── adr/       <- decisiones de arquitectura (Architecture Decision Records)
└── ...            <- código fuente (proyecto Maven)
```
