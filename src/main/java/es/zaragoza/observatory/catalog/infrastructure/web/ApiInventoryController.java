package es.zaragoza.observatory.catalog.infrastructure.web;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import es.zaragoza.observatory.catalog.CatalogSources;
import es.zaragoza.observatory.catalog.domain.ApiEndpoint;
import es.zaragoza.observatory.catalog.domain.ApiInventoryReadModel;
import es.zaragoza.observatory.catalog.domain.ApiInventoryReadModel.EndpointFilter;
import es.zaragoza.observatory.catalog.domain.ApiInventoryReadModel.EndpointSort;
import es.zaragoza.observatory.catalog.domain.DatasetReadModel.PageOf;
import es.zaragoza.observatory.catalog.infrastructure.CatalogProperties;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.ApiEndpointDto;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.ApiList;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.ApiPage;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.PageMeta;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.Source;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.TagSummaryDto;
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;

/**
 * Inventario de endpoints de la API municipal y su cruce con el catálogo por tag (S1.2, SPEC.md §4.7). Lectura
 * pública; el backend filtra, ordena y pagina (regla 8).
 */
@RestController
@RequestMapping("/api/v1/catalog")
class ApiInventoryController {

	static final Map<String, EndpointSort.Field> SORT_FIELDS = Map.of(
			"document", EndpointSort.Field.ORDINAL,
			"path", EndpointSort.Field.PATH,
			"tag", EndpointSort.Field.TAG);

	private final ApiInventoryReadModel inventory;
	private final Ingestion ingestion;
	private final Source source;

	ApiInventoryController(ApiInventoryReadModel inventory, Ingestion ingestion, CatalogProperties properties) {
		this.inventory = inventory;
		this.ingestion = ingestion;
		this.source = new Source(CatalogSources.API_INVENTORY.key(), properties.apiInventory().url().toString());
	}

	@GetMapping("/api-endpoints")
	ApiPage<ApiEndpointDto> endpoints(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + CatalogController.DEFAULT_SIZE) int size,
			@RequestParam(defaultValue = "document,asc") String sort, @RequestParam(required = false) String tag,
			@RequestParam(required = false) String q, @RequestParam(required = false) Boolean templated) {
		var parsed = Sorting.parse(sort, SORT_FIELDS);
		var pageRequest = Sorting.pageRequest(page, size);
		PageOf<ApiEndpoint> result = inventory.search(new EndpointFilter(tag, q, templated),
				new EndpointSort(parsed.field(), parsed.direction()), pageRequest);
		return new ApiPage<>(source, ingestedAt(), Caveats.API_INVENTORY,
				new PageMeta(result.page(), result.size(), result.totalElements(), result.totalPages(),
						parsed.describe()),
				result.items().stream().map(ApiEndpointDto::from).toList());
	}

	@GetMapping("/api-tags")
	ApiList<TagSummaryDto> tags() {
		return new ApiList<>(source, ingestedAt(), Caveats.API_INVENTORY, "tag,asc",
				inventory.tags().stream().map(TagSummaryDto::from).toList());
	}

	private Instant ingestedAt() {
		return ingestion.lastSuccessful(CatalogSources.API_INVENTORY).map(IngestionRunSummary::finishedAt)
				.orElse(null);
	}

}
