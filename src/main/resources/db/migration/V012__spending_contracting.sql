-- spending: contratación pública OCDS (S3.1, ADR-003, ADR-017).
--
-- Aquí NO hay ninguna columna territorial, y su ausencia es la decisión de ADR-003 §1 confirmada sobre la fuente
-- entera: cero caminos de localización en 184 caminos distintos y 5.622 documentos. No se geocodifica el texto
-- de los títulos para inventar un lugar de ejecución (S3.1 §5).
--
-- Y NO existe una columna con `parties[].id` tal como lo publica el origen, porque ese identificador lleva el
-- NIF incrustado (`12619-NIF-B50892819-award-65236`). Se descompone al traducir: el NIF de persona jurídica se
-- guarda normalizado en `spending_award_party.tax_id`, y el de persona física no se guarda nunca —ni su nombre—.
-- La restricción `spending_award_party_no_natural_identity` es la mitad de esa garantía que vive en la base de
-- datos: aunque alguien cambiara el traductor, la fila no entraría (ADR-017 §2).

-- Un proceso de contratación. La fila existe desde que el ocid aparece en el censo, tenga release o no: el 29,7 %
-- del universo (2.379 de 8.001) responde 404 al pedir su detalle, y esos son los expedientes recientes cuyo
-- release aún no se ha publicado. Descartarlos publicaría un histórico que se acaba en 2022 sin decirlo.
CREATE TABLE spending_process (
    -- `ocid` completo: `ocds-1xraxc-{expediente}-ContractingProcess`.
    ocid                 text        PRIMARY KEY,
    -- Número de expediente extraído del ocid. Identifica sin una sola colisión en los 8.001, pero la secuencia
    -- 0..8.149 tiene 149 huecos: el universo se enumera, no se genera contando (S3.1 §1).
    file_number          integer,
    -- Si el proceso aparece en el listado SIN filtro, que es el que documenta la API. 2.271 de 8.001 no
    -- aparecen y solo salen mandando el interruptor `after` (S3.1 §1). Es un hecho publicable: quien pagine el
    -- listado documentado se lleva el 71,6 % del histórico creyendo que lo tiene entero.
    in_documented_list   boolean     NOT NULL DEFAULT false,
    -- PENDING (nunca pedido) · PUBLISHED (200 con release) · EMPTY (200 con `releases` vacío: 16 packages)
    -- · ABSENT (404, con un cuerpo que dice 400). Un 200 no garantiza release.
    release_status       text        NOT NULL,
    -- Intentos de lectura del detalle y fecha del último: de ahí sale la cadencia decreciente de reintento
    -- (ADR-017 §5). Sin ella, reintentar los 2.379 sin release serían 2.379 peticiones diarias para nada.
    attempts             integer     NOT NULL DEFAULT 0,
    last_attempt_at      timestamptz,
    -- Cuándo toca volver a pedirlo. La política vive en el dominio (`RetrySchedule`) y se materializa aquí para
    -- que el planificador pueda pedir «los que tocan» con un índice en vez de traerse la tabla y filtrarla.
    next_attempt_at      timestamptz NOT NULL,

    -- --- del release package (NULL mientras no haya release) ------------------------------------------------
    -- `publishedDate` del package y `releases[].date` coinciden en los 5.606: son el mismo dato, y se guarda uno.
    published_at         timestamptz,
    release_id           text,
    -- `releases[].tag[]`: award · contract · tender · tenderCancellation. En los 5.606 documentos el array trae
    -- siempre un solo valor; se guarda unido por comas por si algún día trae más, y se agrega por él.
    tags                 text,
    initiation_type      text,

    -- --- licitación ------------------------------------------------------------------------------------------
    -- El texto libre SÍ se guarda (ADR-017 §3): dice qué se contrató, y sin él un contrato es un importe sin
    -- objeto. El barrido completo no encontró un solo DNI ni NIE con letra de control válida, y las fórmulas de
    -- tratamiento son 21 en 5.606 documentos (0,4 %), frente al 47,9 % que hizo descartar la redacción en
    -- ADR-012. El CPV no lo sustituye: solo lo trae el 42,7 % de los procesos.
    tender_title         text,
    tender_description   text,
    -- complete · active · unsuccessful · cancelled, tal como los publica el origen.
    tender_status        text,
    procurement_method   text,
    procurement_category text,
    award_criteria       text,
    number_of_tenderers  integer,
    -- Importe LICITADO. No es dinero pagado ni adjudicado: el histórico son 4.359 M€ licitados frente a 1.819 M€
    -- adjudicados, y confundirlos es un factor de 2,4 (ADR-017 §7). Siempre en EUR en los 5.606.
    tender_amount        numeric(18, 2),
    tender_min_amount    numeric(18, 2),
    tender_currency      text,
    -- Órgano de contratación. `procuring_entity_id` es el DIR3 del organismo, no de una persona.
    procuring_entity_name text,
    procuring_entity_id  text,

    -- Etapa, y SOLO donde el documento la sostiene (ADR-017 §6): COMMITTED si algún contrato trae fecha de firma
    -- (3.410), PLANNED si la licitación está activa (305), NULL en los 1.560 procesos completos cuyo contrato es
    -- una cáscara sin fecha. Ponerles COMMITTED por deducción sería una conclusión del observatorio (regla 6).
    -- EXECUTED no existe aquí: `planning` aparece en 0 documentos e `implementation` en 0; sale del presupuesto.
    stage                text,
    -- Suma de las adjudicaciones **activas** del proceso, materializada para poder ordenar y agregar por ella.
    -- Las `unsuccessful` y las `pending` no se suman: son adjudicaciones que no adjudicaron. NULL —nunca cero—
    -- cuando el proceso no tiene ninguna activa con importe, para que no parezca un contrato de cero euros.
    awarded_amount       numeric(18, 2),

    first_seen_at        timestamptz NOT NULL,
    last_seen_at         timestamptz NOT NULL,
    CONSTRAINT spending_process_release_status CHECK (
        release_status IN ('PENDING', 'PUBLISHED', 'EMPTY', 'ABSENT')),
    CONSTRAINT spending_process_stage CHECK (stage IS NULL OR stage IN ('PLANNED', 'COMMITTED')),
    CONSTRAINT spending_process_attempts CHECK (attempts >= 0)
);

