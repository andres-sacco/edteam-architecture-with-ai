-- =====================================================================
-- V3 — Mensajería: outbox transaccional del productor e idempotencia del
--      consumidor.
--
-- Tres tablas, tres problemas distintos:
--
--   outbox_message       lo que hay que publicar, comprometido junto con la
--                        reserva. Es lo que impide notificar algo que se
--                        rollbackeó y perder la notificación de algo que sí se
--                        guardó.
--   processed_message    lo que el consumidor ya aplicó. Es la deduplicación:
--                        la entrega es at-least-once, así que el mismo mensaje
--                        llega más de una vez por diseño.
--   notification_entrega efecto observable del consumidor. Sin un efecto que se
--                        pueda contar no hay forma de probar que procesar dos
--                        veces deja el mismo estado.
--
-- Todas las columnas de tiempo son TIMESTAMP sin zona y guardan UTC, igual que
-- el resto del esquema. Los adaptadores pasan por `infrastructure.jdbc.Utc`
-- para conseguirlo: `spring.jpa.properties.hibernate.jdbc.time_zone` sólo
-- gobierna a Hibernate, y un `Timestamp.from(instant)` de JdbcTemplate
-- escribiría la hora de pared de la zona de la JVM. En estas tablas importa
-- especialmente, porque la consulta manual contra `now()` es el procedimiento
-- documentado para drenar la dead letter.
--
-- Nada de datos sensibles en ninguna de las tres: ni email, ni nombre, ni
-- documento, ni datos de pago. El payload del outbox lleva ruta y fecha de
-- viaje —sin eso el consumidor no puede redactar nada— y el destinatario va
-- siempre como id interno de usuario.
-- =====================================================================

-- ---------------------------------------------------------------------
-- outbox_message
-- ---------------------------------------------------------------------
CREATE TABLE outbox_message (
    -- UUID generado por la aplicación y no BIGSERIAL: es la clave de
    -- idempotencia que viaja al consumidor, y un id secuencial le contaría a
    -- quien reciba el mensaje cuántas operaciones hace el sistema.
    id              UUID         NOT NULL PRIMARY KEY,

    -- Orden. Monotónico y del lado de la base, porque un reloj de pared puede
    -- ir para atrás entre instancias. Por reserva es estricto: el locking
    -- optimista serializa las escrituras sobre la misma fila.
    sequence        BIGSERIAL    NOT NULL UNIQUE,

    -- Tipo del hecho. Es también la routing key: no hay traducción que mantener.
    type            VARCHAR(64)  NOT NULL,

    -- Versión mayor del esquema del payload. Sólo cambia ante una ruptura.
    schema_version  INTEGER      NOT NULL DEFAULT 1,

    -- Entidad a la que se refiere (el id de la reserva). Es el ámbito del
    -- orden: el despachador no adelanta un mensaje de una reserva cuyo
    -- mensaje anterior todavía no salió.
    subject         VARCHAR(64)  NOT NULL,

    -- El 'data' del mensaje, serializado AL ENCOLAR, dentro de la transacción
    -- del caso de uso. Así lo que se publica es exactamente lo que pasó aunque
    -- el código cambie entre el encolado y el despacho, y el relay reenvía
    -- bytes sin conocer los tipos de evento.
    --
    -- JSONB y no TEXT para poder inspeccionar la dead letter con SQL
    -- ('payload->>'userId'') sin parsear a mano.
    payload         JSONB        NOT NULL,

    -- Une el evento con las líneas de log del pedido que lo originó.
    correlation_id  VARCHAR(64),

    -- Cuándo ocurrió el hecho, no cuándo se envía. La diferencia con
    -- 'enqueued_at' y con el momento de publicación es el lag del outbox.
    occurred_at     TIMESTAMP    NOT NULL,
    enqueued_at     TIMESTAMP    NOT NULL,

    status          VARCHAR(16)  NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,

    -- El "no antes de" que faltaba. Sin esta columna el reintento es en
    -- caliente: cinco intentos en veinte segundos contra un destino que sigue
    -- caído, y todo el outbox en FAILED por un blip de medio minuto.
    next_attempt_at TIMESTAMP    NOT NULL,

    -- Lease del reclamo. Un proceso que muere con el mensaje IN_FLIGHT no lo
    -- deja trabado para siempre: al vencer el lease vuelve a ser elegible.
    claimed_at      TIMESTAMP,

    -- Último error, para diagnosticar la dead letter sin ir a los logs.
    last_error      VARCHAR(500),
    failed_at       TIMESTAMP,

    CONSTRAINT ck_outbox_status   CHECK (status IN ('PENDING', 'IN_FLIGHT', 'DISPATCHED', 'FAILED')),
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0)
);

