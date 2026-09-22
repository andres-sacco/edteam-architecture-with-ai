# 01 — Extracción de requerimientos

**Etapa:** Análisis

**Salida esperada:** Tabla de requerimientos funcionales y no funcionales

---

## Rol

Actúa como arquitecto de software especializado en sistemas web.

## Contexto

Estoy construyendo un sistema de reservas de vuelos (backend) que permite crear, consultar, modificar y eliminar/cancelar reservas. El sistema debe ser consumido por múltiples frontends distintos (web, mobile, y potencialmente partners externos vía API), por lo que la lógica de negocio debe estar centralizada y desacoplada de la presentación — expuesta como una API (REST) que cualquier cliente pueda consumir sin conocer detalles internos.

El dominio central es la reserva, que está asociada a un vuelo, un pasajero y fechas. Antes de crear o modificar una reserva, el sistema debe validar que los aeropuertos de origen y destino existan (contra un maestro propio o un proveedor externo, decisión aún abierta). Además, cada operación relevante sobre una reserva (creación, modificación, cancelación) debe notificar a un sistema externo de notificaciones, que es un servicio separado del cual no queremos depender de forma síncrona (para no afectar la disponibilidad de las reservas si ese sistema falla).

El sistema se espera que tenga muchos usuarios concurrentes desde el día uno; son preocupaciones reales, no hipotéticas.

## Tarea

Extrae los requerimientos funcionales y no funcionales.

## Restricciones

- Presupuesto acotado.
- Utilización de Java como lenguaje de programación.

## Formato de salida

Tabla con columnas: `Requerimiento | Tipo | Atributo de calidad | Prioridad`.