-- El planificador del detalle pide «los que tocan»: este índice es su plan.
CREATE INDEX spending_process_due_idx ON spending_process (next_attempt_at, file_number);
-- Listado por fecha de publicación con desempate estable (regla 8) y las series del producto.
CREATE INDEX spending_process_published_idx ON spending_process (published_at DESC, ocid DESC);
CREATE INDEX spending_process_file_number_idx ON spending_process (file_number);
CREATE INDEX spending_process_tender_status_idx ON spending_process (tender_status);
CREATE INDEX spending_process_stage_idx ON spending_process (stage);

-- Adjudicaciones. 3.410 procesos tienen alguna y 195 tienen más de una.
CREATE TABLE spending_award (
    ocid        text NOT NULL REFERENCES spending_process (ocid) ON DELETE CASCADE,
    -- `awards[].id` del origen ("65236-award").
    award_id    text NOT NULL,
    title       text,
    description text,
    -- active 3.280 · unsuccessful 609 · pending 13.
    status      text,
    awarded_on  timestamptz,
    -- Importe ADJUDICADO. Tampoco es dinero pagado: OCDS no publica ejecución (0 `implementation`).
    amount      numeric(18, 2),
    currency    text,
    PRIMARY KEY (ocid, award_id)
);

CREATE INDEX spending_award_status_idx ON spending_award (status);

-- Las partes de una adjudicación, SIN el identificador crudo del origen.
--
-- `parties[].id` lleva el NIF dentro y es además la clave con la que el documento enlaza adjudicación y
-- adjudicatario, así que no se puede «no pedir» como en ADR-012: se descompone. De 8.482 NIF incrustados, 8.481
-- son de persona jurídica (empiezan por letra) y 1 empieza por dígito.
--
-- La clave es el ordinal dentro de la adjudicación, no el identificador del origen, justamente para que ese
-- identificador no tenga dónde guardarse.
CREATE TABLE spending_award_party (
    ocid           text    NOT NULL,
    award_id       text    NOT NULL,
    ordinal        integer NOT NULL,
    -- NIF de persona jurídica, normalizado en mayúsculas. NULL si el origen no lo incrusta o si es persona física.
    tax_id         text,
    -- Razón social. NULL en persona física: para una persona el nombre es el identificador fuerte, y ADR-012 ya
    -- decidió no republicar identidad de personas aunque el ayuntamiento la publique (ADR-017 §2).
    name           text,
    natural_person boolean NOT NULL,
    PRIMARY KEY (ocid, award_id, ordinal),
    FOREIGN KEY (ocid, award_id) REFERENCES spending_award (ocid, award_id) ON DELETE CASCADE,
    -- La mitad de la garantía de ADR-017 §2 que vive en la base de datos: de una persona física no entra nada.
    CONSTRAINT spending_award_party_no_natural_identity CHECK (
        NOT natural_person OR (tax_id IS NULL AND name IS NULL))
);

-- La agregación por adjudicatario, que es lo que el NIF hace posible: por nombre sería frágil.
CREATE INDEX spending_award_party_tax_idx ON spending_award_party (tax_id) WHERE tax_id IS NOT NULL;

-- Contratos. `contracts[].id` aparece 4.970 veces pero `awardID`, `dateSigned` y `description` solo 3.410: los
-- otros 1.560 son cáscaras con un identificador y nada más. La tabla los admite tal cual **a propósito**, porque
-- son un hecho de la fuente y no un error de carga; son también la razón de que 1.560 procesos completos se
-- queden sin `stage` (ADR-017 §6).
CREATE TABLE spending_contract (
    ocid         text NOT NULL REFERENCES spending_process (ocid) ON DELETE CASCADE,
    contract_id  text NOT NULL,
    -- `awardID`: enlaza con `spending_award`. Sin clave ajena: 1.560 contratos no lo traen.
    award_id     text,
    title        text,
    description  text,
    -- terminated 3.391 · vacío 1.560 · active 19.
    status       text,
    signed_on    timestamptz,
    amount       numeric(18, 2),
    currency     text,
    period_start timestamptz,
    period_end   timestamptz,
    PRIMARY KEY (ocid, contract_id)
);

CREATE INDEX spending_contract_signed_idx ON spending_contract (signed_on) WHERE signed_on IS NOT NULL;

-- Los CPV de un proceso, sacados de la clasificación de sus artículos. Solo los trae el 42,7 % de los procesos.
--
-- Un proceso puede tener varios: `main` marca el `classification` principal y los demás vienen de
-- `additionalClassifications`. Por eso la suma de los grupos del eje `cpv` NO es el total de procesos, y la
-- respuesta lo dice en vez de dejar que se note (ADR-017 §7).
CREATE TABLE spending_process_cpv (
    ocid        text    NOT NULL REFERENCES spending_process (ocid) ON DELETE CASCADE,
    code        text    NOT NULL,
    description text,
    main        boolean NOT NULL,
    PRIMARY KEY (ocid, code)
);

CREATE INDEX spending_process_cpv_code_idx ON spending_process_cpv (code);
