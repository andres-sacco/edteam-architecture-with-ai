# 05 — Alineación del dominio y capa de persistencia

**Etapa:** Implementación

**Salida esperada:** Dominio actualizado, entidades JPA, mappers, adapter de persistencia y migración versionada

---

## Rol

Actúa como desarrollador backend Java/Spring Boot, con foco en persistencia y arquitectura hexagonal, apoyándote en el modelo de datos ya diseñado en 3FN.

## Contexto

Ya existe un proyecto Maven/Spring Boot con arquitectura Hexagonal liviana (un solo módulo, separación por paquetes: `domain.model`, `domain.port.in`, `domain.port.out`, `domain.service`, `adapter.in.rest` (vacío), `adapter.out.persistence`, `adapter.out.notification`, `adapter.out.airport`, `config`).

El modelo de dominio actual (Reserva, Vuelo, Pasajero, Asiento) quedó desactualizado: ya definimos un modelo de datos normalizado en 3FN con las entidades Usuario, Reserva, Itinerario (con precio/moneda), Segmento (compartido entre itinerarios), Pasajero (nombre/apellido/fecha_nacimiento, N:M con Reserva vía `reserva_pasajero`), e `itinerario_segmento` como tabla intermedia con orden. Ese modelo de datos no coincide 1:1 con las clases de dominio actuales.

## Tarea

1. Ajustar el modelo de dominio para que refleje las entidades y relaciones del modelo de datos ya definido, actualizando los puertos solo si es estrictamente necesario.
2. Generar las entidades JPA correspondientes.
3. Generar los mappers entre entidades de dominio y entidades JPA.
4. Implementar el/los adapter(s) de persistencia que reemplacen el stub en memoria actual, respetando los puertos de salida.
5. Generar el script de migración equivalente al DDL ya diseñado.
6. Genera un Docker Compose file que permita ejecutar la BBDD sin necesidad de instalarla.
7. Modifica donde sea necesario las pruebas unitarias.

## Restricciones

- Motor relacional (PostgreSQL).
- Hasta 100k registros por entidad.
- Escrituras concurrentes sin reservas duplicadas (aprovechar los `UNIQUE` y el locking optimista ya definidos en el DDL).
- Usar Spring Data JPA.
- Las entidades JPA no deben filtrarse fuera de `adapter.out.persistence` (el dominio nunca importa `jakarta.persistence.*`).
- No crear DTOs de exposición REST todavía (no hay endpoints).
- Las migraciones van versionadas (`src/main/resources/db/migration`), no como script suelto.

## Formato de salida

Lista de archivos a crear o modificar, agrupados por paquete, con el contenido completo de cada uno (clases de dominio actualizadas, entidades JPA, mappers, adapter de persistencia y el archivo de migración).
