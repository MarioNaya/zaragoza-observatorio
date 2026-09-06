package es.zaragoza.observatory.catalog.domain;

import java.util.Objects;

import es.zaragoza.observatory.catalog.domain.DatasetReadModel.DatasetSort.Direction;
import es.zaragoza.observatory.catalog.domain.DatasetReadModel.PageOf;
import es.zaragoza.observatory.catalog.domain.DatasetReadModel.PageRequest;

/**
 * Puerto de lectura de la federación en datos.gob.es y de su cruce con el catálogo por {@code sourceId} (S1.3):
 * qué datasets federados tienen ficha en el listado municipal y cuáles no, y cuántas fichas no están federadas.
 */
public interface FederationReadModel {

	PageOf<FederatedListing> search(FederationFilter filter, FederationSort sort, PageRequest page);

	FederationSummary summary();

	/** @param inCatalog la ficha existe en el catálogo ingerido */
	record FederatedListing(FederatedDataset dataset, boolean inCatalog) {
	}

	/** Filtros opcionales: con o sin ficha en el catálogo; texto en el título. */
	record FederationFilter(Boolean inCatalog, String text) {

		public static FederationFilter none() {
			return new FederationFilter(null, null);
		}
	}

	record FederationSort(Field field, Direction direction) {

		public FederationSort {
			Objects.requireNonNull(field);
			Objects.requireNonNull(direction);
		}

		public static FederationSort bySourceId() {
			return new FederationSort(Field.SOURCE_ID, Direction.ASC);
		}

		public enum Field {
			SOURCE_ID, TITLE
		}
	}

	/**
	 * @param federated datasets del publicador en datos.gob.es
	 * @param inCatalog de ellos, con ficha en el catálogo ingerido
	 * @param notInCatalog de ellos, sin ficha en el listado municipal (partes de series y colecciones, S1.3)
	 * @param catalogNotFederated fichas del catálogo que datos.gob.es no lista
	 */
	record FederationSummary(long federated, long inCatalog, long notInCatalog, long catalogNotFederated) {
	}

}
