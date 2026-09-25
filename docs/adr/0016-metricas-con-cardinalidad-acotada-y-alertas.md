# 0016 — Métricas con cardinalidad acotada y seis alertas versionadas en el repositorio

- **Estado:** Aceptado
- **Fecha:** 2026-09-25
- **Prompt origen:** [19 — Diseño de la observabilidad](../prompts/19-diseno-de-observabilidad.md)
- **Llevado al código en:** [21 — Implementación de la observabilidad](../prompts/21-implementacion-de-observabilidad.md)
- **Diseño completo:** [`docs/observability/design.md`](../observability/design.md) §4 y §6
- **Complementa a:** [0015 — Logs en JSON](0015-logging-estructurado-en-json.md)
- **Custodiado por:** `MetricsCatalogTest`, `AlertRulesTest`, `GrafanaDashboardsTest`, `ObservabilityIT`

## Contexto

Había métricas, y eran las de los componentes internos: caché, outbox,
mensajería. Faltaba justamente lo que se pregunta durante un incidente —cuántas
reservas por minuto, qué porcentaje falla, cuánto tarda el `POST`, cuánto tarda
el catálogo, cuántas respuestas salieron degradadas—. Y no había **dónde
verlas**: sin registry de Prometheus, la única forma de leer una métrica era
pedirla por `/actuator/metrics`, una por una, con el servidor a mano.

Tampoco había alertas. Ninguna definición de qué valor de qué métrica amerita
despertar a alguien, lo que en la práctica significa que nadie se entera de nada
hasta que un usuario escribe.

Dos restricciones ordenaron el trabajo: **cardinalidad acotada** —una etiqueta
con un id de reserva es una serie temporal por reserva, y eso tumba un
Prometheus— y **free tier**: lo que se agregue tiene que correr local y gratis.

## Decisión

**Micrometer con registry de Prometheus, un catálogo de métricas donde cada una
responde una pregunta concreta, y seis alertas versionadas junto al código.**

1. **`/actuator/prometheus` en el puerto de gestión (9090), que el despliegue no
   publica.** Las métricas revelan volumetría de negocio y la superficie real de
   la API: se raspan desde la red interna, no desde afuera.
2. **Una métrica existe si responde una pregunta.** El catálogo agrega al que ya
   había la latencia y la tasa de error por operación de negocio, la latencia y
   el resultado de cada llamada al catálogo, el fan-out del itinerario y las
   respuestas servidas degradadas.
3. **Ninguna etiqueta toma un número no acotado de valores.** Nada de ids de
   reserva ni de usuario. `MetricsCatalogTest` verifica el catálogo.
4. **Los buckets de latencia están declarados**, no son los de la librería: los
   SLO de `http.server.requests`, `reservations.catalog.call` y
   `reservations.catalog.fanout` se configuran en `application.yml` con los
   techos de [0014](0014-presupuesto-de-latencia-del-pedido.md), para que el
   histograma pueda contestar si el presupuesto se cumple.
5. **Seis alertas, y el criterio para que una exista es que se pueda contestar
   qué está roto para el usuario y qué hace quien la recibe.** Si alguna de las
   dos respuestas es «hay que investigar», no es una alerta: es un panel. Dos
   severidades: P1 despierta a alguien, P2 abre un ticket.

   | # | Alerta | Sev. |
   |---|---|---|
   | 1 | Integración con el catálogo rota (el único fallo sin fallback) | P1 |
   | 2 | Más del 2 % de las escrituras fallando | P1 |
   | 3 | El `POST` se salió del presupuesto (p95 > 4 s) | P1 |
   | 4 | Lag del outbox > 300 s: alguien reservó y no recibió el mail | P1 |
   | 5 | Notificaciones muertas (outbox muerto o DLQ con profundidad) | P2 |
   | 6 | Presión de credenciales: fallos de token muy por encima de la línea de base | P2 |

6. **Las reglas viven en el repositorio** (`docker/prometheus/rules/`), se
   despliegan como código y tienen test (`AlertRulesTest`). Los dashboards de
   Grafana también, provisionados por archivo: un Grafana que hay que configurar
   a mano después de cada `down -v` no lo usa nadie.
