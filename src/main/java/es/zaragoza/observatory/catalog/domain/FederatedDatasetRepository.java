package es.zaragoza.observatory.catalog.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * Puerto de persistencia de la federación (S1.3). El listado de datos.gob.es llega en varias páginas: cada página
 * hace upsert y, al completarse la ingesta, se dan de baja los datasets no vistos desde su inicio (regla 5).
 */
public interface FederatedDatasetRepository {

	/** Inserta o actualiza; conserva {@code firstSeenAt} y pone {@code lastSeenAt = seenAt}. */
	void upsert(FederatedDataset dataset, Instant seenAt);

	/** Elimina los datasets con {@code lastSeenAt} anterior a {@code since}; devuelve cuántos. */
	int deleteNotSeenSince(Instant since);

	Optional<FederatedDataset> findBySourceId(int sourceId);

	long count();

}
