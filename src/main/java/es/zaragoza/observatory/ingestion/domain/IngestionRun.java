package es.zaragoza.observatory.ingestion.domain;

import java.time.Instant;
import java.util.Objects;

import es.zaragoza.observatory.ingestion.IngestionRunSummary;
import es.zaragoza.observatory.ingestion.RawPage;
import es.zaragoza.observatory.ingestion.RunStatus;
import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.IngestionRunId;

/**
 * Ejecución de ingesta de un dataset (SPEC.md §4.5 paso 5): inicio, fin, registros, estado y error.
 * Agregado mutable con transiciones {@code RUNNING -> SUCCEEDED | FAILED}.
 */
public class IngestionRun {

	private final IngestionRunId id;
	private final DatasetRef dataset;
	private final Instant startedAt;
	private Instant finishedAt;
	private RunStatus status;
	private long records;
	private int pages;
	private Instant sourceLastModified;
	private String error;

	private IngestionRun(IngestionRunId id, DatasetRef dataset, Instant startedAt, Instant finishedAt,
			RunStatus status, long records, int pages, Instant sourceLastModified, String error) {
		this.id = Objects.requireNonNull(id);
		this.dataset = Objects.requireNonNull(dataset);
		this.startedAt = Objects.requireNonNull(startedAt);
		this.finishedAt = finishedAt;
		this.status = Objects.requireNonNull(status);
		this.records = records;
		this.pages = pages;
		this.sourceLastModified = sourceLastModified;
		this.error = error;
	}

	public static IngestionRun start(IngestionRunId id, DatasetRef dataset, Instant now) {
		return new IngestionRun(id, dataset, now, null, RunStatus.RUNNING, 0, 0, null, null);
	}

	/** Reconstrucción desde persistencia; no valida transiciones. */
	public static IngestionRun rehydrate(IngestionRunId id, DatasetRef dataset, Instant startedAt, Instant finishedAt,
			RunStatus status, long records, int pages, Instant sourceLastModified, String error) {
		return new IngestionRun(id, dataset, startedAt, finishedAt, status, records, pages, sourceLastModified, error);
	}

	public void pageFetched(RawPage page) {
		requireRunning();
		pages++;
		records += page.recordCount();
		if (page.sourceLastModified() != null) {
			sourceLastModified = page.sourceLastModified();
		}
	}

	public void succeed(Instant now) {
		requireRunning();
		status = RunStatus.SUCCEEDED;
		finishedAt = Objects.requireNonNull(now);
	}

	public void fail(Instant now, String error) {
		requireRunning();
		status = RunStatus.FAILED;
		finishedAt = Objects.requireNonNull(now);
		this.error = error == null || error.isBlank() ? "unknown error" : error;
	}

	private void requireRunning() {
		if (status != RunStatus.RUNNING) {
			throw new IllegalStateException("run " + id + " already finished with status " + status);
		}
	}

	public IngestionRunSummary toSummary() {
		return new IngestionRunSummary(id, dataset, startedAt, finishedAt, status, records, pages, sourceLastModified,
				error);
	}

	public IngestionRunId id() {
		return id;
	}

	public DatasetRef dataset() {
		return dataset;
	}

	public Instant startedAt() {
		return startedAt;
	}

	public Instant finishedAt() {
		return finishedAt;
	}

	public RunStatus status() {
		return status;
	}

	public long records() {
		return records;
	}

	public int pages() {
		return pages;
	}

	public Instant sourceLastModified() {
		return sourceLastModified;
	}

	public String error() {
		return error;
	}

}
