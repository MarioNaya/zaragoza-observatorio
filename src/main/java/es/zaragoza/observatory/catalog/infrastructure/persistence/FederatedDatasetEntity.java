package es.zaragoza.observatory.catalog.infrastructure.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import es.zaragoza.observatory.catalog.domain.FederatedDataset;

/** Tabla {@code catalog_federated_dataset} (V007): los datasets del publicador municipal en datos.gob.es (S1.3). */
@Entity
@Table(name = "catalog_federated_dataset")
class FederatedDatasetEntity {

	@Id
	@Column(name = "source_id")
	private Integer sourceId;

	@Column(name = "url", nullable = false, columnDefinition = "text")
	private String url;

	@Column(name = "title", columnDefinition = "text")
	private String title;

	@Column(name = "first_seen_at", nullable = false, columnDefinition = "timestamptz")
	private Instant firstSeenAt;

	@Column(name = "last_seen_at", nullable = false, columnDefinition = "timestamptz")
	private Instant lastSeenAt;

	protected FederatedDatasetEntity() {
	}

	static FederatedDatasetEntity insert(FederatedDataset dataset, Instant seenAt) {
		var entity = new FederatedDatasetEntity();
		entity.sourceId = dataset.sourceId();
		entity.firstSeenAt = seenAt;
		entity.apply(dataset, seenAt);
		return entity;
	}

	void apply(FederatedDataset dataset, Instant seenAt) {
		url = dataset.url();
		title = dataset.title();
		lastSeenAt = seenAt;
	}

	FederatedDataset toDomain() {
		return new FederatedDataset(sourceId, url, title, firstSeenAt, lastSeenAt);
	}

	Integer getSourceId() {
		return sourceId;
	}

	String getUrl() {
		return url;
	}

}
