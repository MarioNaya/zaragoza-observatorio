-- Módulo ingestion (SPEC.md §4.5): registro de ejecuciones y páginas crudas con retención.
-- Tipos: text para cadenas, timestamptz para instantes (las entidades JPA los declaran igual; ddl-auto=validate).

CREATE TABLE ingestion_run (
    id                   uuid PRIMARY KEY,
    source               text        NOT NULL,
    dataset_id           text        NOT NULL,
    started_at           timestamptz NOT NULL,
    finished_at          timestamptz,
    status               text        NOT NULL,
    records              bigint      NOT NULL DEFAULT 0,
    pages                integer     NOT NULL DEFAULT 0,
    source_last_modified timestamptz,
    error                text
);

CREATE INDEX ingestion_run_dataset_started_idx ON ingestion_run (source, dataset_id, started_at DESC);

CREATE TABLE raw_payload (
    id                   uuid PRIMARY KEY,
    run_id               uuid        NOT NULL REFERENCES ingestion_run (id) ON DELETE CASCADE,
    source               text        NOT NULL,
    dataset_id           text        NOT NULL,
    page_number          integer     NOT NULL,
    url                  text        NOT NULL,
    content_type         text,
    body                 text        NOT NULL,
    byte_size            integer     NOT NULL,
    fetched_at           timestamptz NOT NULL,
    source_last_modified timestamptz,
    CONSTRAINT raw_payload_run_page_uq UNIQUE (run_id, page_number)
);

CREATE INDEX raw_payload_fetched_at_idx ON raw_payload (fetched_at);
