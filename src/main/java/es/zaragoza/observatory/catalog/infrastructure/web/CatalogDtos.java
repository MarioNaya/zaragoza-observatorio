package es.zaragoza.observatory.catalog.infrastructure.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import es.zaragoza.observatory.catalog.domain.ApiEndpoint;
import es.zaragoza.observatory.catalog.domain.ApiInventoryReadModel;
import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.domain.DatasetReadModel.DatasetListing;
import es.zaragoza.observatory.catalog.domain.DeclaredFreshness;
import es.zaragoza.observatory.catalog.domain.FederatedDataset;
import es.zaragoza.observatory.catalog.domain.FederationReadModel;
import es.zaragoza.observatory.catalog.domain.FreshnessSnapshot;
import es.zaragoza.observatory.catalog.domain.ObservationMethod;

/** Read models de la API del catálogo (SPEC.md §4.7, §4.9): útiles sin el frontend propio. */
final class CatalogDtos {

	private CatalogDtos() {
	}

	/** Origen del dato: referencia estable del dataset municipal y URL consultada. */
	record Source(String dataset, String url) {
	}

	record PageMeta(int number, int size, long totalElements, int totalPages, String sort) {
	}

	record ApiPage<T>(Source source, Instant ingestedAt, List<String> caveats, PageMeta page, List<T> items) {
	}

	record ApiItem<T>(Source source, Instant ingestedAt, List<String> caveats, T item) {
	}

	record ApiList<T>(Source source, Instant ingestedAt, List<String> caveats, String sort, List<T> items) {
	}

	record DatasetSummary(int id, String title, LocalDateTime issued, LocalDateTime declaredModified,
			LocalDateTime metadataUpdated, String declaredPeriodicity, Integer periodicityDays,
			String publicationStatus, Boolean hasGeo, Boolean open, boolean explorable, boolean hasApi, String apiTag,
			boolean federated, String federatedUrl, DeclaredFreshness latestFreshness, Double latestRatio,
			LocalDate latestSnapshotOn, Instant observedAt, ObservationMethod latestObservationMethod,
			Instant latestObservedChange, Instant firstSeenAt, Instant lastSeenAt, boolean listed,
			Instant delistedAt) {

		static DatasetSummary from(DatasetListing listing) {
			Dataset d = listing.dataset();
			return new DatasetSummary(d.sourceId(), d.title(), d.issued(), d.declaredModified(), d.metadataUpdated(),
					d.declaredPeriodicity(), d.periodicityDays(), d.publicationStatus(), d.hasGeo(), d.open(),
					d.explorable(), d.hasApiDistribution(), d.apiTag(), listing.federated(), listing.federatedUrl(),
					listing.latestFreshness(), listing.latestRatio(), listing.latestSnapshotOn(),
					listing.observedAt(), listing.latestObservationMethod(), listing.latestObservedChange(),
					d.firstSeenAt(), d.lastSeenAt(), listing.listed(), listing.delistedAt());
		}
	}

	record Distribution(Integer id, String mediaType, String accessUrl, String downloadUrl, String title,
			String wfsFeatureName) {

		static Distribution from(Dataset.Distribution d) {
			return new Distribution(d.sourceId(), d.mediaType(), d.accessUrl(), d.downloadUrl(), d.title(),
					d.wfsFeatureName());
		}
	}

	record DatasetDetail(int id, String title, String description, LocalDateTime issued,
			LocalDateTime declaredModified, LocalDateTime metadataUpdated, String declaredPeriodicity,
			Integer periodicityDays, String publicationStatus, Boolean hasGeo, Boolean open, boolean explorable,
			boolean hasApi, String apiTag, DatasetApiEndpoints apiEndpoints, boolean federated, String federatedUrl,
			List<Distribution> distributions, FreshnessSnapshotDto latestSnapshot, Instant observedAt,
			ObservationMethod latestObservationMethod, Instant latestObservedChange, Instant firstSeenAt,
			Instant lastSeenAt, boolean listed, Instant delistedAt) {

		static DatasetDetail from(DatasetListing listing, FreshnessSnapshotDto latest, DatasetApiEndpoints api) {
			Dataset d = listing.dataset();
			return new DatasetDetail(d.sourceId(), d.title(), d.description(), d.issued(), d.declaredModified(),
					d.metadataUpdated(), d.declaredPeriodicity(), d.periodicityDays(), d.publicationStatus(),
					d.hasGeo(), d.open(), d.explorable(), d.hasApiDistribution(), d.apiTag(), api,
					listing.federated(), listing.federatedUrl(),
					d.distributions().stream().map(Distribution::from).toList(), latest, listing.observedAt(),
					listing.latestObservationMethod(), listing.latestObservedChange(), d.firstSeenAt(),
					d.lastSeenAt(), listing.listed(), listing.delistedAt());
		}
	}

