-- Módulo catalog (SPEC.md §4.6, S0.1): fichas del catálogo municipal, distribuciones e histórico de frescura.
-- Fechas del catálogo sin zona (hora local) -> timestamp; marcas de ingesta -> timestamptz.

CREATE TABLE catalog_dataset (
    source_id            integer PRIMARY KEY,
    title                text        NOT NULL,
    description          text,
    issued               timestamp,
    declared_modified    timestamp,
    metadata_updated     timestamp,
    declared_periodicity text,
    periodicity_days     integer,
    publication_status   text,
    has_geo              boolean,
    is_open              boolean,
    explorable           boolean     NOT NULL DEFAULT false,
    api_tag              text,
    has_api              boolean     NOT NULL DEFAULT false,
    latest_freshness     text,
    latest_ratio         double precision,
    latest_snapshot_on   date,
    first_seen_at        timestamptz NOT NULL,
    last_seen_at         timestamptz NOT NULL
);

CREATE INDEX catalog_dataset_title_idx ON catalog_dataset (lower(title));
CREATE INDEX catalog_dataset_periodicity_idx ON catalog_dataset (declared_periodicity);
CREATE INDEX catalog_dataset_freshness_idx ON catalog_dataset (latest_freshness);
CREATE INDEX catalog_dataset_modified_idx ON catalog_dataset (declared_modified);

CREATE TABLE catalog_distribution (
    dataset_source_id    integer NOT NULL REFERENCES catalog_dataset (source_id) ON DELETE CASCADE,
    ordinal              integer NOT NULL,
    source_id            integer,
    media_type           text,
    access_url           text,
    download_url         text,
    title                text,
    PRIMARY KEY (dataset_source_id, ordinal)
);

CREATE INDEX catalog_distribution_media_type_idx ON catalog_distribution (media_type);

CREATE TABLE catalog_freshness_snapshot (
    id                   uuid PRIMARY KEY,
    dataset_source_id    integer     NOT NULL REFERENCES catalog_dataset (source_id) ON DELETE CASCADE,
    observed_on          date        NOT NULL,
    taken_at             timestamptz NOT NULL,
    declared_age_days    integer,
    periodicity_days     integer,
    declared_ratio       double precision,
    declared_freshness   text        NOT NULL,
    observed_last_change timestamptz,
    observed_records     integer,
    observation_method   text,
    CONSTRAINT catalog_freshness_snapshot_day_uq UNIQUE (dataset_source_id, observed_on)
);

CREATE INDEX catalog_freshness_snapshot_dataset_idx ON catalog_freshness_snapshot (dataset_source_id, observed_on DESC);
