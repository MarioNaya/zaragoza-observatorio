package es.zaragoza.observatory.ingestion.domain;

import java.util.List;
import java.util.Optional;

import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.IngestionRunId;

/** Puerto de persistencia del registro de ejecuciones. */
public interface IngestionRunRepository {

	void save(IngestionRun run);

	Optional<IngestionRun> find(IngestionRunId id);

	Optional<IngestionRun> lastSucceeded(DatasetRef dataset);

	/** Últimas ejecuciones del dataset, la más reciente primero. */
	List<IngestionRun> history(DatasetRef dataset, int limit);

}
