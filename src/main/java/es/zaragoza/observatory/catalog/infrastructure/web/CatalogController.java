package es.zaragoza.observatory.catalog.infrastructure.web;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import es.zaragoza.observatory.catalog.CatalogSources;
import es.zaragoza.observatory.catalog.domain.ApiEndpointRepository;
import es.zaragoza.observatory.catalog.domain.ApiInventoryReadModel;
import es.zaragoza.observatory.catalog.domain.DatasetReadModel;
import es.zaragoza.observatory.catalog.domain.DatasetReadModel.DatasetFilter;
import es.zaragoza.observatory.catalog.domain.DatasetReadModel.DatasetListing;
import es.zaragoza.observatory.catalog.domain.DatasetReadModel.DatasetSort;
import es.zaragoza.observatory.catalog.domain.DatasetReadModel.PageOf;
import es.zaragoza.observatory.catalog.domain.DatasetReadModel.PageRequest;
import es.zaragoza.observatory.catalog.domain.DeclaredFreshness;
import es.zaragoza.observatory.catalog.domain.FederationReadModel;
import es.zaragoza.observatory.catalog.domain.FreshnessPolicy;
import es.zaragoza.observatory.catalog.domain.FreshnessSnapshotRepository;
import es.zaragoza.observatory.catalog.domain.ObservationMethod;
import es.zaragoza.observatory.catalog.infrastructure.CatalogProperties;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.ApiEndpointDto;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.ApiInventorySummary;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.ApiItem;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.ApiList;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.ApiPage;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.DatasetApiEndpoints;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.DatasetDetail;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.DatasetSummary;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.FederationSummary;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.FreshnessSnapshotDto;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.PageMeta;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.Source;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.Summary;
import es.zaragoza.observatory.catalog.infrastructure.web.CatalogDtos.Thresholds;
import es.zaragoza.observatory.ingestion.Ingestion;
import es.zaragoza.observatory.ingestion.IngestionRunSummary;
import es.zaragoza.observatory.shared.DatasetRef;

/**
 * Monitor de frescura del catálogo (SPEC.md §4.7). Lectura pública; el backend filtra, ordena y pagina (regla 8);
 * toda respuesta lleva origen, fecha de ingesta y {@code caveats}.
 */
@RestController
@RequestMapping("/api/v1/catalog")
class CatalogController {

	static final int DEFAULT_SIZE = 50;
	static final Map<String, DatasetSort.Field> SORT_FIELDS = Map.of(
			"title", DatasetSort.Field.TITLE,
			"id", DatasetSort.Field.SOURCE_ID,
			"issued", DatasetSort.Field.ISSUED,
			"declaredModified", DatasetSort.Field.DECLARED_MODIFIED,
			"metadataUpdated", DatasetSort.Field.METADATA_UPDATED,
			"declaredRatio", DatasetSort.Field.DECLARED_RATIO,
			"observedLastChange", DatasetSort.Field.OBSERVED_CHANGE);

	private final DatasetReadModel datasets;
	private final FreshnessSnapshotRepository snapshots;
	private final ApiEndpointRepository endpoints;
	private final ApiInventoryReadModel inventory;
	private final FederationReadModel federation;
	private final Ingestion ingestion;
	private final FreshnessPolicy policy;
	private final Source source;
	private final Source inventorySource;

	CatalogController(DatasetReadModel datasets, FreshnessSnapshotRepository snapshots,
			ApiEndpointRepository endpoints, ApiInventoryReadModel inventory, FederationReadModel federation,
			Ingestion ingestion, FreshnessPolicy policy, CatalogProperties properties) {
		this.datasets = datasets;
		this.snapshots = snapshots;
		this.endpoints = endpoints;
		this.inventory = inventory;
		this.federation = federation;
		this.ingestion = ingestion;
		this.policy = policy;
		this.source = new Source(CatalogSources.CATALOG.key(), properties.catalogUrl().toString());
		this.inventorySource = new Source(CatalogSources.API_INVENTORY.key(),
				properties.apiInventory().url().toString());
	}

