# 03 — Esqueleto del proyecto (Java 21 + Spring Boot, Hexagonal)

**Etapa:** Implementación

**Salida esperada:** Proyecto Maven importable en un IDE

---

## Rol

Actúa como Arquitecto Senior que está creando una aplicación web.

## Contexto

Estoy construyendo un sistema de reservas de vuelos (backend) que permite crear, consultar, modificar y eliminar/cancelar reservas. El sistema debe ser consumido por múltiples frontends distintos (web, mobile, y potencialmente partners externos vía API), por lo que la lógica de negocio debe estar centralizada y desacoplada de la presentación — expuesta como una API (REST) que cualquier cliente pueda consumir sin conocer detalles internos.

El dominio central es la reserva, que está asociada a un vuelo, un pasajero y fechas. Antes de crear o modificar una reserva, el sistema debe validar que los aeropuertos de origen y destino existan (contra un maestro propio o un proveedor externo, decisión aún abierta). Además, cada operación relevante sobre una reserva (creación, modificación, cancelación) debe notificar a un sistema externo de notificaciones, que es un servicio separado del cual no queremos depender de forma síncrona (para no afectar la disponibilidad de las reservas si ese sistema falla).

El sistema se espera que tenga muchos usuarios concurrentes desde el día uno; son preocupaciones reales, no hipotéticas.

## Tarea

1. Crear un proyecto con Java (versión 21) y Spring Boot usando arquitectura hexagonal.
2. Crear las pruebas unitarias correspondientes.
3. Validar que la aplicación funcione.

## Restricciones

- No crear los endpoints.
- No crear el acceso a la base de datos.
- No crear seguridad.
- Solo armar el esqueleto general para poder modificarlo después.
- Incluí pruebas unitarias para toda la lógica creada.

## Formato de salida

Un proyecto en Maven para ser importado en algún IDE.
