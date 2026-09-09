package es.zaragoza.observatory.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.domain.DatasetRepository;
import es.zaragoza.observatory.catalog.domain.DatasetRepository.Delisting;

/** Registra una página de fichas ya traducidas: upsert idempotente por {@code sourceId} (regla 5). */
public class RegisterDatasets {

	private final DatasetRepository datasets;

	public RegisterDatasets(DatasetRepository datasets) {
		this.datasets = Objects.requireNonNull(datasets);
	}

	@Transactional
	public int register(List<Dataset> page, Instant seenAt) {
		for (Dataset dataset : page) {
			datasets.upsert(dataset, seenAt);
		}
		return page.size();
	}

	/**
	 * Marca las fichas que no aparecieron en la ingesta iniciada en {@code runStartedAt} y desmarca las que han
	 * vuelto (ADR-013). La ficha nunca se borra: su histórico de frescura es el producto.
	 */
	@Transactional
	public Delisting markNotSeenSince(Instant runStartedAt) {
		return datasets.markNotSeenSince(Objects.requireNonNull(runStartedAt));
	}

}
