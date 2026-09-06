package es.zaragoza.observatory.ingestion.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionJob;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;
import es.zaragoza.observatory.ingestion.domain.IngestionRun;
import es.zaragoza.observatory.ingestion.domain.IngestionRunRepository;
import es.zaragoza.observatory.shared.DatasetRef;

/** Implementación de la superficie pública {@link Ingestion}. */
public class IngestionService implements Ingestion {

	private final RunIngestion runIngestion;
	private final IngestionRunRepository runs;

	public IngestionService(RunIngestion runIngestion, IngestionRunRepository runs) {
		this.runIngestion = Objects.requireNonNull(runIngestion);
		this.runs = Objects.requireNonNull(runs);
	}

	@Override
	public IngestionRunSummary run(IngestionJob job) {
		return runIngestion.run(job).toSummary();
	}

	@Override
	public Optional<IngestionRunSummary> lastSuccessful(DatasetRef dataset) {
		return runs.lastSucceeded(dataset).map(IngestionRun::toSummary);
	}

	@Override
	public List<IngestionRunSummary> history(DatasetRef dataset, int limit) {
		return runs.history(dataset, limit).stream().map(IngestionRun::toSummary).toList();
	}

}
