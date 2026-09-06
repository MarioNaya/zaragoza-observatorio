package es.zaragoza.observatory.ingestion.domain;

import java.time.Instant;
import java.util.List;

import es.zaragoza.observatory.shared.IngestionRunId;

/** Puerto de almacenamiento de páginas crudas con retención (SPEC.md §4.5). */
public interface RawPayloadStore {

	void store(RawPayload payload);

	/** Páginas de una ejecución, ordenadas por número de página. */
	List<RawPayload> findByRun(IngestionRunId run);

	/** Borra las páginas obtenidas antes del instante dado y devuelve cuántas. */
	int purgeFetchedBefore(Instant threshold);

}
