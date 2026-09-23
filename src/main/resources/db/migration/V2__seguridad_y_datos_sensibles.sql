-- =============================================================================
-- V2 - Remediación de seguridad.
--
-- Tres cambios, cada uno cierra una amenaza del modelo STRIDE
-- (docs/security/threat-model.md):
--
--   T-16  la clave de idempotencia deja de ser global y pasa a ser del usuario
--   T-06  el documento deja de ser clave natural global de pasajero
--   T-11  aparece el registro de auditoría append-only
--
-- Ninguno destruye datos: la migración es aplicable sobre una base con
-- reservas vivas.
-- =============================================================================

-- =============================================
-- T-16 — La clave de idempotencia se alcanza al usuario
--
-- La clave es un UUID que el cliente manda en un header, y un header queda en
-- los logs de acceso de cualquier proxy del camino. Con el UNIQUE global, quien
-- consiguiera una clave usada recuperaba la reserva completa de su dueño
-- —incluidos los documentos de los pasajeros— reenviándola en un alta.
--
-- Con la unicidad por (usuario, clave), una clave filtrada no sirve desde otra
-- identidad: para esa identidad es una clave nueva, y el alta que dispara es la
-- suya. La garantía de que un reintento no duplica sigue siendo de la base.
-- =============================================
ALTER TABLE reserva DROP CONSTRAINT uq_reserva_idempotency_key;

ALTER TABLE reserva
    ADD CONSTRAINT uq_reserva_usuario_idempotency_key UNIQUE (usuario_id, idempotency_key);

COMMENT ON CONSTRAINT uq_reserva_usuario_idempotency_key ON reserva IS
    'Dos intentos del mismo usuario con la misma clave producen una sola reserva. Alcanzada al usuario a propósito: una clave filtrada no sirve desde otra identidad.';

-- =============================================
-- T-06 — El documento deja de ser clave natural global
--
-- Con UNIQUE (documento), el alta reutilizaba la fila existente y la respuesta
-- devolvía los datos ALMACENADOS: enviando un documento ajeno, el 201 respondía
-- con el nombre, el apellido y la fecha de nacimiento reales de su titular. A la
-- inversa, registrar primero un documento con datos falsos se los imponía a la
-- reserva legítima que viniera después.
--
-- La deduplicación entre reservas era una optimización de almacenamiento; el
-- oráculo que habilitaba, no. El pasajero pasa a ser propio de la reserva: se
-- escriben más filas y no se cruza el borde de confianza.
-- =============================================
ALTER TABLE pasajero DROP CONSTRAINT uq_pasajero_doc;

-- =============================================
-- T-21 — El documento se guarda cifrado
--
-- Un dump de 'pasajero' era un dump de PII en claro, y el backup heredaba el
-- problema. El valor pasa a ser 'v1:' + Base64(IV || AES-256-GCM), que no entra
-- en 50 caracteres.
--
-- No se reescriben las filas existentes: PiiCipher reconoce el valor sin
-- prefijo como anterior al cambio y lo devuelve tal cual. Convertirlas acá
-- obligaría a poner la clave en la migración, que es exactamente donde no tiene
-- que estar.
-- =============================================
ALTER TABLE pasajero ALTER COLUMN documento TYPE VARCHAR(512);

COMMENT ON COLUMN pasajero.documento IS
    'Cifrado con AES-256-GCM (ver PiiCipher). No es buscable ni deduplicable a propósito: el cifrado no es determinista. Las filas anteriores a V2 pueden estar en claro.';

-- =============================================
-- T-11 — Registro de auditoría
--
-- 'reserva' guarda fecha_actualizacion pero no el actor, así que ante una
-- disputa con un pasajero o una aerolínea no había forma de probar quién
-- canceló. Esta tabla se escribe en la misma transacción que el cambio: no hay
-- ventana en la que el cambio quede y el registro se pierda.
--
-- No guarda ningún dato de pasajero. Prueba quién tocó qué, no repite el
-- contenido: si lo repitiera sería una segunda copia de la PII con su propia
-- política de retención.
-- =============================================
CREATE TABLE auditoria (
    id             BIGSERIAL    NOT NULL,
    fecha          TIMESTAMP    NOT NULL,
    accion         VARCHAR(40)  NOT NULL,
    resultado      VARCHAR(10)  NOT NULL,
    actor          VARCHAR(150) NOT NULL,
    recurso_tipo   VARCHAR(30)  NOT NULL,
    recurso_id     VARCHAR(50)  NOT NULL,
    recurso_version INTEGER,
    correlation_id VARCHAR(64),
    client_ip      VARCHAR(45),

    CONSTRAINT pk_auditoria        PRIMARY KEY (id),
    CONSTRAINT ck_auditoria_result CHECK (resultado IN ('ALLOWED', 'DENIED'))
);

COMMENT ON TABLE auditoria IS
    'Registro append-only de las operaciones sensibles. Las lecturas exitosas no se registran: serían una fila por GET y el mismo problema de retención de PII que se está acotando.';

-- Las dos consultas que se le hacen a esta tabla: "todo lo que pasó con esta
-- reserva" ante una disputa, y "todo lo que hizo este actor" ante un incidente.
CREATE INDEX idx_auditoria_recurso ON auditoria (recurso_tipo, recurso_id, fecha DESC);
CREATE INDEX idx_auditoria_actor   ON auditoria (actor, fecha DESC);

-- Append-only de verdad, no por convención.
--
-- Un registro de auditoría que la aplicación puede modificar no prueba nada:
-- quien comprometa la aplicación borra su rastro. El trigger lo impide en la
-- base, así que hace falta un DROP TRIGGER —que es DDL, y queda en los logs del
-- motor— para poder tocar una línea.
--
-- La purga por retención, cuando exista, se hará bajando el trigger
-- explícitamente en una migración, que es como tiene que verse una decisión de
-- ese tamaño.
CREATE OR REPLACE FUNCTION auditoria_solo_insert() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'La tabla auditoria es append-only: no se admite % ', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_auditoria_append_only
    BEFORE UPDATE OR DELETE ON auditoria
    FOR EACH ROW EXECUTE FUNCTION auditoria_solo_insert();
