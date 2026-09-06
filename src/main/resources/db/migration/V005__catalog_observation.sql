-- Eje observado de la frescura (SPEC.md §4.6, docs/spikes/S1.1-frescura-observada.md): resultado de la observación
-- en la instantánea del día y marcas desnormalizadas en la ficha para repartir el muestreo y para el listado.

ALTER TABLE catalog_freshness_snapshot
    ADD COLUMN observed_at        timestamptz,
    ADD COLUMN observed_url       text,
    ADD COLUMN observation_detail text,
    ADD COLUMN observation_error  text;

ALTER TABLE catalog_dataset
    ADD COLUMN observed_at               timestamptz,
    ADD COLUMN latest_observation_method text,
    ADD COLUMN latest_observed_change    timestamptz;

CREATE INDEX catalog_dataset_observed_at_idx ON catalog_dataset (observed_at NULLS FIRST, source_id);
CREATE INDEX catalog_dataset_observation_idx ON catalog_dataset (latest_observation_method);

-- Capa WFS de la distribución (formato[].wfsFeatureName), necesaria para GetFeature&resultType=hits.
ALTER TABLE catalog_distribution
    ADD COLUMN wfs_feature_name text;
