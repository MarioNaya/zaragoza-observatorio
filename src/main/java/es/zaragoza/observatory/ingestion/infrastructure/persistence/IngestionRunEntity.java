package es.zaragoza.observatory.ingestion.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import es.zaragoza.observatory.ingestion.RunStatus;
import es.zaragoza.observatory.ingestion.domain.IngestionRun;
import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.IngestionRunId;

/** Tabla {@code ingestion_run} (V003). Mapeo 1:1 del agregado {@link IngestionRun}. */
@Entity
@Table(name = "ingestion_run")
class IngestionRunEntity {

	@Id
	private UUID id;

	@Column(name = "source", nullable = false, columnDefinition = "text")
	private String source;

	@Column(name = "dataset_id", nullable = false, columnDefinition = "text")
	private String datasetId;

	@Column(name = "started_at", nullable = false, columnDefinition = "timestamptz")
	private Instant startedAt;

	@Column(name = "finished_at", columnDefinition = "timestamptz")
	private Instant finishedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, columnDefinition = "text")
	private RunStatus status;

	@Column(name = "records", nullable = false)
	private long records;

	@Column(name = "pages", nullable = false)
	private int pages;

	@Column(name = "source_last_modified", columnDefinition = "timestamptz")
	private Instant sourceLastModified;

	@Column(name = "error", columnDefinition = "text")
	private String error;

	protected IngestionRunEntity() {
	}

	static IngestionRunEntity from(IngestionRun run) {
		var entity = new IngestionRunEntity();
		entity.id = run.id().value();
		entity.source = run.dataset().source();
		entity.datasetId = run.dataset().id();
		entity.startedAt = run.startedAt();
		entity.finishedAt = run.finishedAt();
		entity.status = run.status();
		entity.records = run.records();
		entity.pages = run.pages();
		entity.sourceLastModified = run.sourceLastModified();
		entity.error = run.error();
		return entity;
	}

	IngestionRun toDomain() {
		return IngestionRun.rehydrate(new IngestionRunId(id), new DatasetRef(source, datasetId), startedAt,
				finishedAt, status, records, pages, sourceLastModified, error);
	}

	UUID getId() {
		return id;
	}

}
