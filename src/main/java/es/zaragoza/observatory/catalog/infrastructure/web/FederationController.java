package es.zaragoza.observatory.catalog.infrastructure.web;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import es.zaragoza.observatory.catalog.CatalogSources;
import es.zaragoza.observatory.catalog.domain.DatasetReadModel.PageOf;
import es.zaragoza.observatory.catalog.domain.FederationReadModel;
import es.zaragoza.observatory.catalog.domain.FederationReadModel.FederatedListing;
import es.zaragoza.observatory.catalog.domain.FederationReadModel.FederationFilter;
import es.zaragoza.observatory.catalog.domain.FederationReadModel.FederationSort;
import es.zaragoza.observatory.catalog.infrastructure.CatalogProperties;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.ApiPage;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.FederatedDatasetDto;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.PageMeta;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.Source;
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;

/**
 * Los datasets del publicador municipal en datos.gob.es y su cruce con el catálogo (S1.3, SPEC.md §4.7): qué está
 * federado con ficha, qué está federado sin ficha en el listado municipal y qué fichas no están federadas.
 */
@RestController
@RequestMapping("/api/v1/catalog")
class FederationController {

	static final Map<String, FederationSort.Field> SORT_FIELDS = Map.of(
			"id", FederationSort.Field.SOURCE_ID,
			"title", FederationSort.Field.TITLE);

	private final FederationReadModel federation;
	private final Ingestion ingestion;
	private final Source source;

	FederationController(FederationReadModel federation, Ingestion ingestion, CatalogProperties properties) {
		this.federation = federation;
		this.ingestion = ingestion;
		this.source = new Source(CatalogSources.FEDERATION.key(), properties.federation().url().toString());
	}

	@GetMapping("/federation")
	ApiPage<FederatedDatasetDto> federated(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + CatalogController.DEFAULT_SIZE) int size,
			@RequestParam(defaultValue = "id,asc") String sort, @RequestParam(required = false) Boolean inCatalog,
			@RequestParam(required = false) String q) {
		var parsed = Sorting.parse(sort, SORT_FIELDS);
		var pageRequest = Sorting.pageRequest(page, size);
		PageOf<FederatedListing> result = federation.search(new FederationFilter(inCatalog, q),
				new FederationSort(parsed.field(), parsed.direction()), pageRequest);
		return new ApiPage<>(source, ingestedAt(), Caveats.FEDERATION,
				new PageMeta(result.page(), result.size(), result.totalElements(), result.totalPages(),
						parsed.describe()),
				result.items().stream().map(FederatedDatasetDto::from).toList());
	}

	private Instant ingestedAt() {
		return ingestion.lastSuccessful(CatalogSources.FEDERATION).map(IngestionRunSummary::finishedAt).orElse(null);
	}

}
