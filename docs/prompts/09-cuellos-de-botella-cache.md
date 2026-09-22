# 09 — Detección de cuellos de botella para caché

**Etapa:** Performance

**Salida esperada:** Lista priorizada de endpoints con el motivo de cada elección

---

## Rol

Actúa como ingeniero de performance especializado en caching.

## Contexto

El sistema de reservas de vuelos expone los siguientes endpoints:

- `GET /reservations/{id}`
- `GET /reservations`
- `POST /reservations`
- `PUT /reservations/{id}`
- `DELETE /reservations/{id}`

más las consultas a la API externa de disponibilidad de vuelos, todo bajo alta concurrencia de muchos usuarios y frontends distintos.

La persistencia es PostgreSQL y las reservas usan locking optimista: una lectura desactualizada puede derivar en un `409` al modificar.

## Tarea

Detectar los cuellos de botella que más se beneficiarían de una caché. Incluí comunicaciones externas con otras aplicaciones.

## Restricciones

- Free tier de un proveedor de Redis administrado, con **memoria limitada**.
- **No cachear datos sensibles** de pasajeros ni de pago.

## Formato de salida

Lista priorizada de endpoints con el motivo de cada elección.
