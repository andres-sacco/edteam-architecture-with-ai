# 04 — Modelo de datos normalizado hasta 3FN

**Etapa:** Modelado de datos

**Salida esperada:** Diagrama entidad-relación descrito + script DDL

---

## Rol

Actúa como especialista en modelado de datos.

## Contexto

Las entidades del negocio son: Reserva, Itinerario, Segmento (origen/destino/aerolínea/fecha), Pasajero (nombre, apellido, fecha de nacimiento) y Usuario.

Una reserva tiene un itinerario, N pasajeros, y pertenece a un único usuario; los segmentos pueden repetirse entre distintos itinerarios. Además, un Itinerario tiene un precio determinado.

## Tarea

Diseñar el modelo de datos normalizado hasta 3FN.

## Restricciones

- Motor de base de datos relacional.
- Hasta 100k registros por entidad.
- Debe soportar escrituras concurrentes sin generar reservas duplicadas.

## Formato de salida

Diagrama entidad-relación descrito y script DDL de creación de tablas.
