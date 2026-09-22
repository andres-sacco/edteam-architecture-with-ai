-- =============================================================================
-- V1 - Esquema inicial del sistema de reservas de vuelos (3FN).
--
-- Equivalente al modelo de datos diseñado. Dos precisiones respecto del DDL
-- original, ninguna semántica:
--   * Todas las constraints llevan nombre explícito. El adaptador de
--     persistencia traduce los errores de integridad a excepciones de negocio
--     mirando el nombre de la constraint; con nombres autogenerados esa
--     traducción quedaría atada a lo que decida PostgreSQL.
--   * Se agregan los índices que hacen eficientes las búsquedas por clave
--     natural que usa el adaptador.
--   * itinerario_segmento.orden es INTEGER en lugar de SMALLINT. El @OrderColumn
--     de JPA es INTEGER y no admite otro tipo, y ddl-auto=validate —que es lo
--     que garantiza que el mapeo y el esquema no se desincronicen— rechaza el
--     desajuste. Mantener SMALLINT obligaría a mapear la tabla intermedia como
--     entidad propia para ahorrar dos bytes por fila.
-- =============================================================================

-- =============================================
-- USUARIO
-- =============================================
CREATE TABLE usuario (
    id          BIGSERIAL    NOT NULL,
    email       VARCHAR(150) NOT NULL,
    nombre      VARCHAR(100) NOT NULL,
    apellido    VARCHAR(100) NOT NULL,
    fecha_alta  TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_usuario       PRIMARY KEY (id),
    CONSTRAINT uq_usuario_email UNIQUE (email)
);

COMMENT ON TABLE usuario IS 'Dueño de las reservas. Todavía sin casos de uso propios en la aplicación.';

-- =============================================
-- PASAJERO
-- Entidad independiente, reutilizable entre reservas.
-- =============================================
CREATE TABLE pasajero (
    id               BIGSERIAL    NOT NULL,
    nombre           VARCHAR(100) NOT NULL,
    apellido         VARCHAR(100) NOT NULL,
    fecha_nacimiento DATE         NOT NULL,
    documento        VARCHAR(50),

    CONSTRAINT pk_pasajero    PRIMARY KEY (id),
    CONSTRAINT uq_pasajero_doc UNIQUE (documento)
);

COMMENT ON COLUMN pasajero.documento IS
    'Clave natural: permite reconocer al mismo pasajero en reservas posteriores. Admite nulos porque hay pasajeros sin documento cargado; en ese caso no se puede deduplicar.';

-- =============================================
-- SEGMENTO
-- Tramo de vuelo, compartido entre itinerarios.
-- =============================================
CREATE TABLE segmento (
    id          BIGSERIAL   NOT NULL,
    origen      VARCHAR(3)  NOT NULL,
    destino     VARCHAR(3)  NOT NULL,
    aerolinea   VARCHAR(50) NOT NULL,
    fecha_vuelo TIMESTAMP   NOT NULL,

    CONSTRAINT pk_segmento         PRIMARY KEY (id),
    CONSTRAINT uq_segmento         UNIQUE (origen, destino, aerolinea, fecha_vuelo),
    CONSTRAINT ck_segmento_ruta    CHECK (origen <> destino)
);

COMMENT ON CONSTRAINT uq_segmento ON segmento IS
    'Clave natural del tramo. La usa el adaptador para reutilizar la fila en lugar de duplicarla, y resuelve la carrera entre dos reservas simultáneas del mismo vuelo.';

-- =============================================
-- ITINERARIO
-- Secuencia ordenada de segmentos, con precio propio.
-- =============================================
CREATE TABLE itinerario (
    id     BIGSERIAL     NOT NULL,
    precio NUMERIC(10,2) NOT NULL,
    moneda VARCHAR(3)    NOT NULL DEFAULT 'USD',

    CONSTRAINT pk_itinerario     PRIMARY KEY (id),
    CONSTRAINT ck_itinerario_precio CHECK (precio >= 0)
);

