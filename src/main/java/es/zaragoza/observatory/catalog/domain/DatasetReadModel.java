package es.zaragoza.observatory.catalog.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Puerto de lectura del monitor (SPEC.md §4.7, §4.9): el backend filtra, ordena, pagina y agrega. Los tipos de
 * consulta son del dominio para que el controlador no exponga detalles de persistencia.
 */
public interface DatasetReadModel {

	PageOf<DatasetListing> search(DatasetFilter filter, DatasetSort sort, PageRequest page);

	Optional<DatasetListing> find(int sourceId);

	CatalogSummary summary();

	/**
	 * Ficha más las marcas desnormalizadas de su última instantánea declarada y de su última observación (puede
	 * no haberlas aún), si datos.gob.es la lista la URL de su ficha allí ({@code federatedUrl}, S1.3) y, si dejó
	 * de aparecer en el listado municipal, cuándo se notó ({@code delistedAt}, ADR-013).
	 */
	record DatasetListing(Dataset dataset, DeclaredFreshness latestFreshness, Double latestRatio,
			LocalDate latestSnapshotOn, Instant observedAt, ObservationMethod latestObservationMethod,
			Instant latestObservedChange, String federatedUrl, Instant delistedAt) {

		public boolean federated() {
			return federatedUrl != null;
		}

		/** La ficha apareció en la última ingesta completa del listado municipal. */
		public boolean listed() {
			return delistedAt == null;
		}
	}

	/**
	 * Filtros combinables (todos opcionales). {@code periodicity} admite el valor {@link #UNDECLARED} para las
	 * fichas sin {@code accrualPeriodicity}; {@code observation} filtra por el método de la última observación;
	 * {@code federated} por presencia en datos.gob.es; {@code listed} por aparición en el último listado
	 * municipal ingerido (ADR-013).
	 */
	record DatasetFilter(String periodicity, String publicationStatus, Boolean hasGeo, Boolean open, Boolean hasApi,
			DeclaredFreshness freshness, String text, ObservationMethod observation, Boolean federated,
			Boolean listed) {

		public static final String UNDECLARED = "UNDECLARED";

		public static DatasetFilter none() {
			return new DatasetFilter(null, null, null, null, null, null, null, null, null, null);
		}
	}

	/** Ordenación explícita (regla 8). Siempre se desempata por {@code sourceId}. */
	record DatasetSort(Field field, Direction direction) {

		public DatasetSort {
			Objects.requireNonNull(field);
			Objects.requireNonNull(direction);
		}

		public static DatasetSort byTitle() {
			return new DatasetSort(Field.TITLE, Direction.ASC);
		}

		public enum Field {
			TITLE, SOURCE_ID, ISSUED, DECLARED_MODIFIED, METADATA_UPDATED, DECLARED_RATIO, OBSERVED_CHANGE
		}

		public enum Direction {
			ASC, DESC
		}
	}

	record PageRequest(int page, int size) {

		public static final int MAX_SIZE = 200;

		public PageRequest {
			if (page < 0) {
				throw new IllegalArgumentException("page must be >= 0");
			}
			if (size < 1 || size > MAX_SIZE) {
				throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
			}
		}
	}

	record PageOf<T>(List<T> items, int page, int size, long totalElements) {

		public PageOf {
			items = List.copyOf(items);
		}

		public int totalPages() {
			return size == 0 ? 0 : (int) Math.ceil(totalElements / (double) size);
		}
	}

	/**
	 * Agregados del catálogo. {@code byPeriodicity} usa {@link DatasetFilter#UNDECLARED} para las fichas sin
	 * periodicidad; {@code byDeclaredFreshness} y {@code byObservationMethod} incluyen todas las categorías, con 0
	 * donde no haya fichas; {@code withoutObservation} son las fichas aún no observadas.
	 * {@code datasets} cuenta todas las fichas ingeridas, listadas o no; {@code notListed} son las que no
	 * aparecieron en el último listado municipal ingerido (ADR-013).
	 */
	record CatalogSummary(long datasets, Map<DeclaredFreshness, Long> byDeclaredFreshness,
			Map<String, Long> byPeriodicity, long withApi, long open, long explorable, long withGeo,
			LocalDate latestSnapshotOn, long withoutSnapshot, Map<ObservationMethod, Long> byObservationMethod,
			long withoutObservation, long federated, long notListed) {
	}

}
