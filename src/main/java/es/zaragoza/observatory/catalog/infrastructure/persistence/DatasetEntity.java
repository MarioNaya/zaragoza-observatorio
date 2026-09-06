package es.zaragoza.observatory.catalog.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.domain.DatasetReadModel.DatasetListing;
import es.zaragoza.observatory.catalog.domain.DeclaredFreshness;
import es.zaragoza.observatory.catalog.domain.Observation;
import es.zaragoza.observatory.catalog.domain.ObservationMethod;

/** Tabla {@code catalog_dataset} (V004, V005) más la colección {@code catalog_distribution}. */
@Entity
@Table(name = "catalog_dataset")
class DatasetEntity {

	@Id
	@Column(name = "source_id")
	private Integer sourceId;

	@Column(name = "title", nullable = false, columnDefinition = "text")
	private String title;

	@Column(name = "description", columnDefinition = "text")
	private String description;

	@Column(name = "issued", columnDefinition = "timestamp")
	private LocalDateTime issued;

	@Column(name = "declared_modified", columnDefinition = "timestamp")
	private LocalDateTime declaredModified;

	@Column(name = "metadata_updated", columnDefinition = "timestamp")
	private LocalDateTime metadataUpdated;

	@Column(name = "declared_periodicity", columnDefinition = "text")
	private String declaredPeriodicity;

	@Column(name = "periodicity_days")
	private Integer periodicityDays;

	@Column(name = "publication_status", columnDefinition = "text")
	private String publicationStatus;

	@Column(name = "has_geo", columnDefinition = "boolean")
	private Boolean hasGeo;

	@Column(name = "is_open", columnDefinition = "boolean")
	private Boolean open;

	@Column(name = "explorable", nullable = false, columnDefinition = "boolean")
	private boolean explorable;

	@Column(name = "api_tag", columnDefinition = "text")
	private String apiTag;

	@Column(name = "has_api", nullable = false, columnDefinition = "boolean")
	private boolean hasApi;

	@Enumerated(EnumType.STRING)
	@Column(name = "latest_freshness", columnDefinition = "text")
	private DeclaredFreshness latestFreshness;

	@Column(name = "latest_ratio")
	private Double latestRatio;

	@Column(name = "latest_snapshot_on")
	private LocalDate latestSnapshotOn;

	@Column(name = "observed_at", columnDefinition = "timestamptz")
	private Instant observedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "latest_observation_method", columnDefinition = "text")
	private ObservationMethod latestObservationMethod;

	@Column(name = "latest_observed_change", columnDefinition = "timestamptz")
	private Instant latestObservedChange;

	@Column(name = "first_seen_at", nullable = false, columnDefinition = "timestamptz")
	private Instant firstSeenAt;

	@Column(name = "last_seen_at", nullable = false, columnDefinition = "timestamptz")
	private Instant lastSeenAt;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "catalog_distribution", joinColumns = @JoinColumn(name = "dataset_source_id"))
	@OrderColumn(name = "ordinal")
	private List<DistributionEmbeddable> distributions = new ArrayList<>();

	protected DatasetEntity() {
	}

	static DatasetEntity insert(Dataset dataset, Instant seenAt) {
		var entity = new DatasetEntity();
		entity.sourceId = dataset.sourceId();
		entity.firstSeenAt = seenAt;
		entity.apply(dataset, seenAt);
		return entity;
	}

	/** Copia los campos de la fuente y actualiza {@code lastSeenAt}; conserva primera aparición, frescura y observación. */
	void apply(Dataset dataset, Instant seenAt) {
		title = dataset.title();
		description = dataset.description();
		issued = dataset.issued();
		declaredModified = dataset.declaredModified();
		metadataUpdated = dataset.metadataUpdated();
		declaredPeriodicity = dataset.declaredPeriodicity();
		periodicityDays = dataset.periodicityDays();
		publicationStatus = dataset.publicationStatus();
		hasGeo = dataset.hasGeo();
		open = dataset.open();
		explorable = dataset.explorable();
		apiTag = dataset.apiTag();
		hasApi = dataset.hasApiDistribution();
		lastSeenAt = seenAt;
		distributions.clear();
		dataset.distributions().forEach(d -> distributions.add(DistributionEmbeddable.from(d)));
	}

	void recordLatestFreshness(DeclaredFreshness freshness, Double ratio, LocalDate observedOn) {
		latestFreshness = freshness;
		latestRatio = ratio;
		latestSnapshotOn = observedOn;
	}

	void recordObservation(Observation observation) {
		observedAt = observation.observedAt();
		latestObservationMethod = observation.method();
		latestObservedChange = observation.lastChange();
	}

	Dataset toDomain() {
		return new Dataset(sourceId, title, description, issued, declaredModified, metadataUpdated,
				declaredPeriodicity, periodicityDays, publicationStatus, hasGeo, open, explorable, apiTag,
				distributions.stream().map(DistributionEmbeddable::toDomain).toList(), firstSeenAt, lastSeenAt);
	}

	DatasetListing toListing() {
		return new DatasetListing(toDomain(), latestFreshness, latestRatio, latestSnapshotOn, observedAt,
				latestObservationMethod, latestObservedChange);
	}

	Integer getSourceId() {
		return sourceId;
	}

}
