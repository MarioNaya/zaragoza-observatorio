-- spending: subvenciones (S3.3, ADR-018, ADR-003 §1).
--
-- La tercera y última fuente del contexto de gasto, y la primera del proyecto entero en la que el dato personal
-- ES el contenido: una subvención sin beneficiario es un importe sin destinatario.
--
-- Tampoco hay aquí ninguna columna territorial, por lo mismo que en la contratación y el presupuesto: las
-- subvenciones no se publican por junta y no se geocodifica una dirección para inventarles un sitio
-- (ADR-003 §1, ADR-011 §2).
--
-- La fuente son CUATRO recursos independientes y ninguno espera a otro: las convocatorias y las concesiones
-- salen de `ayuda-subvencion`, el directorio de beneficiarios y el enlace concesión->beneficiario salen de
-- `ayuda-subvencion-v2`. Por eso el enlace tiene tabla propia: así el orden de ingesta da igual.

-- Convocatorias: la unidad de la que cuelga todo. 1.589 de 2014 a 2026.
CREATE TABLE spending_grant_call (
    id                  integer PRIMARY KEY,
    -- Nombre de la convocatoria. Ninguno de los 1.589 lleva identidad dentro, pero la restricción va igual: lo
    -- que protege es del futuro, no de lo medido.
    title               text,
    fiscal_year         text,
    multi_year          boolean,
    valid_from          date,
    valid_to            date,
    submission_from     date,
    submission_to       date,
    -- Lo que se puso a disposición, NO lo repartido. Lo repartido es la suma de los importes concedidos de sus
    -- concesiones, y las dos cifras se publican por separado (ADR-018 §6).
    budget              numeric(18, 2),
    advance_percentage  integer,
    -- El gestor es un CARGO, no una persona: los 68 que publica el origen son roles («Concejal Presidente de la
    -- Junta Municipal de Distrito de Torrero»).
    manager_id          text,
    manager             text,
    function_id         text,
    function            text,
    purpose_id          text,
    purpose             text,
    type_id             text,
    type                text,
    -- Línea de financiación. Solo llega si la proyección la pide con ruta con punto: `fl` recorta por subárbol y
    -- deja vacío el tercer nivel (S3.3 §3).
    line_id             text,
    line                text,
    scope_id            text,
    scope               text,
    area_id             text,
    area                text,
    first_seen_at       timestamptz NOT NULL,
    last_seen_at        timestamptz NOT NULL,
    CONSTRAINT spending_grant_call_title_has_no_identity CHECK (
        title IS NULL
        OR (title !~ '\m[0-9]{8}[A-Za-z]\M' AND title !~ '\m[XYZxyz][0-9]{7}[A-Za-z]\M'))
);

CREATE INDEX spending_grant_call_year_idx ON spending_grant_call (fiscal_year, id);
CREATE INDEX spending_grant_call_line_idx ON spending_grant_call (line_id, id);

-- Beneficiarios. El identificador es el que publica la fuente: un SEUDÓNIMO estable, y es lo que permite contar
-- cuánto recibe un mismo beneficiario sin nombrarlo.
--
-- De las 20.910 fichas distintas, 16.450 son personas físicas. De esas no entra el nombre, ni el NIF, ni el
-- enmascarado. Y de NADIE entra dato de contacto: el directorio publica domicilio, teléfono y correo, y 1.346 de
-- esos correos son de un proveedor gratuito y 186 tienen forma nombre.apellido@… (ADR-018 §4, S3.3 §6).
CREATE TABLE spending_grant_beneficiary (
    id              text    PRIMARY KEY,
    -- Razón social. NULL si es persona física.
    name            text,
    -- NIF de persona jurídica. NULL si es persona física; el enmascarado de la fuente no se guarda nunca.
    legal_nif       text,
    -- Por CUALQUIERA de las dos señales —clasificación del directorio o identificador enmascarado—, que
    -- discrepan en 645 de 44.316 concesiones (1,5 %). Ante la duda, no se publica identidad.
    natural_person  boolean NOT NULL,
    -- La segunda señal, guardada aparte porque llega de OTRO recurso. Sin esta columna, releer el directorio
    -- después de los enlaces devolvería el nombre a un beneficiario ya marcado como persona física: el orden de
    -- ingesta cambiaría lo que se guarda, y eso en una regla de datos personales no vale.
    masked_identifier boolean NOT NULL DEFAULT false,
    -- El código del origen, sin traducir (regla 6): personas-fisicas, otros, entidad-deportiva,
    -- entidad-cultural-o-filantropica, administracion-local, entidad-religiosa.
    classification  text,
    first_seen_at   timestamptz NOT NULL,
    last_seen_at    timestamptz NOT NULL,
    -- La mitad de la garantía de ADR-018 §4 que vive en la base de datos: aunque alguien cambiara el traductor,
    -- una persona física no puede llevar nombre ni identificador fiscal (misma figura que
    -- `spending_award_party_no_natural_identity` en V012).
    CONSTRAINT spending_grant_beneficiary_no_natural_identity CHECK (
        NOT natural_person OR (name IS NULL AND legal_nif IS NULL))
);

