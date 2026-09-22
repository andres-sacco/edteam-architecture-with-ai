# 12 — Remediación de seguridad sobre la aplicación

**Etapa:** Implementación

**Salida esperada:** Cambios en el código que implementan las mitigaciones, con su matriz de trazabilidad

---

## Rol

Actúa como desarrollador backend Java/Spring Boot con foco en seguridad aplicada, implementando las mitigaciones que salieron de un threat modeling ya realizado.

## Contexto

Existe un proyecto Maven con **Java 21 + Spring Boot 3.5** y arquitectura hexagonal (un solo módulo, separación por paquetes):

```
com.edteam.reservations
├── domain          # model, event, exception — sin Spring, sin JPA, sin HTTP
├── application     # port/in (casos de uso + comandos), port/out, service, outbox, exception
└── infrastructure  # adapter/in/rest, adapter/in/scheduling,
                    # adapter/out/persistence, adapter/out/airport,
                    # adapter/out/notification, adapter/out/outbox, config
```

El sistema expone endpoints CRUD de reservas de vuelos, consumidos por múltiples frontends distintos con muchos usuarios concurrentes, y maneja datos sensibles: datos de pasajeros y, potencialmente, datos de pago. Integra servicios externos (catálogo de aeropuertos, notificaciones y, potencialmente, aerolíneas y pasarela de pagos) autenticados con API key o token. La persistencia es PostgreSQL con Flyway y locking optimista.

**La aplicación todavía no tiene capa de seguridad**: no hay autenticación ni autorización implementadas, y `spring-boot-starter-security` no está en el `pom.xml`.

La entrada de este prompt es la **tabla de amenazas del paso anterior** (amenaza | categoría STRIDE | mitigación propuesta), priorizada por severidad e impacto en el negocio. Ese documento dice *qué* hay que mitigar; este paso lo lleva al código.

## Tarea

1. Tomar la tabla de amenazas y convertirla en un plan de remediación ordenado: para cada amenaza, qué cambio concreto la mitiga, en qué paquete vive y qué prioridad tiene.
2. Implementar las mitigaciones de autenticación y autorización: quién puede llamar a cada endpoint y, sobre todo, **quién puede ver o modificar cada reserva** (una reserva pertenece a un único usuario; hoy nada impide que otro la consulte por id).
3. Implementar las mitigaciones sobre el manejo de datos sensibles: qué se loguea y qué no, qué se serializa hacia afuera, y qué se protege en reposo.
4. Implementar las mitigaciones sobre las integraciones externas: manejo de credenciales (API keys y tokens) fuera del código y del control de versiones, y validación de lo que llega del servicio externo.
5. Implementar las mitigaciones restantes de la tabla que apliquen al diseño actual (exposición del contrato, superficie de los endpoints, abuso por volumen, trazabilidad de las operaciones sensibles).
6. Escribir los tests que prueban cada mitigación: no alcanza con que el camino feliz siga funcionando, hay que probar que el acceso indebido **falla**.
7. Registrar la decisión en un ADR en `docs/adr/` y actualizar el `README.md` (estructura, decisiones y la sección _Fuera de alcance_, donde hoy la seguridad figura como pendiente).

## Restricciones

- Priorizar por **severidad e impacto en el negocio**, en el mismo orden que la tabla de entrada: primero lo que expone datos de pasajeros o de pago.
- La seguridad es un detalle de infraestructura: **el dominio y la aplicación no importan Spring Security**. Las reglas de negocio sobre quién es dueño de qué siguen siendo del dominio, expresadas sin depender del framework.
- `HexagonalArchitectureTest` (ArchUnit) tiene que seguir en verde; si hace falta, agregar la regla que impida que la capa de seguridad se filtre hacia adentro.
- No romper el contrato OpenAPI ya acordado: los cambios visibles para los clientes (nuevos códigos de estado, headers de autenticación) tienen que quedar reflejados en el YAML.
- Ningún secreto en el repositorio: credenciales por variables de entorno o por un gestor de secretos, con valores de ejemplo en la configuración local.
- Los mensajes de error no deben filtrar información: un recurso ajeno no puede distinguirse de uno inexistente sólo por el código de respuesta.
- Coherencia con las decisiones ya tomadas: no cachear datos sensibles de pasajeros ni de pago, y no reintentar operaciones no idempotentes.
- Los tests existentes tienen que seguir pasando; si alguno deja de tener sentido bajo el nuevo esquema, adaptarlo y justificar el cambio.

## Formato de salida

1. **Matriz de trazabilidad**: amenaza | categoría STRIDE | mitigación implementada | archivos afectados | prioridad.
2. **Lista de archivos a crear o modificar**, agrupados por paquete, con el contenido completo de cada uno (configuración de seguridad, filtros, adaptadores, cambios en dominio y aplicación, migraciones, tests, ADR).
3. **Amenazas no mitigadas en este paso**, con el motivo y qué haría falta para cerrarlas.
4. **Comandos de verificación** del build y de los tests.