7. **Sobre qué NO se alerta, a propósito**: Redis caído, RabbitMQ caído, circuito
   abierto, tasa de `4xx` y respuestas degradadas. Los dos primeros son
   dependencias opcionales cuyo daño real lo miden las alertas 3 y 4; un circuito
   abierto es el sistema **funcionando** —decidió dejar de pagar timeouts—; un
   `409` por `If-Match` viejo es la API haciendo su trabajo. Esta lista es parte
   del diseño, no una omisión.
8. **El stack de observación corre local y detrás de un profile de compose**
   (`--profile observability`): Prometheus, Grafana OSS, Loki y Tempo, los cuatro
   publicados sólo en loopback. El `docker compose up` de todos los días sigue
   levantando cuatro servicios, no ocho.

### Alternativas descartadas

| Alternativa | Por qué se descartó | Qué la volvería a poner sobre la mesa |
|---|---|---|
| **Seguir leyendo `/actuator/metrics` una por una** | No hay serie temporal, no hay percentiles y no hay alertas: es un valor instantáneo que hay que ir a buscar | Nada |
| **Exponer `/actuator/prometheus` en el puerto público** | Revela volumetría de negocio y la superficie real de la API a cualquiera que la alcance | Un scrapeo autenticado desde afuera de la red; queda el interruptor `METRICS_SCRAPE_OPEN` para el caso |
| **Un SaaS de observabilidad** (Datadog, New Relic, Grafana Cloud) | Free tier con límites y una cuenta que crear para poder levantar el proyecto. Prometheus + Grafana OSS + Loki + Tempo corren enteros en local sin cuenta. Grafana Cloud queda anotado como destino de un entorno real | Un entorno productivo de verdad; el formato de las métricas no cambia |
| **Etiquetar por id de reserva o de usuario** | Una serie temporal por reserva. Es la forma más rápida de tumbar un Prometheus, y la información puntual es justamente lo que dan los logs y las trazas | Nada |
| **Alertar sobre cada circuito abierto** | Un circuito abierto es una decisión correcta del sistema. Despertar a alguien por eso enseña a ignorar la alerta | Un circuito abierto durante horas: eso es panel, y merece revisión en horario |
| **Alertar la latencia del `PUT`** | El peor caso medido (7,1 s) está por encima del objetivo (4,5 s): la alerta sonaría siempre, y una alerta que suena siempre deja de leerse | El recorte de los 2,6 s de la lectura previa ([0014](0014-presupuesto-de-latencia-del-pedido.md)); el histograma ya tiene el bucket para verificarlo |
| **Alertmanager en el compose local** | Un quinto contenedor para decidir a quién se le manda la página, que es una decisión del entorno y no del repositorio. Las reglas se ven disparar en la pestaña *Alerts* de Prometheus, que es lo que hace falta para escribirlas | Un despliegue real |

## Consecuencias

### A favor

- **Las preguntas de un incidente tienen respuesta sin entrar al servidor**: qué
  porcentaje de escrituras falla, cuánto tarda el catálogo, cuántas respuestas
  salieron degradadas, cuánto lag tiene el outbox.
- **Las alertas se revisan en un pull request**, como el código, y una regla mal
  escrita rompe el build.
- **El presupuesto de latencia es verificable en producción**, no sólo en el
  test: el bucket de 4 s de `http.server.requests` existe para eso.
- **Levantar todo el stack en local es un comando**, con datasources y dashboards
  ya provisionados.

### En contra, y asumido

- **Las seis alertas no cubren todo, y es a propósito.** Un modo de falla que no
  esté en la lista no despierta a nadie: se descubre en un panel o por un usuario.
  Es el intercambio explícito contra el ruido.
- **Los umbrales salen del comportamiento de hoy.** El 2 % de la alerta 2 y los
  300 s de la 4 son juicios, no mediciones de una línea de base larga; van a
  necesitar ajuste con tráfico real.
- **Seiscientos megas de RAM** si alguien levanta el profile de observabilidad;
  por eso está detrás de un profile.
- **La cardinalidad es una disciplina, no una barrera.** `MetricsCatalogTest`
  cuida el catálogo conocido: una etiqueta nueva mal elegida en un componente
  nuevo puede pasar.
- **Hay tres lugares donde mirar** —métricas, logs y trazas— y correlacionarlos
  es trabajo manual salvo por el `correlationId` y el `traceId`.
- **Sin backend, las métricas se siguen publicando y nadie las lee.** La
  aplicación arranca igual, que es la regla de siempre, y la consecuencia es que
  un entorno sin scrapeo parece sano porque nada falla.
