package es.zaragoza.observatory.ingestion.domain;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import es.zaragoza.observatory.ingestion.RawPage;
import es.zaragoza.observatory.shared.DatasetRef;
import es.zaragoza.observatory.shared.IngestionRunId;

/**
 * Página cruda conservada para depuración y reprocesado (SPEC.md §4.5: tabla {@code raw_payload} con retención).
 */
public record RawPayload(UUID id, IngestionRunId run, DatasetRef dataset, int pageNumber, URI url, String contentType,
		String body, int byteSize, Instant fetchedAt, Instant sourceLastModified) {

	public RawPayload {
		Objects.requireNonNull(id);
		Objects.requireNonNull(run);
		Objects.requireNonNull(dataset);
		Objects.requireNonNull(url);
		Objects.requireNonNull(body);
		Objects.requireNonNull(fetchedAt);
	}

	public static RawPayload of(IngestionRunId run, DatasetRef dataset, RawPage page) {
		return new RawPayload(UUID.randomUUID(), run, dataset, page.number(), page.url(), page.contentType(),
				page.body(), page.byteSize(), page.fetchedAt(), page.sourceLastModified());
	}

}
