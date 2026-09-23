# Architecture Decision Records (ADR)

Decisiones de arquitectura del sistema de reservas de vuelos.

Cada ADR documenta una decisión significativa, su contexto y sus consecuencias. El formato usado es el de Michael Nygard:

```markdown
# NNNN — Título de la decisión

- **Estado:** Propuesto | Aceptado | Rechazado | Reemplazado por [NNNN](...)
- **Fecha:** YYYY-MM-DD

## Contexto

## Decisión

## Consecuencias
```

## Convenciones

- **Nombre de archivo:** `NNNN-slug-descriptivo.md` (numeración de 4 dígitos, correlativa).
- Un ADR nunca se borra ni se reescribe: si una decisión cambia, se crea uno nuevo que la reemplaza y se actualiza el estado del anterior.
- Cuando un ADR nació de un prompt, enlazar el archivo correspondiente en [`docs/prompts/`](../prompts/README.md).

## Índice

| # | Decisión | Estado | Prompt origen |
|---|----------|--------|---------------|
| [0003](0003-autenticacion-autorizacion-y-datos-sensibles.md) | Autenticación, autorización por recurso y datos sensibles | Aceptado | [12 — Remediación de seguridad](../prompts/12-remediacion-de-seguridad.md) |
| [0004](0004-mensajeria-asincronica-y-broker.md) | Mensajería asincrónica: eventos sobre RabbitMQ con outbox en PostgreSQL | Aceptado | [13 — Topología de eventos y colas](../prompts/13-topologia-de-eventos-y-colas.md) |
| [0005](0005-garantias-de-entrega-y-remediacion-de-la-mensajeria.md) | Garantías de entrega de la mensajería: idempotencia, dos dead letters y reintentos clasificados | Aceptado | [15 — Implementación de mensajería](../prompts/15-implementacion-de-mensajeria.md) |
