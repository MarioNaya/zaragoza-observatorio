-- spending: presupuesto de gastos (S3.2, ADR-003).
--
-- La segunda fuente del contexto, y la única del producto entero con dinero PAGADO: la contratación OCDS publica
-- licitado y adjudicado y nada más (ADR-017 §7, `planning` en 0 documentos e `implementation` en 0).
--
-- Aquí tampoco hay ninguna columna territorial, y por la misma razón que en la contratación: el gasto
-- presupuestario no se publica por junta y no se geocodifica el nombre de una partida para inventarle un sitio
-- (ADR-003 §1, ADR-011 §2).
--
-- La unidad no es la partida sino la INSTANTÁNEA: 140 fotos datadas del presupuesto entero, de 2006-12-31 a
-- 2026-08-31, 154.508 filas en total. La misma partida aparece una vez por cada fecha, con los importes que
-- tenía ese día, y cada foto es acumulada desde enero de su ejercicio: sumar las doce fotos de un año contaría
-- el mismo euro doce veces.

-- El censo de instantáneas. Existe la fila desde que el censo publica la fecha, con partidas o sin ellas: la
-- carga inicial son 396 peticiones repartidas en lotes, y una instantánea censada y aún sin leer es una fila
-- legítima, no un registro a medias.
CREATE TABLE spending_budget_snapshot (
    -- La fecha que publica el censo, en `yyyyMMdd` en el origen.
    snapshot_date    date        PRIMARY KEY,
    -- PENDING (nunca pedida) · LOADED (trajo partidas) · EMPTY (200 sin partidas) · ABSENT (su URL no responde).
    read_status      text        NOT NULL,
    attempts         integer     NOT NULL DEFAULT 0,
    last_attempt_at  timestamptz,
    -- Cuándo toca la siguiente lectura. NULL significa CONGELADA, y es lo que hace barata a esta fuente: una
    -- instantánea publicada no se reescribe (S3.2 §3), así que se lee una vez y no se vuelve a pedir. Solo la
    -- más reciente conserva cadencia, porque es la única que la fuente podría rehacer.
    next_attempt_at  timestamptz,
    -- Partidas cargadas y `totalCount` que declaró la fuente. Se guardan los dos para poder contrastarlos: un
    -- desajuste no invalida la instantánea, pero se ve.
    lines            integer,
    reported_count   integer,
    -- Los ocho importes de la instantánea, sumados. No es denormalización por comodidad: son la serie del
    -- producto —presupuestado, comprometido, ejecutado y pagado a lo largo de veinte años— y calcularla sumando
    -- 154.508 filas en cada petición sería pagar el histórico entero por una gráfica.
    credit_initial      numeric(18, 2),
    credit_modification numeric(18, 2),
    credit_final        numeric(18, 2),
    committed           numeric(18, 2),
    obligations         numeric(18, 2),
    payments            numeric(18, 2),
    payments_pending    numeric(18, 2),
    credit_remaining    numeric(18, 2),
    first_seen_at    timestamptz NOT NULL,
    last_seen_at     timestamptz NOT NULL,
    CONSTRAINT spending_budget_snapshot_status CHECK (
        read_status IN ('PENDING', 'LOADED', 'EMPTY', 'ABSENT')),
    CONSTRAINT spending_budget_snapshot_attempts CHECK (attempts >= 0)
);

-- El planificador pide «las que tocan»: este índice es su plan. Las congeladas (next_attempt_at NULL) no entran.
CREATE INDEX spending_budget_snapshot_due_idx ON spending_budget_snapshot (next_attempt_at, snapshot_date)
    WHERE next_attempt_at IS NOT NULL;

