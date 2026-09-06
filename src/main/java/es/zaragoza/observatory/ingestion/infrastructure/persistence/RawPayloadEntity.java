package es.zaragoza.observatory.ingestion.infrastructure.persistence;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import es.zaragoza.observatory.ingestion.domain.RawPayload;
import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.IngestionRunId;

/** Tabla {@code raw_payload} (V003): página cruda con retención. */
@Entity
@Table(name = "raw_payload")
class RawPayloadEntity {

	@Id
	private UUID id;

	@Column(name = "run_id", nullable = false)
	private UUID runId;

	@Column(name = "source", nullable = false, columnDefinition = "text")
	private String source;

	@Column(name = "dataset_id", nullable = false, columnDefinition = "text")
	private String datasetId;

	@Column(name = "page_number", nullable = false)
	private int pageNumber;

	@Column(name = "url", nullable = false, columnDefinition = "text")
	private String url;

	@Column(name = "content_type", columnDefinition = "text")
	private String contentType;

	@Column(name = "body", nullable = false, columnDefinition = "text")
	private String body;

	@Column(name = "byte_size", nullable = false)
	private int byteSize;

	@Column(name = "fetched_at", nullable = false, columnDefinition = "timestamptz")
	private Instant fetchedAt;

	@Column(name = "source_last_modified", columnDefinition = "timestamptz")
	private Instant sourceLastModified;

	protected RawPayloadEntity() {
	}

	static RawPayloadEntity from(RawPayload payload) {
		var entity = new RawPayloadEntity();
		entity.id = payload.id();
		entity.runId = payload.run().value();
		entity.source = payload.dataset().source();
		entity.datasetId = payload.dataset().id();
		entity.pageNumber = payload.pageNumber();
		entity.url = payload.url().toString();
		entity.contentType = payload.contentType();
		entity.body = payload.body();
		entity.byteSize = payload.byteSize();
		entity.fetchedAt = payload.fetchedAt();
		entity.sourceLastModified = payload.sourceLastModified();
		return entity;
	}

	RawPayload toDomain() {
		return new RawPayload(id, new IngestionRunId(runId), new DatasetRef(source, datasetId), pageNumber,
				URI.create(url), contentType, body, byteSize, fetchedAt, sourceLastModified);
	}

}
