-- Fichas que dejan de aparecer en el listado municipal (ADR-013): la ficha no se borra (su histórico de
-- frescura es el producto) sino que se marca. delisted_at = inicio de la primera ingesta completa en la que
-- la ficha no apareció; NULL = apareció en la última. Se recalcula en cada ingesta y se limpia si reaparece.

ALTER TABLE catalog_dataset
    ADD COLUMN delisted_at timestamptz;

CREATE INDEX catalog_dataset_delisted_idx ON catalog_dataset (delisted_at);
