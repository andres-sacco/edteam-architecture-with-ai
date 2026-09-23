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
| 10 | [Implementación de la caché](10-implementacion-de-cache.md) | Implementación | Caché en los puntos priorizados + invalidación y tests |
| 11 | [Auditoría de seguridad (STRIDE)](11-auditoria-de-seguridad-stride.md) | Seguridad | Tabla amenaza / STRIDE / mitigación |
| 12 | [Remediación de seguridad](12-remediacion-de-seguridad.md) | Implementación | Mitigaciones en el código + matriz de trazabilidad |
| 13 | [Topología de eventos y colas](13-topologia-de-eventos-y-colas.md) | Decisión arquitectónica | Colas/tópicos con publicador, consumidor y contrato |
| 14 | [Auditoría de la mensajería](14-auditoria-de-mensajeria.md) | Revisión | Tabla de hallazgos + cómo detectar cada uno |
| 15 | [Implementación de la mensajería](15-implementacion-de-mensajeria.md) | Implementación | Outbox durable, broker, consumidor idempotente y DLQ |
| 16 | [Diseño de la resiliencia](16-diseno-de-resiliencia.md) | Decisión arquitectónica | Circuit breaker, reintentos y fallback por dependencia |
| 17 | [Auditoría de la resiliencia](17-auditoria-de-resiliencia.md) | Revisión | Tabla de hallazgos + presupuesto de latencia |
| 18 | [Implementación de la resiliencia](18-implementacion-de-resiliencia.md) | Implementación | Circuitos, fallbacks y reintentos acotados con métricas |
| 19 | [Diseño de la observabilidad](19-diseno-de-observabilidad.md) | Decisión arquitectónica | Esquema de log, niveles, métricas, trazas y alertas |
| 20 | [Auditoría de la observabilidad](20-auditoria-de-observabilidad.md) | Revisión | Tabla de hallazgos + mapa de trazabilidad |
| 21 | [Implementación de la observabilidad](21-implementacion-de-observabilidad.md) | Implementación | Logs en JSON, métricas del pedido, trazabilidad y alertas |
| 22 | [Dockerfile multietapa](22-dockerfile-multietapa.md) | Implementación | Dockerfile comentado + `.dockerignore` |
| 23 | [Auditoría de la imagen](23-auditoria-de-la-imagen.md) | Revisión | Tabla de hallazgos + medición de la imagen |
| 24 | [Pipeline de CI/CD](24-pipeline-ci-cd.md) | Implementación | Workflow de GitHub Actions completo |
| 25 | [Auditoría del pipeline](25-auditoria-del-pipeline.md) | Revisión | Tabla de hallazgos + medición de tiempos |
| 26 | [ADRs de las decisiones](26-adrs-de-las-decisiones.md) | Documentación | Un ADR por decisión + índice actualizado |
| 27 | [Auditoría de los ADRs](27-auditoria-de-los-adrs.md) | Revisión | Matriz de consistencia + verificación contra el código |
| 28 | [Diagramas C4 en Mermaid](28-diagramas-c4-mermaid.md) | Documentación | Bloques Mermaid de Contexto y Contenedores |
| 29 | [Auditoría de los diagramas C4](29-auditoria-de-los-diagramas-c4.md) | Revisión | Tabla de hallazgos + contraste contra el sistema |

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