COMMENT ON TABLE outbox_message IS
    'Outbox transaccional: eventos escritos en la misma transacción que la reserva y publicados por un relay aparte.';

-- Índice parcial: el poll cuesta en función de la COLA, no del histórico. Sin
-- el WHERE, cada corrida del despachador ordena la tabla entera cada 5s y se
-- degrada a medida que crecen los despachados.
CREATE INDEX idx_outbox_pendientes
    ON outbox_message (next_attempt_at, sequence)
    WHERE status IN ('PENDING', 'IN_FLIGHT');

-- La dead letter del productor: la consulta del endpoint de gestión y la de la
-- métrica que alerta con profundidad > 0.
CREATE INDEX idx_outbox_muertos
    ON outbox_message (failed_at DESC)
    WHERE status = 'FAILED';

-- Purga de los despachados. Sin esto la tabla crece para siempre.
CREATE INDEX idx_outbox_despachados
    ON outbox_message (enqueued_at)
    WHERE status = 'DISPATCHED';

-- ---------------------------------------------------------------------
-- processed_message  (deduplicación del consumidor)
-- ---------------------------------------------------------------------
CREATE TABLE processed_message (
    -- El messageId del envelope. La PK ES la deduplicación: un INSERT que
    -- viola el UNIQUE es la señal de "ya lo procesé", y esa decisión la toma
    -- la base y no una consulta previa de la aplicación, que tendría una
    -- carrera entre el SELECT y el INSERT.
    message_id   UUID        NOT NULL PRIMARY KEY,

    type         VARCHAR(64) NOT NULL,
    subject      VARCHAR(64) NOT NULL,
    sequence     BIGINT      NOT NULL,
    processed_at TIMESTAMP   NOT NULL
);

COMMENT ON TABLE processed_message IS
    'Mensajes ya aplicados por el consumidor. Ventana de retención mayor a la del outbox: mientras un mensaje pueda reenviarse, su id tiene que seguir acá.';

CREATE INDEX idx_processed_retencion ON processed_message (processed_at);

-- Último sequence aplicado por reserva. Se consulta para DETECTAR el desorden
-- y registrarlo como anomalía, no para descartar: descartar por sequence menor
-- borra eventos legítimos que llegaron desordenados, que es el modo normal de
-- operación con reintentos.
CREATE INDEX idx_processed_orden ON processed_message (subject, sequence DESC);

-- ---------------------------------------------------------------------
-- notificacion_entrega  (efecto del consumidor)
-- ---------------------------------------------------------------------
CREATE TABLE notificacion_entrega (
    id           BIGSERIAL   NOT NULL PRIMARY KEY,

    -- UNIQUE y no sólo índice: es la segunda red del "exactamente un efecto".
    -- Si la deduplicación por processed_message fallara, esto lo convierte en
    -- un error de base en lugar de en una segunda notificación al usuario.
    message_id   UUID        NOT NULL UNIQUE,

    type         VARCHAR(64) NOT NULL,
    reserva_id   VARCHAR(64) NOT NULL,

    -- El destinatario, por id interno. Nunca el email: ese dato es del sistema
    -- de notificaciones, que lo resuelve a partir de este id.
    usuario_id   VARCHAR(64) NOT NULL,

    sequence     BIGINT      NOT NULL,
    occurred_at  TIMESTAMP   NOT NULL,
    delivered_at TIMESTAMP   NOT NULL
);

COMMENT ON TABLE notificacion_entrega IS
    'Efecto observable del consumidor: una fila por notificación efectivamente emitida. Sin datos de contacto: el destinatario es el id interno del usuario.';

CREATE INDEX idx_notificacion_reserva ON notificacion_entrega (reserva_id, sequence DESC);
