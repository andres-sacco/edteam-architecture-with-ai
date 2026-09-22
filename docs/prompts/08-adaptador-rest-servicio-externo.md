# 08 — Adaptador REST hacia un servicio externo


**Etapa:** Implementación

**Salida esperada:** Interfaz del adaptador + implementación con manejo de fallos

---

## Rol

Actúa como ingeniero backend diseñando un adaptador REST hacia un servicio externo.

## Contexto

El sistema de reservas de vuelos (Java 21 + Spring Boot, arquitectura hexagonal) necesita comunicarse con APIs externas de aerolíneas (disponibilidad y precios) y/o con una pasarela de pagos, autenticadas con API key o token, para obtener o confirmar datos de vuelos.

Hoy las dependencias externas ya viven detrás de puertos de salida (`AirportCatalogPort`, `NotificationPort`, `ReservationRepositoryPort`) en `application.port.out`, y sus adaptadores en `infrastructure.adapter.out.*`. Hay un precedente de decoración: `CachingAirportCatalog` envuelve al catálogo de aeropuertos con una caché de TTL configurable.

El sistema se espera con muchos usuarios concurrentes desde el día uno, y la disponibilidad de las reservas no debe depender de que el servicio externo esté sano.

## Tarea

Diseñar el cliente REST que llama a esa API antes de intentar usar la caché.

## Restricciones

- Definir **timeout** (de conexión y de lectura).
- **Reintentos con backoff**.
- Manejo explícito de errores de la API, diferenciando **4xx de 5xx**.
- **No reintentar operaciones no idempotentes**, como confirmar un pago.

## Formato de salida

Interfaz del adaptador más su implementación, con el manejo de fallos incluido.
