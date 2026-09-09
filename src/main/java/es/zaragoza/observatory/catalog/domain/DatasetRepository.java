package es.zaragoza.observatory.catalog.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Puerto de persistencia de fichas del catálogo. Escritura idempotente por {@code sourceId} (regla 5). */
public interface DatasetRepository {

	/**
	 * Inserta o actualiza la ficha. Si ya existía conserva {@code firstSeenAt} y pone {@code lastSeenAt = seenAt};
	 * si no, ambos valen {@code seenAt}. Las distribuciones se sustituyen por completo.
	 */
	void upsert(Dataset dataset, Instant seenAt);

	/**
	 * Marca como no listadas (ADR-013) las fichas que no aparecieron en la ingesta iniciada en
	 * {@code runStartedAt} y quita la marca a las que han vuelto a aparecer. La ficha nunca se borra: su
	 * histórico de frescura se conserva.
	 */
	Delisting markNotSeenSince(Instant runStartedAt);

	/** Resultado de una pasada de marcado: fichas marcadas por primera vez y fichas que han vuelto al listado. */
	record Delisting(int delisted, int relisted) {

		public static final Delisting NONE = new Delisting(0, 0);
	}

	Optional<Dataset> findBySourceId(int sourceId);

	/** Todas las fichas, ordenadas por {@code sourceId}. */
	List<Dataset> findAll();

	long count();

	/** Marca desnormalizada de la última instantánea (para filtrar y ordenar el listado). */
	void recordLatestFreshness(int sourceId, DeclaredFreshness freshness, Double ratio, LocalDate observedOn);

	/**
	 * Fichas que toca observar: nunca observadas primero, después las observadas antes de {@code observedBefore},
	 * de más antigua a más reciente y con desempate por {@code sourceId}. Como máximo {@code limit}.
	 */
	List<Dataset> findDueForObservation(Instant observedBefore, int limit);

	/** Marca desnormalizada de la última observación (instante, método intentado y último cambio observado). */
	void recordObservation(int sourceId, Observation observation);

}
