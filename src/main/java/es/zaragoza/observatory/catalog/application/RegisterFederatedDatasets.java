package es.zaragoza.observatory.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.catalog.domain.FederatedDataset;
import es.zaragoza.observatory.catalog.domain.FederatedDatasetRepository;

/**
 * Registra una página de datasets federados (upsert idempotente, regla 5) y, al completarse la ingesta, da de
 * baja los que no se hayan visto desde su inicio: datos.gob.es no publica marca de cambio y el listado llega en
 * varias páginas (S1.3).
 */
public class RegisterFederatedDatasets {

	private final FederatedDatasetRepository federated;

	public RegisterFederatedDatasets(FederatedDatasetRepository federated) {
		this.federated = Objects.requireNonNull(federated);
	}

	@Transactional
	public int register(List<FederatedDataset> page, Instant seenAt) {
		for (FederatedDataset dataset : page) {
			federated.upsert(dataset, seenAt);
		}
		return page.size();
	}

	@Transactional
	public int purgeNotSeenSince(Instant runStartedAt) {
		return federated.deleteNotSeenSince(Objects.requireNonNull(runStartedAt));
	}

}