	/** Un dataset del publicador municipal en datos.gob.es (S1.3) y si tiene ficha en el catálogo ingerido. */
	record FederatedDatasetDto(int id, String title, String url, boolean inCatalog, Instant firstSeenAt,
			Instant lastSeenAt) {

		static FederatedDatasetDto from(FederationReadModel.FederatedListing listing) {
			FederatedDataset f = listing.dataset();
			return new FederatedDatasetDto(f.sourceId(), f.title(), f.url(), listing.inCatalog(), f.firstSeenAt(),
					f.lastSeenAt());
		}
	}

	record FederationSummary(Instant ingestedAt, long federated, long inCatalog, long notInCatalog,
			long catalogNotFederated) {
	}

	/** Una operación del Swagger de la API (S1.2). */
	record ApiEndpointDto(String tag, String method, String path, String url, String summary, boolean templated,
			Instant firstSeenAt, Instant lastSeenAt) {

		static ApiEndpointDto from(ApiEndpoint e) {
			return new ApiEndpointDto(e.tag(), e.method(), e.path(), e.url(), e.summary(), e.templated(),
					e.firstSeenAt(), e.lastSeenAt());
		}
	}

	/**
	 * Operaciones documentadas bajo el {@code apiTag} de una ficha, con el origen y la fecha de ingesta del
	 * inventario (distintos de los de la ficha). {@code tagDocumented} es {@code null} si la ficha no declara tag.
	 */
	record DatasetApiEndpoints(Source source, Instant ingestedAt, Boolean tagDocumented, List<ApiEndpointDto> items) {
	}

	record DatasetRefDto(int id, String title) {
	}

	record TagSummaryDto(String tag, long endpoints, List<DatasetRefDto> datasets) {

		static TagSummaryDto from(ApiInventoryReadModel.TagSummary t) {
			return new TagSummaryDto(t.tag(), t.endpoints(),
					t.datasets().stream().map(d -> new DatasetRefDto(d.sourceId(), d.title())).toList());
		}
	}

	record ApiInventorySummary(Instant ingestedAt, long endpoints, long tags, long datasetsWithTag,
			long datasetsWithDocumentedTag, long tagsWithoutDataset) {
	}

	record FreshnessSnapshotDto(LocalDate observedOn, Instant takenAt, Integer declaredAgeDays,
			Integer periodicityDays, Double declaredRatio, DeclaredFreshness declared, Instant observedAt,
			ObservationMethod observationMethod, String observedUrl, Instant observedLastChange,
			Integer observedRecords, String observationDetail, String observationError) {

		static FreshnessSnapshotDto from(FreshnessSnapshot s) {
			return new FreshnessSnapshotDto(s.observedOn(), s.takenAt(), s.declaredAgeDays(), s.periodicityDays(),
					s.declaredRatio(), s.declared(), s.observedAt(), s.observationMethod(), s.observedUrl(),
					s.observedLastChange(), s.observedRecords(), s.observationDetail(), s.observationError());
		}
	}

	record Thresholds(double onTimeMax, double slightDelayMax, double delayedMax) {
	}

	record Summary(Source source, Instant ingestedAt, List<String> caveats, long datasets,
			Map<DeclaredFreshness, Long> byDeclaredFreshness, Map<String, Long> byPeriodicity, long withApi,
			long open, long explorable, long withGeo, LocalDate latestSnapshotOn, long withoutSnapshot,
			Map<ObservationMethod, Long> byObservationMethod, long withoutObservation, long notListed,
			ApiInventorySummary apiInventory, FederationSummary federation, Thresholds thresholds) {
	}

}
