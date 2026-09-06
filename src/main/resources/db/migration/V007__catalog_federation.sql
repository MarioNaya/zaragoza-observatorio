-- Federación en datos.gob.es (docs/spikes/S1.3-federacion.md): los datasets del publicador municipal (L01502973)
-- tal como los lista datos.gob.es, enlazados con catalog_dataset por source_id (el id municipal que lleva
-- identifier). Tabla propia y no una columna de la ficha: 108 datasets federados (partes de series y colecciones)
-- no aparecen en el listado catalogo.json.

CREATE TABLE catalog_federated_dataset (
    source_id     integer PRIMARY KEY,
    url           text        NOT NULL,
    title         text,
    first_seen_at timestamptz NOT NULL,
    last_seen_at  timestamptz NOT NULL
);

CREATE INDEX catalog_federated_dataset_seen_idx ON catalog_federated_dataset (last_seen_at);
