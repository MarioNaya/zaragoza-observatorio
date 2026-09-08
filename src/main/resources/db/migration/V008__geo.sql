-- geo: juntas municipales y vecinales con geometría y padrón (S0.4, S2.1, ADR-011).
--
-- La unidad territorial del proyecto es la junta (29), no el barrio: los barrios no son dato abierto (S0.4).
-- `id` es el de la API municipal (`distrito.id`, = `iddatosab` de los indicadores) y `padron_id` la otra
-- numeración (`idpadron`), la que usan los datasets de población SOCIO24: las dos vienen publicadas juntas en
-- `distrito/{id}.indicadores` (S2.1).
--
-- La geometría se guarda como `geometry` y no como `geometry(Polygon, …)` a propósito: hoy las 29 juntas son
-- polígonos simples sin agujeros (S2.1), pero un MultiPolygon en origen no debe tumbar la ingesta de la capa
-- base. El SRID sí se fija: 4326, que es lo que devuelve la API con `srsname=wgs84`.
--
-- La resolución punto → junta es `ST_Contains` sobre esta tabla (ADR-011): la API municipal no la resuelve.
-- Los polígonos NO son una partición del término (se solapan en el entorno de Juslibol), así que la consulta
-- puede devolver más de una junta: la ambigüedad se registra, no se resuelve en silencio.

CREATE TABLE geo_district (
    id            integer PRIMARY KEY,
    padron_id     integer,
    name          text        NOT NULL,
    kind          text        NOT NULL,
    boundary      geometry(Geometry, 4326) NOT NULL,
    first_seen_at timestamptz NOT NULL,
    last_seen_at  timestamptz NOT NULL
);

CREATE INDEX geo_district_boundary_idx ON geo_district USING GIST (boundary);
CREATE UNIQUE INDEX geo_district_padron_idx ON geo_district (padron_id) WHERE padron_id IS NOT NULL;

-- Padrón por junta y año, tal como lo publica `distrito/{id}.indicadores`. La serie tiene 2020, 2021, 2022 y
-- 2024: **no hay 2023** (S2.1), así que ninguna lectura puede suponerla continua.
CREATE TABLE geo_population_record (
    district_id integer     NOT NULL REFERENCES geo_district (id),
    year        integer     NOT NULL,
    total       integer,
    spaniards   integer,
    foreigners  integer,
    under_16    integer,
    under_18    integer,
    households  integer,
    area_km2    double precision,
    ingested_at timestamptz NOT NULL,
    PRIMARY KEY (district_id, year)
);
