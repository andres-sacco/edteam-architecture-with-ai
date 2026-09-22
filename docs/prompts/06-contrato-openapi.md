# 06 — Contrato de la API (OpenAPI 3.0)

**Etapa:** Diseño de la API

**Salida esperada:** Documento YAML válido en OpenAPI 3.0

---

## Rol

Actúa como diseñador de APIs experto en OpenAPI v3.

## Contexto

Estoy construyendo un sistema de reservas de vuelos (backend) que permite crear, consultar, modificar y eliminar/cancelar reservas.

Las entidades principales son: **Reserva**, **Itinerario**, **Pasajero** y **Usuario**. Un itinerario está compuesto por segmentos en orden (origen, destino, aerolínea, fecha de vuelo) y tiene un precio con su moneda; una reserva tiene un itinerario, N pasajeros y pertenece a un único usuario.

Los casos de uso a exponer son: crear reserva, obtener una reserva, listar reservas, actualizar reserva y eliminar/cancelar reserva.

La API va a ser consumida por múltiples frontends distintos (web, mobile y potencialmente partners externos), por lo que el contrato debe ser genérico y estable: no puede reflejar detalles internos de persistencia ni atarse a un cliente en particular.

## Tarea

Generar la especificación OpenAPI completa de los endpoints necesarios, incluyendo paths, operaciones, parámetros, cuerpos de request y response, y los `schemas` de los recursos y de los errores.

## Restricciones

- Seguir convenciones REST (recursos en plural, verbos HTTP con su semántica, sin verbos en la URL).
- Usar los códigos de estado HTTP correctos: **200**, **201**, **400**, **404**, **409**.
- El contrato no debe exponer el modelo de dominio tal cual: los esquemas son propios de la API y deben poder evolucionar sin arrastrar al modelo interno.

## Formato de salida

Documento YAML válido en OpenAPI 3.0.
