package es.zaragoza.observatory.catalog.domain;

import java.util.List;
import java.util.Objects;

import es.zaragoza.observatory.catalog.domain.DatasetReadModel.PageOf;
import es.zaragoza.observatory.catalog.domain.DatasetReadModel.PageRequest;

/**
 * Puerto de lectura del inventario de endpoints y de su cruce con el catálogo (S1.2, SPEC.md §4.7). El cruce se
 * hace por el tag que declara la distribución {@code application/api} de cada ficha ({@link Dataset#apiTag()}):
 * un tag puede tener varias fichas (presupuestos: 3) y muchos tags no tienen ninguna (29 de 84).
 */
public interface ApiInventoryReadModel {

	PageOf<ApiEndpoint> search(EndpointFilter filter, EndpointSort sort, PageRequest page);

	/**
	 * Unión de los tags del Swagger y de los tags que declaran las fichas, ordenada por tag: un tag con
	 * {@code endpoints = 0} es un tag que el catálogo declara y el Swagger no documenta; un tag con
	 * {@code datasets} vacío es un tag documentado sin ficha en el catálogo.
	 */
	List<TagSummary> tags();

	InventorySummary summary();

	/** Filtros combinables (opcionales): tag exacto; texto en {@code path} o {@code summary}; sin plantilla. */
	record EndpointFilter(String tag, String text, Boolean templated) {

		public static EndpointFilter none() {
			return new EndpointFilter(null, null, null);
		}
	}

	/** Orden explícito (regla 8); siempre se desempata por {@code ordinal}. */
	record EndpointSort(Field field, DatasetReadModel.DatasetSort.Direction direction) {

		public EndpointSort {
			Objects.requireNonNull(field);
			Objects.requireNonNull(direction);
		}

		public static EndpointSort byDocument() {
			return new EndpointSort(Field.ORDINAL, DatasetReadModel.DatasetSort.Direction.ASC);
		}

		public enum Field {
			ORDINAL, PATH, TAG
		}
	}

	/**
	 * @param tag el tag
	 * @param endpoints operaciones documentadas con ese tag (0 si el Swagger no lo tiene)
	 * @param datasets fichas del catálogo que lo declaran, por {@code sourceId}
	 */
	record TagSummary(String tag, long endpoints, List<DatasetRef> datasets) {

		public TagSummary {
			datasets = List.copyOf(datasets);
		}
	}

	/** Referencia mínima a una ficha para el cruce. */
	record DatasetRef(int sourceId, String title) {
	}

	/**
	 * @param endpoints operaciones en el inventario
	 * @param tags tags distintos en el inventario
	 * @param datasetsWithTag fichas que declaran un tag
	 * @param datasetsWithDocumentedTag fichas cuyo tag existe en el inventario
	 * @param tagsWithoutDataset tags del inventario que ninguna ficha declara
	 */
	record InventorySummary(long endpoints, long tags, long datasetsWithTag, long datasetsWithDocumentedTag,
			long tagsWithoutDataset) {
	}

}
