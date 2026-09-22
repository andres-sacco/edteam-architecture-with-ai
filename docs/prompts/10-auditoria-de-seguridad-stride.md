# 10 — Auditoría de seguridad (STRIDE)


**Etapa:** Seguridad

**Salida esperada:** Tabla de amenazas con su categoría STRIDE y mitigación propuesta

---

## Rol

Actúa como especialista en seguridad aplicando threat modeling (STRIDE).

## Contexto

El sistema de reservas de vuelos expone endpoints CRUD de reservas, consumidos por múltiples frontends distintos con muchos usuarios concurrentes; maneja datos sensibles como datos de pasajeros y, potencialmente, datos de pago.

El backend es Java 21 + Spring Boot con arquitectura hexagonal sobre PostgreSQL, integra servicios externos (catálogo de aeropuertos, notificaciones y, potencialmente, aerolíneas y pasarela de pagos) y todavía **no tiene una capa de seguridad**: no hay autenticación ni autorización implementadas.

## Tarea

Identificar amenazas y vulnerabilidades del diseño actual.

## Restricciones

- Priorizar por **severidad** e **impacto en el negocio**.

## Formato de salida

Tabla: amenaza | categoría STRIDE | mitigación propuesta.
