package es.zaragoza.observatory.ingestion;

import java.time.Instant;

import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.IngestionRunId;

/**
 * Vista pública de una ejecución de ingesta (inicio, fin, registros, estado, error; SPEC.md §4.5).
 *
 * @param sourceLastModified último {@code Last-Modified} que devolvió la fuente durante la ejecución, si alguno
 */
public record IngestionRunSummary(IngestionRunId id, DatasetRef dataset, Instant startedAt, Instant finishedAt,
		RunStatus status, long records, int pages, Instant sourceLastModified, String error) {
}
