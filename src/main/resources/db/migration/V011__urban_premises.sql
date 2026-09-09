-- urban: locales con licencia (`registro-licencia`, S2.4, ADR-011, ADR-016).
--
-- Como en `citizen` (V009), lo que NO hay aquí es parte de la decisión: **no existe ninguna columna de texto
-- libre**. La fuente publica cuatro campos de texto y ninguno entra:
--   * `comments` del local y de cada licencia: 15 DNI con letra de control válida en el registro entero;
--   * `emplazamiento`: es la dirección, y ADR-011 §2 prohíbe geocodificar por dirección, así que no tiene uso;
--   * `actividad`: texto libre con 2 DNI dentro, redundante con el epígrafe IAE, que viene codificado en los
--     42.342 registros y es una taxonomía cerrada de 965 entradas.
-- La diferencia con ADR-012 es que aquí el texto **sí se descarga**: `fl` vacía los objetos anidados (una
-- licencia proyectada llega sin año ni expediente) y `removeproperties` se acepta sin aplicarse. La garantía es
-- por tanto la otra mitad: no hay columna donde guardarlo y el traductor no lo lee (probado en un test).
--
-- Tampoco hay `district_declared`: esta fuente **no declara junta** por ninguna vía (ni el local ni su portal,
-- S2.4 §6). Una columna siempre nula no es un contraste, es ruido con aspecto de contraste; la ausencia se
-- publica en los `caveats`. ADR-011 §3 sigue vigente para las fuentes que sí la declaran.
--
-- `district_id` no lleva clave ajena a `geo_district` a propósito: son módulos distintos y ninguno accede a las
-- tablas del otro (regla 3).

CREATE TABLE urban_premises (
    -- `id` del origen. Denso y estable: va de 0 a 42.914 con huecos.
    source_id       integer     PRIMARY KEY,
    -- Epígrafe IAE tal como lo publica el origen (`iae`), que es la actividad codificada. `iae_code` es
    -- `iae.identifier` ("16732"); sección y agrupación vienen de `iae.id` y permiten agrupar sin otra tabla.
    iae_code        text,
    iae_title       text,
    iae_section     integer,
    iae_group       integer,
    -- `estado`: 0, 1, 2 o 3. **No hay taxonomía publicada** (S2.4 §7): se guarda el código y no se le pone
    -- nombre, porque ponérselo sería inventar la taxonomía que la fuente no publica (regla 6).
    status_code     integer     NOT NULL,
    -- `codPortal` y `codVia`: identificadores del callejero. Sirven para agrupar locales del mismo portal, no
    -- para resolver territorio (eso lo hace la geometría).
    portal_code     text,
    street_code     text,
    -- `zonaSaturada`: código A..P y Ñ. La taxonomía publica 15 zonas y los datos usan 17 (`O` y `P` no están).
    saturated_zone  text,
    -- Fechas del origen: sin zona y en hora local de Zaragoza (S0.5), se guardan ya convertidas.
    created_at      timestamptz NOT NULL,
    -- `lastUpdated`: marca de agua del incremental. 22.044 registros comparten un mismo valor (carga por lotes),
    -- por eso el barrido nunca ordena por este campo; solo filtra por él (S2.4 §4).
    updated_at      timestamptz,
    -- `fechaBaja`: la traen 169 locales. Que exista no significa que el local esté cerrado: sin taxonomía de
    -- `estado` no se puede afirmar cuántos están activos, y ninguna cifra se llama «locales abiertos».
    deregistered_at timestamptz,
    -- Punto en WGS84, o NULL. 4.499 de 42.342 no lo traen (10,6 %) y quedan sin junta (ADR-011 §2).
    lon             double precision,
    lat             double precision,
    district_id     integer,
    -- RESOLVED · AMBIGUOUS · OUTSIDE · NO_POINT (geo.Assignment). En esta fuente no se ha observado ningún
    -- AMBIGUOUS y hay 16 OUTSIDE, pero los cuatro estados existen igual.
    assignment      text        NOT NULL,
    first_seen_at   timestamptz NOT NULL,
    last_seen_at    timestamptz NOT NULL,
    CONSTRAINT urban_premises_point_complete CHECK ((lon IS NULL) = (lat IS NULL)),
    CONSTRAINT urban_premises_assignment_point CHECK ((assignment = 'NO_POINT') = (lon IS NULL)),
    CONSTRAINT urban_premises_assignment_district CHECK (
        (district_id IS NOT NULL) = (assignment IN ('RESOLVED', 'AMBIGUOUS')))
);

-- Listado por alta descendente con desempate estable (regla 8) y marca de agua del incremental.
CREATE INDEX urban_premises_created_idx ON urban_premises (created_at DESC, source_id DESC);
CREATE INDEX urban_premises_updated_idx ON urban_premises (updated_at DESC) WHERE updated_at IS NOT NULL;
CREATE INDEX urban_premises_district_idx ON urban_premises (district_id) WHERE district_id IS NOT NULL;
CREATE INDEX urban_premises_iae_idx ON urban_premises (iae_code) WHERE iae_code IS NOT NULL;
CREATE INDEX urban_premises_iae_group_idx ON urban_premises (iae_section, iae_group);

-- Las licencias de cada local: 69.631 para 42.342 locales (media 1,64, máximo 12, 3 locales sin ninguna).
--
-- La clave es `(local, año, expediente)`, la única medida como única sobre el registro entero: no colisiona ni
-- una vez. `display_order` es el `orden` del origen y **no vale como clave** (950 colisiones en 788 locales);
-- se guarda porque es el orden con el que el ayuntamiento las presenta.
CREATE TABLE urban_premises_licence (
    premises_id     integer     NOT NULL REFERENCES urban_premises (source_id) ON DELETE CASCADE,
    -- `id.anyo` del expediente. El rango observado es 1913..2033: hay una licencia fechada en el futuro y se
    -- guarda tal cual, porque corregirla sería inventarse el dato.
    year            integer     NOT NULL,
    file_number     bigint      NOT NULL,
    display_order   integer,
    -- `tipo.id` y `tipo.title`: 47 tipos, taxonomía del origen con sus mayúsculas y sus solapes.
    type_id         integer     NOT NULL,
    type_name       text,
    -- `resolucion`: siempre a las 00:00:00 en el origen, así que es una fecha, no un instante.
    resolved_on     date,
    -- `idResolucion`: código de resolución, sin taxonomía publicada, como `estado`.
    resolution_code integer,
    created_at      timestamptz,
    updated_at      timestamptz,
    deregistered_at timestamptz,
    PRIMARY KEY (premises_id, year, file_number)
);

-- La serie del producto: licencias por año, y por año dentro de una junta (con el JOIN al local).
CREATE INDEX urban_premises_licence_year_idx ON urban_premises_licence (year);
CREATE INDEX urban_premises_licence_type_idx ON urban_premises_licence (type_id);
