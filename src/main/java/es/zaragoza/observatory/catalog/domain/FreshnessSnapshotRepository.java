package es.zaragoza.observatory.catalog.domain;

import java.util.List;
import java.util.Optional;

/** Puerto de persistencia del histórico de frescura. Una instantánea por dataset y día. */
public interface FreshnessSnapshotRepository {

	/** Inserta o sustituye la instantánea de ese dataset y día. */
	void upsert(FreshnessSnapshot snapshot);

	/** Histórico del dataset, la más reciente primero. */
	List<FreshnessSnapshot> history(int datasetSourceId, int limit);

	Optional<FreshnessSnapshot> latest(int datasetSourceId);

}