COMMENT ON TABLE itinerario IS
    'No tiene clave natural a propósito: la misma combinación de tramos puede venderse a distinto precio, así que cada reserva crea su itinerario aunque reutilice los segmentos.';

-- Tabla intermedia: un segmento puede repetirse entre distintos itinerarios,
-- y un itinerario tiene un orden definido de segmentos (ida + escala, ida + vuelta).
CREATE TABLE itinerario_segmento (
    itinerario_id BIGINT  NOT NULL,
    segmento_id   BIGINT  NOT NULL,
    orden         INTEGER NOT NULL,

    CONSTRAINT pk_itinerario_segmento PRIMARY KEY (itinerario_id, orden),
    CONSTRAINT uq_itin_segmento       UNIQUE (itinerario_id, segmento_id),
    CONSTRAINT fk_itin_seg_itinerario FOREIGN KEY (itinerario_id) REFERENCES itinerario (id) ON DELETE CASCADE,
    CONSTRAINT fk_itin_seg_segmento   FOREIGN KEY (segmento_id)   REFERENCES segmento (id)
);

-- Para resolver "en qué itinerarios está este segmento" sin recorrer la tabla.
CREATE INDEX idx_itin_segmento_segmento ON itinerario_segmento (segmento_id);

-- =============================================
-- RESERVA
-- =============================================
CREATE TABLE reserva (
    id                  BIGSERIAL   NOT NULL,
    usuario_id          BIGINT      NOT NULL,
    itinerario_id       BIGINT      NOT NULL,
    estado              VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
    fecha_creacion      TIMESTAMP   NOT NULL DEFAULT now(),
    fecha_actualizacion TIMESTAMP   NOT NULL DEFAULT now(),

    -- Control de concurrencia optimista: evita el overwrite silencioso cuando
    -- dos usuarios modifican la misma reserva. Lo administra @Version en JPA.
    version             INTEGER     NOT NULL DEFAULT 0,

    -- Clave de idempotencia generada por el cliente: evita reservas duplicadas
    -- ante reintentos de red o doble click, incluso con pedidos simultáneos.
    idempotency_key     UUID        NOT NULL,

    CONSTRAINT pk_reserva                 PRIMARY KEY (id),
    CONSTRAINT uq_reserva_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_reserva_usuario         FOREIGN KEY (usuario_id)    REFERENCES usuario (id),
    CONSTRAINT fk_reserva_itinerario      FOREIGN KEY (itinerario_id) REFERENCES itinerario (id),
    CONSTRAINT ck_reserva_estado          CHECK (estado IN ('PENDIENTE', 'CONFIRMADA', 'CANCELADA'))
);

COMMENT ON CONSTRAINT uq_reserva_idempotency_key ON reserva IS
    'Garantiza que dos intentos con la misma clave produzcan una sola reserva. La garantía es de la base, no de la aplicación.';

CREATE INDEX idx_reserva_usuario    ON reserva (usuario_id);
CREATE INDEX idx_reserva_itinerario ON reserva (itinerario_id);

-- Tabla intermedia: N pasajeros por reserva, el mismo pasajero en N reservas.
CREATE TABLE reserva_pasajero (
    reserva_id  BIGINT NOT NULL,
    pasajero_id BIGINT NOT NULL,

    CONSTRAINT pk_reserva_pasajero      PRIMARY KEY (reserva_id, pasajero_id),
    CONSTRAINT fk_res_pas_reserva       FOREIGN KEY (reserva_id)  REFERENCES reserva (id) ON DELETE CASCADE,
    CONSTRAINT fk_res_pas_pasajero      FOREIGN KEY (pasajero_id) REFERENCES pasajero (id)
);

-- Para resolver "en qué reservas viaja este pasajero".
CREATE INDEX idx_reserva_pasajero_pasajero ON reserva_pasajero (pasajero_id);
