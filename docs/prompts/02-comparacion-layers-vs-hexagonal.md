# 02 — Comparación: arquitectura por Layers vs. Hexagonal

**Etapa:** Decisión arquitectónica

**Salida esperada:** Tabla comparativa con recomendación justificada

---

## Rol

Actúa como Arquitecto Senior evaluando trade-offs, no dando una única respuesta cerrada.

## Contexto

Estoy construyendo un sistema de reservas de vuelos (backend) que permite crear, consultar, modificar y eliminar/cancelar reservas. El sistema debe ser consumido por múltiples frontends distintos (web, mobile, y potencialmente partners externos vía API), por lo que la lógica de negocio debe estar centralizada y desacoplada de la presentación — expuesta como una API (REST) que cualquier cliente pueda consumir sin conocer detalles internos.

El dominio central es la reserva, que está asociada a un vuelo, un pasajero y fechas. Antes de crear o modificar una reserva, el sistema debe validar que los aeropuertos de origen y destino existan (contra un maestro propio o un proveedor externo, decisión aún abierta). Además, cada operación relevante sobre una reserva (creación, modificación, cancelación) debe notificar a un sistema externo de notificaciones, que es un servicio separado del cual no queremos depender de forma síncrona (para no afectar la disponibilidad de las reservas si ese sistema falla).

El sistema se espera que tenga muchos usuarios concurrentes desde el día uno; son preocupaciones reales, no hipotéticas.

## Tarea

Comparar arquitectura por Layers vs. Hexagonal para este proyecto.

## Restricciones

Usar criterios explícitos:

- Testabilidad
- Acoplamiento
- Costo de mantenimiento
- Curva de aprendizaje del equipo

## Formato de salida

Tabla comparativa con una recomendación justificada, no un ensayo.