CREATE INDEX spending_grant_beneficiary_class_idx ON spending_grant_beneficiary (classification, id);

-- Concesiones: 46.925 de 2013 a 2026. El censo completo es el de `ayuda-subvencion/resolucion`; la v2 es un
-- subconjunto estricto que esconde 2013 y 2014 (S3.3 §4).
CREATE TABLE spending_grant (
    id              bigint  PRIMARY KEY,
    -- Sin clave ajena a propósito: los cuatro recursos se ingieren por separado y una concesión puede nombrar
    -- una convocatoria que aún no se ha leído. El enlace se resuelve al leer, y lo que falte se ve.
    call_id         integer,
    -- Para qué era la ayuda. Se guarda porque es media lectura de la fuente, con el documento de identidad
    -- sustituido por un marcador cuando lo llevaba: 2.378 DNI y 381 NIE, todos con letra de control válida
    -- (ADR-018 §5).
    title           text,
    title_redacted  boolean NOT NULL DEFAULT false,
    file_number     text,
    -- Ninguno de estos importes es dinero pagado. `granted` es lo acordado; lo que salió de la caja está en el
    -- presupuesto, en la obligación neta y el pago neto (S3.2). El origen no declara moneda.
    requested       numeric(18, 2),
    granted         numeric(18, 2),
    annual          numeric(18, 2),
    -- `numAnualidades` en el origen. Pese al nombre, los valores observados son AÑOS, no un número de
    -- anualidades: se guarda tal cual y no se reinterpreta (regla 6).
    annuities       integer,
    requested_on    date,
    -- El eje de la serie. Siete registros traen año 0002, 0019 o 0022: no se corrigen ni se tiran, su grupo sale
    -- como tal (ADR-018 §7).
    granted_on      date,
    agreed_on       date,
    first_seen_at   timestamptz NOT NULL,
    last_seen_at    timestamptz NOT NULL,
    -- La otra mitad de la garantía de ADR-018 §5, y la primera restricción de integridad personal del proyecto
    -- que se apoya en una expresión regular y no en un NULL: con esto puesto, un traductor roto no puede colar
    -- un documento de identidad.
    CONSTRAINT spending_grant_title_has_no_identity CHECK (
        title IS NULL
        OR (title !~ '\m[0-9]{8}[A-Za-z]\M' AND title !~ '\m[XYZxyz][0-9]{7}[A-Za-z]\M'))
);

-- El listado por defecto: la concesión más reciente, con desempate estable por identificador.
CREATE INDEX spending_grant_granted_on_idx ON spending_grant (granted_on DESC, id DESC);
CREATE INDEX spending_grant_amount_idx ON spending_grant (granted DESC, id DESC);
CREATE INDEX spending_grant_call_idx ON spending_grant (call_id, id);

-- El enlace concesión -> beneficiario, que solo publica la v2. Tabla propia porque es un hecho de OTRO recurso
-- y porque así el orden de ingesta da igual: un enlace de una concesión todavía no leída se guarda y se resuelve
-- después. Los 2.609 registros que solo publica la v1 (2013, 2014 y siete de 2015) no tienen fila aquí, y ese
-- hueco se publica en `caveats` en vez de rellenarse adivinando por nombre (ADR-018 §7).
CREATE TABLE spending_grant_link (
    grant_id        bigint PRIMARY KEY,
    beneficiary_id  text   NOT NULL,
    last_seen_at    timestamptz NOT NULL
);

CREATE INDEX spending_grant_link_beneficiary_idx ON spending_grant_link (beneficiary_id, grant_id);