	@GetMapping("/datasets")
	ApiPage<DatasetSummary> list(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + DEFAULT_SIZE) int size,
			@RequestParam(defaultValue = "title,asc") String sort, @RequestParam(required = false) String periodicity,
			@RequestParam(required = false) String status, @RequestParam(required = false) Boolean hasGeo,
			@RequestParam(required = false) Boolean open, @RequestParam(required = false) Boolean hasApi,
			@RequestParam(required = false) DeclaredFreshness freshness, @RequestParam(required = false) String q,
			@RequestParam(required = false) ObservationMethod observation,
			@RequestParam(required = false) Boolean federated, @RequestParam(required = false) Boolean listed) {
		DatasetSort datasetSort = parseSort(sort);
		PageRequest pageRequest = pageRequest(page, size);
		var filter = new DatasetFilter(periodicity, status, hasGeo, open, hasApi, freshness, q, observation,
				federated, listed);
		PageOf<DatasetListing> result = datasets.search(filter, datasetSort, pageRequest);
		return new ApiPage<>(source, ingestedAt(), Caveats.CATALOG,
				new PageMeta(result.page(), result.size(), result.totalElements(), result.totalPages(),
						describe(datasetSort)),
				result.items().stream().map(DatasetSummary::from).toList());
	}

	@GetMapping("/datasets/{id}")
	ApiItem<DatasetDetail> detail(@PathVariable int id) {
		DatasetListing listing = datasets.find(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "dataset " + id + " no está en el catálogo ingerido"));
		var latest = snapshots.latest(id).map(FreshnessSnapshotDto::from).orElse(null);
		return new ApiItem<>(source, ingestedAt(), Caveats.CATALOG,
				DatasetDetail.from(listing, latest, apiEndpoints(listing.dataset().apiTag())));
	}

	/** Operaciones del Swagger bajo el tag de la ficha (S1.2); vacío si no declara tag o el tag no está documentado. */
	private DatasetApiEndpoints apiEndpoints(String apiTag) {
		List<ApiEndpointDto> items = apiTag == null ? List.of()
				: endpoints.findByTag(apiTag).stream().map(ApiEndpointDto::from).toList();
		Boolean documented = apiTag == null ? null : !items.isEmpty();
		return new DatasetApiEndpoints(inventorySource, inventoryIngestedAt(), documented, items);
	}

	@GetMapping("/datasets/{id}/freshness-history")
	ApiList<FreshnessSnapshotDto> history(@PathVariable int id, @RequestParam(defaultValue = "90") int limit) {
		if (datasets.find(id).isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "dataset " + id + " no está en el catálogo ingerido");
		}
		if (limit < 1 || limit > 1000) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit debe estar entre 1 y 1000");
		}
		List<FreshnessSnapshotDto> items = snapshots.history(id, limit).stream().map(FreshnessSnapshotDto::from)
				.toList();
		return new ApiList<>(source, ingestedAt(), Caveats.CATALOG, "observedOn,desc", items);
	}

	@GetMapping("/summary")
	Summary summary() {
		var summary = datasets.summary();
		var api = inventory.summary();
		var fed = federation.summary();
		return new Summary(source, ingestedAt(), Caveats.CATALOG, summary.datasets(), summary.byDeclaredFreshness(),
				summary.byPeriodicity(), summary.withApi(), summary.open(), summary.explorable(), summary.withGeo(),
				summary.latestSnapshotOn(), summary.withoutSnapshot(), summary.byObservationMethod(),
				summary.withoutObservation(), summary.notListed(),
				new ApiInventorySummary(inventoryIngestedAt(), api.endpoints(), api.tags(), api.datasetsWithTag(),
						api.datasetsWithDocumentedTag(), api.tagsWithoutDataset()),
				new FederationSummary(lastIngestedAt(CatalogSources.FEDERATION), fed.federated(), fed.inCatalog(),
						fed.notInCatalog(), fed.catalogNotFederated()),
				new Thresholds(policy.onTimeMax(), policy.slightDelayMax(), policy.delayedMax()));
	}

	private Instant ingestedAt() {
		return lastIngestedAt(CatalogSources.CATALOG);
	}

	private Instant inventoryIngestedAt() {
		return lastIngestedAt(CatalogSources.API_INVENTORY);
	}

	private Instant lastIngestedAt(DatasetRef dataset) {
		return ingestion.lastSuccessful(dataset).map(IngestionRunSummary::finishedAt).orElse(null);
	}

	static DatasetSort parseSort(String sort) {
		var parsed = Sorting.parse(sort, SORT_FIELDS);
		return new DatasetSort(parsed.field(), parsed.direction());
	}

	static PageRequest pageRequest(int page, int size) {
		return Sorting.pageRequest(page, size);
	}

	static String describe(DatasetSort sort) {
		String name = SORT_FIELDS.entrySet().stream().filter(e -> e.getValue() == sort.field()).map(Map.Entry::getKey)
				.findFirst().orElse(sort.field().name());
		return name + "," + sort.direction().name().toLowerCase(Locale.ROOT);
	}

	/** Solo para comprobar que la lista blanca y el orden por defecto siguen alineados. */
	static Optional<DatasetSort.Field> sortField(String name) {
		return Optional.ofNullable(SORT_FIELDS.get(name));
	}

}