-- Una partida presupuestaria en una instantánea.
--
-- La clave es (snapshot_date, concept). El `id` del origen NO se guarda: es `fecha + "-" + concepto`, o sea esta
-- misma clave con la fecha pegada delante. Y `concept` lleva dentro los dos dígitos del ejercicio
-- (`26GUR--1513-6190325`), así que no cruza de año: de los 22.838 conceptos distintos del histórico, ninguno
-- aparece en dos ejercicios (S3.2 §6).
CREATE TABLE spending_budget_line (
    snapshot_date  date    NOT NULL REFERENCES spending_budget_snapshot (snapshot_date) ON DELETE CASCADE,
    concept        text    NOT NULL,
    area_id        text,
    area           text,
    -- Capítulo económico 1..9. El endpoint se llama «gasto corriente» y trae también el capítulo 6, inversiones
    -- reales (305 partidas de 1.251 en la última instantánea), y los financieros: es el presupuesto de gastos
    -- entero. El nombre del endpoint engaña y la API lo dice en `caveats` (S3.2 §5).
    chapter_id     integer,
    chapter        text,
    -- NULL en 2010-2014 y parcial en otros seis ejercicios: la clasificación por programa no existía. Son 32.618
    -- filas de 154.508, y el nulo es el dato, no un fallo de carga (S3.2 §7).
    programme_id   text,
    programme      text,
    organ_id       text,
    organ          text,
    -- Epígrafe económico. Los ejercicios antiguos lo publican relleno con espacios; el traductor recorta, que es
    -- normalizar formato y no interpretar (regla 6).
    item_id        text,
    item           text,
    -- El nombre de la partida, que es lo que dice EN QUÉ se gasta. Se guarda —sin él una partida es un importe
    -- sin concepto— salvo cuando nombra a una persona física: cuatro filas de los cierres de 2006-2009 son una
    -- pensión «a la viuda de D. …». Ahí se guarda NULL con la marca puesta (S3.2 §8, regla 22).
    heading          text,
    heading_redacted boolean NOT NULL DEFAULT false,
    -- Los ocho importes. Aquí el cero SÍ es un valor legítimo, al revés que en un importe de contratación: una
    -- partida sin ejecutar tiene 0 € de obligación neta, y eso es un dato. Sobre la instantánea entera cuadran
    -- al céntimo: definitivo = inicial + modificaciones, remanente = definitivo − obligación neta, pendiente de
    -- pago = obligación neta − pago neto (S3.2 §5).
    credit_initial      numeric(18, 2) NOT NULL DEFAULT 0,
    credit_modification numeric(18, 2) NOT NULL DEFAULT 0,
    credit_final        numeric(18, 2) NOT NULL DEFAULT 0,
    committed           numeric(18, 2) NOT NULL DEFAULT 0,
    -- Obligación neta reconocida: EL GASTO EJECUTADO. Es la cifra que no existía en el producto hasta esta
    -- fuente, y no es intercambiable con las otras tres.
    obligations         numeric(18, 2) NOT NULL DEFAULT 0,
    -- Pago neto: lo que ha salido de la caja.
    payments            numeric(18, 2) NOT NULL DEFAULT 0,
    payments_pending    numeric(18, 2) NOT NULL DEFAULT 0,
    credit_remaining    numeric(18, 2) NOT NULL DEFAULT 0,
    PRIMARY KEY (snapshot_date, concept),
    -- La mitad de la garantía de la regla 22 que vive en la base de datos: aunque alguien cambiara el traductor,
    -- una fila marcada como redactada no puede llevar el texto (misma figura que
    -- `spending_award_party_no_natural_identity` en V012).
    CONSTRAINT spending_budget_line_redacted_has_no_text CHECK (
        NOT heading_redacted OR heading IS NULL)
);

-- Los ejes de agregación dentro de una instantánea (regla 8: el backend agrega).
CREATE INDEX spending_budget_line_chapter_idx ON spending_budget_line (snapshot_date, chapter_id);
CREATE INDEX spending_budget_line_area_idx ON spending_budget_line (snapshot_date, area_id);
CREATE INDEX spending_budget_line_programme_idx ON spending_budget_line (snapshot_date, programme_id);
CREATE INDEX spending_budget_line_organ_idx ON spending_budget_line (snapshot_date, organ_id);
-- El listado por defecto: la partida que más gasto ejecutado tiene, con desempate estable por código.
CREATE INDEX spending_budget_line_obligations_idx ON spending_budget_line (snapshot_date, obligations DESC, concept);
