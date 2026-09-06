-- Inventario de endpoints (docs/spikes/S1.2-inventario-api.md): una fila por operación y tag del Swagger 2.0 de la
-- API municipal (sede/servicio/catalogo/api.json), sincronizada entera en cada ingesta. Se cruza con
-- catalog_dataset.api_tag.

CREATE TABLE catalog_api_endpoint (
    id            uuid PRIMARY KEY,
    tag           text        NOT NULL,
    method        text        NOT NULL,
    path          text        NOT NULL,
    url           text        NOT NULL,
    summary       text,
    ordinal       integer     NOT NULL,
    first_seen_at timestamptz NOT NULL,
    last_seen_at  timestamptz NOT NULL,
    CONSTRAINT catalog_api_endpoint_key_uq UNIQUE (tag, method, path)
);

CREATE INDEX catalog_api_endpoint_tag_idx ON catalog_api_endpoint (tag, ordinal);
CREATE INDEX catalog_dataset_api_tag_idx ON catalog_dataset (api_tag);
